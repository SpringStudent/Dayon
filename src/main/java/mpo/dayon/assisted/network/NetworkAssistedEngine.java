package mpo.dayon.assisted.network;

import mpo.dayon.common.compressor.CompressorEngineConfiguration;
import mpo.dayon.common.compressor.CompressorEngineListener;
import mpo.dayon.assisted.control.NetworkControlMessageHandler;
import mpo.dayon.assisted.mouse.MouseEngineListener;
import mpo.dayon.common.buffer.MemByteBuffer;
import mpo.dayon.common.concurrent.RunnableEx;
import mpo.dayon.common.configuration.ReConfigurable;
import mpo.dayon.common.error.FatalErrorHandler;
import mpo.dayon.common.event.Listeners;
import mpo.dayon.common.log.Log;
import mpo.dayon.common.network.NetworkEngine;
import mpo.dayon.common.network.Token;
import mpo.dayon.common.network.message.*;
import mpo.dayon.common.security.CustomTrustManager;
import mpo.dayon.common.squeeze.CompressionMethod;

import javax.net.ssl.*;
import java.awt.*;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.time.Duration;

import static java.lang.String.format;

import static mpo.dayon.assisted.gui.Assisted.TOKEN_PARAMS;
import static mpo.dayon.common.configuration.Configuration.DEFAULT_TOKEN_SERVER_URL;
import static mpo.dayon.common.utils.SystemUtilities.*;

