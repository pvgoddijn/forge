package forge.net;

import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventTurnBegan;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gui.control.GameEventForwarder;
import forge.trackable.TrackableCollection;

/**
 * A reconnect's handshake ({@code FServerManager.resumeAndResync}) runs on a Netty thread while game events wait for
 * the game thread's next flush point. The client must still get the game view before {@code openView}.
 */
public class ProtocolGuiGameReconnectTest extends AITest {

    /** The protocol methods sent, in order, whatever the thread. */
    private static final class OrderRemote implements IRemote {
        final List<ProtocolMethod> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(final NetEvent event) {
            sent.add(((GuiGameEvent) event).getMethod());
        }

        @Override
        public Object sendAndWait(final IdentifiableNetEvent event) {
            return null;
        }
    }

    private static void on(final Thread t) throws InterruptedException {
        t.start();
        t.join();
    }

    // Fails if a reconnect with an event waiting sends openView before any game view (the waiting flush only marked due)
    @Test
    public void aReconnectWithAnEventWaitingSendsTheGameViewBeforeOpenView() throws InterruptedException {
        final Game game = initAndCreateGame();
        final OrderRemote remote = new OrderRemote();
        final ProtocolGuiGame gui = new ProtocolGuiGame(remote);
        gui.setGameView(game.getView());
        final GameEventForwarder forwarder = new GameEventForwarder(gui);
        gui.setForwarder(forwarder);

        // the game loop starts the game on its thread, then an event waits for its next flush point
        on(new Thread(() -> {
            forwarder.receiveGameEvent(new GameEventGameStarted(game.getRules().getGameType(), (forge.game.player.PlayerView) null, List.of()));
            forwarder.receiveGameEvent(new GameEventTurnBegan(null, 1));
        }, "Game-loop"));
        assertTrue(forwarder.hasPendingEvents(), "an event waits");

        // the client drops and comes back; resumeAndResync on a Netty thread
        remote.sent.clear();
        on(new Thread(() -> {
            gui.resume();
            gui.resetForReconnect();
            gui.updateGameView();
            gui.openView(new TrackableCollection<>(game.getView().getPlayers()));
        }, "netty-worker"));

        final int view = remote.sent.indexOf(ProtocolMethod.setGameView);
        final int open = remote.sent.indexOf(ProtocolMethod.openView);
        assertTrue(open >= 0, "openView sent: " + remote.sent);
        assertTrue(view >= 0 && view < open, "the game view goes before openView: " + remote.sent);
    }
}
