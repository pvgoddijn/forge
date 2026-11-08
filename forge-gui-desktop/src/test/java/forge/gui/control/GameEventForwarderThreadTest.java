package forge.gui.control;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import forge.game.event.GameEvent;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.PlayerView;
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
        on("Render", r);
    }

    private static void on(String name, Runnable r) throws InterruptedException {
        Thread t = new Thread(r, name);
        t.start();
        t.join();
    }

    /** The game loop starts the game on this thread, which makes it the game thread. */
    private static void startOnThisThread(GameEventForwarder forwarder) {
        forwarder.receiveGameEvent(new GameEventGameStarted(null, (PlayerView) null, List.of()));
    }

    // Fails if an input ended on the render thread walks the view there, or if its flush is then dropped
    @Test
    public void aFlushAskedForOffTheGameThreadIsDoneOnTheGameThreadAtItsNextPriority() throws InterruptedException {
        GameEventForwarder forwarder = newForwarder();
        startOnThisThread(forwarder); // this (test) thread runs the game loop
        forwarder.receiveGameEvent(new RedrawHint());

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
        startOnThisThread(forwarder);
        forwarder.receiveGameEvent(new RedrawHint());
        offTheGameThread(forwarder::flush);

        forwarder.receiveGameEvent(new GameEventTurnEnded());
        assertEquals(sentOn, List.of(Thread.currentThread()), "sent on the game thread before the turn's end");
    }

    // Fails if the last flush, when the forwarder is let go off the game thread, is dropped
    @Test
    public void flushNowSendsFromAnyThread() throws InterruptedException {
        GameEventForwarder forwarder = newForwarder();
        startOnThisThread(forwarder);
        forwarder.receiveGameEvent(new RedrawHint());
        offTheGameThread(forwarder::flushNow);
        assertEquals(sentOn.size(), 1, "sent at once");
        assertFalse(forwarder.hasPendingEvents());
    }

    // Fails if a thread named like a game thread ("Game BT n", where net play answers run) or a thread that only
    // delivered an event (the EDT, when a combat input changes) counts as the game thread
    @Test
    public void onlyTheThreadThatStartedTheGameFlushes() throws InterruptedException {
        GameEventForwarder forwarder = newForwarder();
        Thread[] loop = new Thread[1];
        on("Game-7", () -> {
            loop[0] = Thread.currentThread();
            startOnThisThread(forwarder);
        });
        assertEquals(forwarder.getGameThread(), loop[0]);

        on("Game BT3", () -> {
            forwarder.receiveGameEvent(new RedrawHint()); // an event fired off the game loop
            forwarder.update(null, null); // and an input ended there
        });
        on("Game AI Eval", forwarder::flush);
        assertEquals(sentOn, List.of(), "nothing is sent off the game thread");
        assertTrue(forwarder.isFlushDue());
        assertEquals(forwarder.getGameThread(), loop[0], "delivering an event doesn't make a thread the game thread");
    }

    // Fails if a flush from another thread encodes while the game thread's flush is still encoding
    @Test
    public void twoFlushesNeverEncodeAtTheSameTime() throws InterruptedException {
        sentOn.clear();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch encoding = new CountDownLatch(1);
        IGuiGame gui = (IGuiGame) Proxy.newProxyInstance(IGuiGame.class.getClassLoader(), new Class<?>[] { IGuiGame.class },
                (proxy, method, args) -> {
                    if ("handleGameEvents".equals(method.getName())) {
                        most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        encoding.countDown();
                        Thread.sleep(200);
                        inside.decrementAndGet();
                    }
                    return null;
                });
        GameEventForwarder forwarder = new GameEventForwarder(gui);
        Thread game = new Thread(() -> {
            startOnThisThread(forwarder);
            forwarder.receiveGameEvent(new GameEventTurnBegan(null, 1));
            forwarder.receiveGameEvent(new GameEventPlayerPriority(null, null, null)); // flushes, encoding for 200 ms
        }, "Game-8");
        game.start();
        assertTrue(encoding.await(5, TimeUnit.SECONDS));
        on("EDT", () -> { // meanwhile an event comes from the EDT and the forwarder is let go
            forwarder.receiveGameEvent(new GameEventTurnBegan(null, 2));
            forwarder.flushNow();
        });
        game.join();
        assertEquals(most.get(), 1, "one flush at a time");
        assertFalse(forwarder.hasPendingEvents());
    }
}
