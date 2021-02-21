package mpo.dayon.assisted.control;

import mpo.dayon.common.network.message.NetworkKeyControlMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;

import java.awt.*;
import java.awt.event.KeyEvent;

import static mpo.dayon.common.network.message.NetworkKeyControlMessage.KeyState.PRESSED;
import static mpo.dayon.common.network.message.NetworkKeyControlMessage.KeyState.RELEASED;
import static org.junit.jupiter.api.condition.OS.WINDOWS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RobotNetworkControlMessageHandlerTest {

    Robot robot = mock(Robot.class);
    RobotNetworkControlMessageHandler controlMessageHandler = new RobotNetworkControlMessageHandler(robot);

    @Test
    void testHandleMessagePressA() {
        // given
        NetworkKeyControlMessage message = new NetworkKeyControlMessage(PRESSED, 65, 'A');
        // when
        controlMessageHandler.handleMessage(message);
        // then
        verify(robot).keyPress(message.getKeyChar());
    }

    @Test
    void testHandleMessageReleaseA() {
        // given
        NetworkKeyControlMessage message = new NetworkKeyControlMessage(RELEASED, 65, 'A');
        // when
        controlMessageHandler.handleMessage(message);
        // then
        verify(robot).keyRelease(message.getKeyChar());
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void testHandleMessagePressTurkishLowerSpecialSTypedOnNonTurkishAssisted() {
        // given
        NetworkKeyControlMessage message = new NetworkKeyControlMessage(PRESSED, 0, 'ş');
        // when
        controlMessageHandler.handleMessage(message);
        // then
        verify(robot).keyPress(KeyEvent.VK_CONTROL);
        verify(robot).keyPress(KeyEvent.VK_SHIFT);
        verify(robot).keyPress(KeyEvent.VK_U);
        verify(robot).keyRelease(KeyEvent.VK_U);
        // 351 as hex 15F
        verify(robot).keyPress(KeyEvent.VK_1);
        verify(robot).keyRelease(KeyEvent.VK_1);
        verify(robot).keyPress(KeyEvent.VK_5);
        verify(robot).keyRelease(KeyEvent.VK_5);
        verify(robot).keyPress(KeyEvent.VK_F);
        verify(robot).keyRelease(KeyEvent.VK_F);
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void testHandleMessageReleaseTurkishLowerSpecialSTypedOnNonTurkishAssisted() {
        // given
        NetworkKeyControlMessage message = new NetworkKeyControlMessage(RELEASED, 0, 'ş');
        // when
        controlMessageHandler.handleMessage(message);
        // then
        verify(robot).keyRelease(KeyEvent.VK_SHIFT);
        verify(robot).keyRelease(KeyEvent.VK_CONTROL);
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void testHandleMessagePressTurkishUpperSpecialSTypedOnNonTurkishAssisted() {
        // given
        NetworkKeyControlMessage message = new NetworkKeyControlMessage(PRESSED, 0, 'Ş');
        // when
        controlMessageHandler.handleMessage(message);
        // then
        verify(robot).keyPress(KeyEvent.VK_CONTROL);
        verify(robot).keyPress(KeyEvent.VK_SHIFT);
        verify(robot).keyPress(KeyEvent.VK_U);
        verify(robot).keyRelease(KeyEvent.VK_U);
        // 350 as hex 15E
        verify(robot).keyPress(KeyEvent.VK_1);
        verify(robot).keyRelease(KeyEvent.VK_1);
        verify(robot).keyPress(KeyEvent.VK_5);
        verify(robot).keyRelease(KeyEvent.VK_5);
        verify(robot).keyPress(KeyEvent.VK_E);
        verify(robot).keyRelease(KeyEvent.VK_E);
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void testHandleMessageReleaseTurkishUpperSpecialSTypedOnNonTurkishAssisted() {
        // given
        NetworkKeyControlMessage message = new NetworkKeyControlMessage(RELEASED, 0, 'Ş');
        // when
        controlMessageHandler.handleMessage(message);
        // then
        verify(robot).keyRelease(KeyEvent.VK_SHIFT);
        verify(robot).keyRelease(KeyEvent.VK_CONTROL);
    }
}