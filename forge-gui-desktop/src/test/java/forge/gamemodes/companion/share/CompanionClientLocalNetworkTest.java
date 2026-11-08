package forge.gamemodes.companion.share;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.testng.annotations.Test;

import forge.gamemodes.net.client.FGameClient.ReconnectState;
import forge.interfaces.IDeviceAdapter;
import forge.net.HeadlessNetworkGuiGame;
import forge.net.TestUtils;

/**
 * A joining client's local network: each connection's socket comes from the device (bound to Wi-Fi on Android, so
 * only that socket is), and the client lets go of the local network on every way out.
 */
public class CompanionClientLocalNetworkTest {

    /** A device recording {@code holdLocalNetwork} calls, and handing out sockets it keeps. */
    private static final class Device {
        final List<Boolean> holds = new CopyOnWriteArrayList<>();
        final List<SocketChannel> sockets = new CopyOnWriteArrayList<>();
        final IDeviceAdapter adapter = (IDeviceAdapter) Proxy.newProxyInstance(IDeviceAdapter.class.getClassLoader(),
                new Class<?>[] { IDeviceAdapter.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "holdLocalNetwork" -> holds.add((Boolean) args[0]);
                        case "openLocalNetworkSocket" -> {
                            SocketChannel socket = SocketChannel.open();
                            sockets.add(socket);
                            return socket;
                        }
                        default -> { }
                    }
                    return null;
                });
    }

    /** Lets a test say what net play's reconnect would. */
    private static final class Client extends CompanionClient {
        Client(JoinCode code, IDeviceAdapter device) {
            super("Joiner", new HeadlessNetworkGuiGame(), code, device);
        }

        void reconnect(ReconnectState state) {
            onReconnectStateChanged(state);
        }
    }

    private static JoinCode codeFor(int port) {
        try {
            return new JoinCode((Inet4Address) InetAddress.getByName("127.0.0.1"), port, 1234);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Fails if a first connect that fails keeps holding the local network (mutation m6: Netty's undeclared
    // ConnectException slipped past a catch of RuntimeException)
    @Test(timeOut = 30_000)
    public void aFailedFirstConnectLetsGoOfTheLocalNetwork() throws IOException {
        TestUtils.ensureFModelInitialized();
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = probe.getLocalPort();
        }
        Device device = new Device();
        Client client = new Client(codeFor(closedPort), device.adapter);
        try {
            try {
                client.connect();
                fail("nothing listens there");
            } catch (Exception expected) { // a ConnectException, rethrown by Netty undeclared
                assertTrue(expected instanceof java.net.ConnectException, "refused: " + expected);
            }
            // before any close(): the failed connect alone lets go
            assertEquals(device.sockets.size(), 1, "the device made the socket");
            assertEquals(device.holds, List.of(true, false), "held to connect, let go when it failed");
            assertFalse(client.isHoldingLocalNetwork());
        } finally {
            client.close();
        }
        assertEquals(device.holds, List.of(true, false), "close() after that makes no second call");
    }

    // Fails if the connection doesn't use the device's socket, or if the reconnect giving up or losing the seat
    // keeps holding the local network
    @Test(timeOut = 30_000)
    public void theDevicesSocketConnectsAndAFailedReconnectLetsGo() throws IOException {
        TestUtils.ensureFModelInitialized();
        for (ReconnectState end : List.of(ReconnectState.FAILED, ReconnectState.SEAT_LOST)) {
            try (ServerSocket host = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                Device device = new Device();
                Client client = new Client(codeFor(host.getLocalPort()), device.adapter);
                try {
                    client.connect();
                    try (Socket accepted = host.accept()) {
                        assertNotNull(accepted);
                        assertEquals(device.sockets.size(), 1, "one connection, one socket from the device");
                        SocketChannel socket = device.sockets.get(0);
                        assertTrue(socket.isConnected(), "Netty connected the device's own socket");
                        assertEquals(socket.socket().getLocalPort(), accepted.getPort(), "and that is the one the host sees");
                        assertEquals(device.holds, List.of(true), "held while joined");

                        client.reconnect(ReconnectState.RECONNECTING);
                        assertEquals(device.holds, List.of(true), "still held while it reconnects");
                        client.reconnect(end);
                        assertEquals(device.holds, List.of(true, false), "let go when the reconnect ends " + end);
                    }
                } finally {
                    client.close();
                }
                assertEquals(device.holds, List.of(true, false), "close() after that makes no second call");
            }
        }
    }
}