public class NetworkAssistedEngine extends NetworkEngine
        implements ReConfigurable<NetworkAssistedEngineConfiguration>, CompressorEngineListener, MouseEngineListener {
    private NetworkAssistedEngineConfiguration configuration;

    private final NetworkCaptureConfigurationMessageHandler captureConfigurationHandler;

    private final NetworkCompressorConfigurationMessageHandler compressorConfigurationHandler;

    private final NetworkControlMessageHandler controlHandler;

    private final NetworkClipboardRequestMessageHandler clipboardRequestHandler;

    private final NetworkScreenshotRequestMessageHandler screenshotRequestHandler;

    private final ClipboardOwner clipboardOwner;

    private final Listeners<NetworkAssistedEngineListener> listeners = new Listeners<>();

    private final char osId = System.getProperty("os.name").toLowerCase().charAt(0);

    private Token token;

    private SSLSocketFactory ssf;

    private String publicIp;

    public NetworkAssistedEngine(NetworkCaptureConfigurationMessageHandler captureConfigurationHandler,
                                 NetworkCompressorConfigurationMessageHandler compressorConfigurationHandler,
                                 NetworkControlMessageHandler controlHandler,
                                 NetworkClipboardRequestMessageHandler clipboardRequestHandler,
                                 NetworkScreenshotRequestMessageHandler screenshotRequestHandler, ClipboardOwner clipboardOwner) {
        this.captureConfigurationHandler = captureConfigurationHandler;
        this.compressorConfigurationHandler = compressorConfigurationHandler;
        this.controlHandler = controlHandler;
        this.clipboardRequestHandler = clipboardRequestHandler;
        this.screenshotRequestHandler = screenshotRequestHandler;
        this.clipboardOwner = clipboardOwner;
    }

    public NetworkAssistedEngineConfiguration getConfiguration() {
        return configuration;
    }

    private void runReceivers() {
        this.receiver = new Thread(new RunnableEx() {
            @Override
            protected void doRun() {
                NetworkAssistedEngine.this.receivingLoop();
            }
        }, "CommandReceiver");

        this.fileReceiver = new Thread(new RunnableEx() {
            @Override
            protected void doRun() {
                NetworkAssistedEngine.this.fileReceivingLoop();
            }
        }, "FileReceiver");
    }

    @Override
    public void configure(NetworkAssistedEngineConfiguration configuration) {
        Log.debug(format("New configuration %s", configuration));
        this.configuration = configuration;
    }

    @Override
    public void reconfigure(NetworkAssistedEngineConfiguration configuration) {
        this.configuration = configuration;
        fireOnReconfigured(configuration);
    }

    public void addListener(NetworkAssistedEngineListener listener) {
        listeners.add(listener);
    }

    public void connect(Token token) {
        this.token = token;
        try {
            start();
        } catch (UnknownHostException e) {
            fireOnHostNotFound(configuration);
        } catch (SocketTimeoutException e) {
            fireOnConnectionTimeout(configuration);
        } catch (IOException e) {
            closeConnections();
            fireOnRefused(configuration);
        } catch (NoSuchAlgorithmException | KeyManagementException | CertificateEncodingException e) {
            FatalErrorHandler.bye(e.getMessage(), e);
        }
    }

    @SuppressWarnings("java:S2095") // our sockets MUST NOT be closed
    private void start() throws IOException, NoSuchAlgorithmException, KeyManagementException, CertificateEncodingException {
        Log.debug(token.toString());
        ssf = CustomTrustManager.initSslContext(false).getSocketFactory();

        if (token.getTokenString() != null && token.getPeerAddress() == null) {
            Log.debug("Incomplete Token, resolving " + token);
            // got public ip and able to expose port?
            detectEnvironment();
            checkAndUpdateRVS(true);
        }
        fireOnConnecting(configuration);

        // the assistant is not accessible check if reverting the connection initialisation is an option
        if (token.getTokenString() != null && Boolean.FALSE.equals(token.isPeerAccessible())) {
            fireOnPeerIsAccessible(false);
            Log.info("Assistant is not accessible");
            // got public ip and able to expose port?
            detectEnvironment();
            // update the rvs
            checkAndUpdateRVS(false);
            Log.debug(String.valueOf(token));
            // revert the connection and start server if necessary and possible
            if (Boolean.TRUE.equals(isOwnPortAccessible.get()) && Boolean.FALSE.equals(token.isPeerAccessible())) {
                fireOnAccepting(token.getPort());
                startServer();
                Log.debug("Connected");
            } else {
                // guess we are out of options
                Log.debug("Out of options");
                fireOnRefused(configuration);
            }
        }

        if (token.getTokenString() == null || token.isPeerAccessible()) {
            fireOnPeerIsAccessible(true);
            Log.debug("Assistant is accessible");
            Log.info(format("Connecting to [%s][%s]...", configuration.getServerName(), configuration.getServerPort()));
            connectToAssistant();
        }

        // common part
        createInputStream();
        runReceiversIfNecessary();
        receiver.start();
        initSender(1);
        // The first message being sent to the assistant (e.g. version identification, locale and OS).
        sender.sendHello(osId);

        // if we were initiation the connection, we also need to establish a file connection
        if (Boolean.FALSE.equals(isOwnPortAccessible.get())) {
            fileConnection = (SSLSocket) ssf.createSocket(configuration.getServerName(), configuration.getServerPort());
            Log.debug("File connection established");
        }
        // common part
        initFileSender();
        createFileInputStream();
        fileReceiver.start();
        Log.info("Connected with the assistant!");
        fireOnConnected(CustomTrustManager.calculateFingerprints(connection.getSession(), this.getClass().getSimpleName()));
    }

    private void startServer() throws NoSuchAlgorithmException, KeyManagementException {
        SSLServerSocketFactory sssf;
        try {
            sssf = CustomTrustManager.initSslContext(false).getServerSocketFactory();
            Log.info(format("Dayon! server [port:%d]", configuration.getServerPort()));
            server = (SSLServerSocket) sssf.createServerSocket(configuration.getServerPort());
            server.setNeedClientAuth(true);
            Log.info("Accepting...");
            connection = (SSLSocket) server.accept();
            Toolkit.getDefaultToolkit().beep();
            Log.info(format("Incoming connection from %s", connection.getInetAddress().getHostAddress()));
            safeClose(server);
        } catch (IOException e) {
            Log.error("Error accepting incoming connection", e);
            closeConnections();
            return;
        }

        try {
            fileServer = (SSLServerSocket) sssf.createServerSocket(configuration.getServerPort());
            fileConnection = (SSLSocket) fileServer.accept();
            safeClose(fileServer);
            Log.debug("File connection established");
        } catch (IOException e) {
            Log.error("Error establishing file connection", e);
            closeConnections();
        }
    }

    private void checkAndUpdateRVS(boolean incomplete) throws IOException {
        try {
            String queryParams = incomplete? TOKEN_PARAMS + "&inc" : TOKEN_PARAMS;
            String tokenServerUrl = configuration.getTokenServerUrl().isEmpty() ? DEFAULT_TOKEN_SERVER_URL : configuration.getTokenServerUrl();
            final String connectionParams = resolveToken(tokenServerUrl + queryParams, token.getTokenString(), isOwnPortAccessible.get());
            String[] parts = connectionParams.split("\\*");
            if (parts.length > 1) {
                String assistantAddress = parts[0];
                String port = parts[1];
                if (parts.length > 4) {
                    token.updateToken(assistantAddress, Integer.parseInt(port), parts[3].equals("0"));
                } else {
                    token.updateToken(assistantAddress, Integer.parseInt(port), null);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void detectEnvironment() throws IOException {
        if (publicIp == null) {
            try {
                publicIp = resolvePublicIp();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!selfTest(publicIp, configuration.getServerPort())) {
            if (!manageRouterPorts(0, configuration.getServerPort())) {
                Log.warn(format("Port %d forwarding failed", configuration.getServerPort()));
            } else {
                Log.info(format("Port %d forwarding successful", configuration.getServerPort()));
            }
        } else {
            Log.info(format("Port %d open", configuration.getServerPort()));
        }
    }

    private void connectToAssistant() {
        try {
            connection = (SSLSocket) ssf.createSocket();
            connection.setNeedClientAuth(true);
            // grace period of 15 seconds for the assistant to accept the connection
            connection.setSoTimeout(15000);
            // abort the connection attempt after 5 seconds if the assistant cannot be reached
            connection.connect(new InetSocketAddress(configuration.getServerName(), configuration.getServerPort()), 5000);
            // once connected, remain connected until cancelled
            connection.setSoTimeout(0);
        } catch (IOException e) {
            Log.warn("Unable to connect to the assistant");
        }
    }

    private void createFileInputStream() throws IOException {
        fileIn = new ObjectInputStream(new BufferedInputStream(fileConnection.getInputStream()));
    }

    private void runReceiversIfNecessary() {
        if (receiver == null) {
            Log.info("Getting the receivers ready");
            runReceivers();
        }
    }

    public static String resolveToken(String tokenServerUrl, String token, Boolean open) throws IOException, InterruptedException {
        if (open == null) {
            isOwnPortAccessible.set(null);
        }
        // null = unknown = -1, true = open = 1, false = closed = 0
        String query = format(tokenServerUrl, token, toInt(open));
        Log.debug("Resolving token using: " + query);
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(query))
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        final String responseString = response.body().trim();
        Log.debug("Token resolved: " + responseString);
        return responseString;
    }

    private static int toInt(Boolean open) {
        if (open == null) {
            return -1;
        }
        return open ? 1 : 0;
    }

    /**
     * Called from a GUI action => do not block the AWT thread (!)
     */
    public void cancel() {
        Log.info("Cancelling the network assisted engine...");
        cancelling.set(true);
        closeConnections();
        fireOnDisconnecting();
    }

    private void receivingLoop() {

        try {
            //noinspection InfiniteLoopStatement
            while (true) {
                NetworkMessage.unmarshallMagicNumber(in); // blocking read (!)
                NetworkMessageType type = NetworkMessage.unmarshallEnum(in, NetworkMessageType.class);
                Log.debug("Received %s", type::name);

                switch (type) {
                    case CAPTURE_CONFIGURATION:
                        captureConfigurationHandler.handleConfiguration(NetworkCaptureConfigurationMessage.unmarshall(in));
                        break;
                    case COMPRESSOR_CONFIGURATION:
                        compressorConfigurationHandler.handleConfiguration(NetworkCompressorConfigurationMessage.unmarshall(in));
                        break;
                    case MOUSE_CONTROL:
                        controlHandler.handleMessage(NetworkMouseControlMessage.unmarshall(in));
                        break;
                    case KEY_CONTROL:
                        controlHandler.handleMessage(NetworkKeyControlMessage.unmarshall(in));
                        break;
                    case CLIPBOARD_REQUEST:
                        clipboardRequestHandler.handleClipboardRequest();
                        break;
                    case CLIPBOARD_TEXT:
                        var clipboardTextMessage = NetworkClipboardTextMessage.unmarshall(in);
                        setClipboardContents(clipboardTextMessage.getText(), clipboardOwner);
                        sender.ping();
                        break;
                    case CLIPBOARD_GRAPHIC:
                        var clipboardGraphicMessage = NetworkClipboardGraphicMessage.unmarshall(in);
                        setClipboardContents(clipboardGraphicMessage.getGraphic().getTransferData(DataFlavor.imageFlavor), clipboardOwner);
                        sender.ping();
                        break;
                    case SCREENSHOT_REQUEST:
                        screenshotRequestHandler.handleScreenshotRequest();
                        break;
                    case PING:
                        break;
                    default:
                        throw new IllegalArgumentException(format(UNSUPPORTED_TYPE, type));
                }
            }
        } catch (IOException ex) {
            handleIOException(ex);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        } finally {
            closeConnections();
            fireOnDisconnecting();
        }
    }

    private void fileReceivingLoop() {
        try {
            handleIncomingClipboardFiles(fileIn, clipboardOwner);
        } catch (IOException ex) {
            closeConnections();
        }
    }

    /**
     * May block (!)
     * <p/>
     * We're receiving a fully compressed (and ready to send over the network)
     * capture.
     */
    @Override
    public void onCompressed(int captureId, CompressionMethod compressionMethod, CompressorEngineConfiguration compressionConfiguration,
                             MemByteBuffer compressed) {
        if (sender != null) {
            sender.sendCapture(captureId, compressionMethod, compressionConfiguration, compressed);
        }
    }

    /**
     * May block (!)
     */
    @Override
    public boolean onLocationUpdated(Point location) {
        return sender == null || sender.sendMouseLocation(location);
    }

    public void sendResizeScreen(int width, int height) {
        if (sender != null) {
            sender.sendResizeScreen(width, height);
        }
    }

    public void farewell() {
        if (sender != null) {
            sender.sendGoodbye();
        }
    }

    private void fireOnConnected(String fingerprints) {
        listeners.getListeners().forEach(listener -> listener.onConnected(fingerprints));
    }

    @Override
    protected void fireOnClipboardReceived() {
        // let the assistant know that we're done
        sender.ping();
    }

    private void fireOnConnecting(NetworkAssistedEngineConfiguration configuration) {
        listeners.getListeners().forEach(listener -> listener.onConnecting(configuration.getServerName(), configuration.getServerPort()));
    }

    private void fireOnPeerIsAccessible(boolean isPeerAccessible) {
        listeners.getListeners().forEach(listener -> listener.onPeerIsAccessible(isPeerAccessible));
    }

    private void fireOnHostNotFound(NetworkAssistedEngineConfiguration configuration) {
        listeners.getListeners().forEach(listener -> listener.onHostNotFound(configuration.getServerName()));
    }

    private void fireOnConnectionTimeout(NetworkAssistedEngineConfiguration configuration) {
        listeners.getListeners().forEach(listener -> listener.onConnectionTimeout(configuration.getServerName(), configuration.getServerPort()));
    }

    private void fireOnRefused(NetworkAssistedEngineConfiguration configuration) {
        listeners.getListeners().forEach(listener -> listener.onRefused(configuration.getServerName(), configuration.getServerPort()));
    }

    private void fireOnDisconnecting() {
        listeners.getListeners().forEach(NetworkAssistedEngineListener::onDisconnecting);
    }

    @Override
    protected void fireOnIOError(IOException ex) {
        listeners.getListeners().forEach(listener -> listener.onIOError(ex));
    }

    private void fireOnAccepting(int port) {
        listeners.getListeners().forEach(listener -> listener.onAccepting(port));
    }

    private void fireOnReconfigured(NetworkAssistedEngineConfiguration configuration) {
        listeners.getListeners().forEach(listener -> listener.onReconfigured(configuration));
    }

}
