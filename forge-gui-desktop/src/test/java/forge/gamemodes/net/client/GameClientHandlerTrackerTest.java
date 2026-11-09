package forge.gamemodes.net.client;

import forge.ai.AITest;
import forge.game.Game;
import forge.gamemodes.net.ProtocolMethod;
import forge.gui.interfaces.IGuiGame;
import forge.net.HeadlessNetworkGuiGame;
import forge.trackable.Tracker;
import io.netty.channel.embedded.EmbeddedChannel;
import org.testng.annotations.Test;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;

/**
 * Multi-phone slice 04, Gate B: a mode that keeps the shown view's tracker across connections (Companion) takes it only
 * once its own first view came. Mobile's GUI is one for the app ({@code MatchController.instance}) and keeps the last
 * match's view, so a fresh client's first connection must not adopt that match's tracker.
 */
public class GameClientHandlerTrackerTest extends AITest {
    /** As Companion's client: keeps the shown view's tracker across connections. */
    private static final class KeepingClient extends FGameClient {
        KeepingClient(IGuiGame gui) {
            super("Ann", gui, "localhost", 1);
        }

        @Override
        protected boolean keepsTrackerAcrossConnections() {
            return true;
        }
    }

    /** {@code handler}'s first game view, as it comes off its connection. */
    private static void firstView(GameClientHandler handler, Game game) {
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            handler.beforeCall(channel.pipeline().context(handler), ProtocolMethod.setGameView,
                    new Object[] { game.getView() });
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void aFreshClientStartsWithATrackerOfItsOwnThoughTheGuiShowsAnEarlierMatch() {
        HeadlessNetworkGuiGame gui = new HeadlessNetworkGuiGame();
        gui.setGameView(initAndCreateGame().getView()); // the previous match's view, left in the GUI
        Tracker leftover = gui.getGameView().getTracker();
        assertNotNull(leftover);
        KeepingClient client = new KeepingClient(gui);
        try {
            GameClientHandler first = new GameClientHandler(client);
            firstView(first, initAndCreateGame());
            assertNotNull(first.tracker());
            assertNotSame(first.tracker(), leftover, "the first connection starts with a tracker of its own");

            // a reconnect, once this client's own view came: the shown view's tracker is kept
            Tracker shown = gui.getGameView().getTracker();
            GameClientHandler again = new GameClientHandler(client);
            firstView(again, initAndCreateGame());
            assertSame(again.tracker(), shown, "a reconnect keeps the tracker of the view shown");
        } finally {
            client.close();
        }
    }
}
