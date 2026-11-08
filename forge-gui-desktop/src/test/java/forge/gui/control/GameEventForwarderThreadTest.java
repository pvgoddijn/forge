package forge.gui.control;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.testng.annotations.Test;

import forge.game.event.GameEvent;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventTurnEnded;
import forge.game.event.IGameEventVisitor;
import forge.gui.interfaces.IGuiGame;

/** A flush asked for off the game thread is done on the game thread, at its next flush point, and isn't lost. */
public class GameEventForwarderThreadTest {

    /** Something the GUI should draw again, which on its own makes no batch at a pass of priority. */
    private static final class RedrawHint implements GameEvent {
        @Override
        public <T> T visit(IGameEventVisitor<T> visitor) {
            return null;
        }

        @Override
        public boolean isRedrawHint() {
            return true;
        }
    }

    /** The thread each batch was sent on. */
    private final List<Thread> sentOn = new CopyOnWriteArrayList<>();

    private GameEventForwarder newForwarder() {
        sentOn.clear(); // one instance runs every test
        IGuiGame gui = (IGuiGame) Proxy.newProxyInstance(IGuiGame.class.getClassLoader(), new Class<?>[] { IGuiGame.class },
                (proxy, method, args) -> {
                    if ("handleGameEvents".equals(method.getName())) {
                        sentOn.add(Thread.currentThread());
                    }
                    return null;
                });
        return new GameEventForwarder(gui);
    }

    /** Runs {@code r} on a thread that isn't the game's, as a tap on the render thread or a Netty thread would. */
    private static void offTheGameThread(Runnable r) throws InterruptedException {
        Thread t = new Thread(r, "Render");
        t.start();
        t.join();
    }

    // Fails if an input ended on the render thread walks the view there, or if its flush is then dropped
    @Test
    public void aFlushAskedForOffTheGameThreadIsDoneOnTheGameThreadAtItsNextPriority() throws InterruptedException {
        GameEventForwarder forwarder = newForwarder();
        forwarder.receiveGameEvent(new RedrawHint()); // this (test) thread delivers the game's events

        offTheGameThread(() -> forwarder.update(null, null)); // an input ended by a tap

        assertEquals(sentOn, List.of(), "nothing is sent off the game thread");
        assertTrue(forwarder.isFlushDue(), "the flush is due");

        // a pass of priority after nothing but a redraw hint sends nothing by itself, but the due flush goes
        forwarder.receiveGameEvent(new GameEventPlayerPriority(null, null, null));
        assertEquals(sentOn, List.of(Thread.currentThread()), "the game thread sent it at its next priority");
        assertFalse(forwarder.isFlushDue());
        assertFalse(forwarder.hasPendingEvents());
    }

    // Fails if a due flush waits past the end of the turn
    @Test
    public void aDueFlushGoesAtTheEndOfTheTurn() throws InterruptedException {
        GameEventForwarder forwarder = newForwarder();
        forwarder.receiveGameEvent(new RedrawHint());
        offTheGameThread(forwarder::flush);

        forwarder.receiveGameEvent(new GameEventTurnEnded());
        assertEquals(sentOn, List.of(Thread.currentThread()), "sent on the game thread before the turn's end");
    }

    // Fails if the last flush, when the forwarder is let go off the game thread, is dropped
    @Test
    public void flushNowSendsFromAnyThread() throws InterruptedException {
        GameEventForwarder forwarder = newForwarder();
        forwarder.receiveGameEvent(new RedrawHint());
        offTheGameThread(forwarder::flushNow);
        assertEquals(sentOn.size(), 1, "sent at once");
        assertFalse(forwarder.hasPendingEvents());
    }
}
