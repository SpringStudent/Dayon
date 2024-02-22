package mpo.dayon.common.network.message;

import mpo.dayon.common.network.TransferableImage;

import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public class NetworkClipboardGraphicMessage extends NetworkMessage {

    private final TransferableImage payload;
    private final int size;

    public NetworkClipboardGraphicMessage(TransferableImage payload) {
        this.payload = payload;
        // this is just a rather rough estimation
        this.size = payload.getTransferData(DataFlavor.imageFlavor).getData().getDataBuffer().getSize() * 4;
    }

    @java.lang.SuppressWarnings("squid:S5135") // assistant and assisted trust each other
    public static NetworkClipboardGraphicMessage unmarshall(ObjectInputStream in) throws IOException, ClassNotFoundException {
        TransferableImage graphic = (TransferableImage) in.readUnshared();
        return new NetworkClipboardGraphicMessage(graphic);
    }

    public TransferableImage getGraphic() {
        return payload;
    }

    @Override
    public NetworkMessageType getType() {
        return NetworkMessageType.CLIPBOARD_GRAPHIC;
    }

    @Override
    public int getWireSize() {
        return 1 + size;
    }

    @Override
    public void marshall(ObjectOutputStream out) throws IOException {
        marshallEnum(out, getType());
        out.writeObject(payload);
    }

    @Override
    public String toString() {
        return "Clipboard graphic";
    }

}
