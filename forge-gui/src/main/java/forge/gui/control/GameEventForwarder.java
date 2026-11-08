package forge.gui.control;

import com.google.common.eventbus.Subscribe;
import forge.game.event.GameEvent;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventTurnEnded;
import forge.gui.interfaces.IGuiGame;

import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

/**
 * Buffers game events and flushes them to the GUI in batches.
 *
 * <p>Flush triggers (all on the game thread):
 * <ul>
 *   <li>Priority: a player receives it once the game has settled, so a batch holds whole actions
 *       and what one player did is sent before anyone chooses what to do next. A pass with nothing
 *       but redraw hints since the last batch sends nothing</li>
 *   <li>End of turn: normally no player receives priority in the cleanup step, so what happened in
 *       it is sent before the turn ends and never travels with the next turn's start</li>
 *   <li>Input queue change: registered as {@link Observer} on player InputQueues,
 *       ensuring events are delivered before the game thread blocks for input</li>
 *   <li>Sync points: explicit {@link #flush()} from {@code flushPendingEvents()}</li>
 * </ul>
 *
 * <p>No daemon thread — all delta collection runs on the game thread to avoid race issues. The game thread is the
 * one that runs the game loop: given to the constructor by a forwarder attached mid-game, otherwise the thread that
 * delivers {@code GameEventGameStarted} (fired before the mulligans), or failing that the first priority or end of
 * turn, which only the game loop fires. Events fired elsewhere (the EDT when a combat input changes, a net play
 * answer on a "Game BT" thread) don't change it. A flush asked for on any other thread (an input ended by a tap on
 * the render thread, a Netty thread, a net play answer) only marks a flush as due, and the game thread does it at its
 * next flush point: the next priority (even after nothing but redraw hints), input or end of turn. Ending an input is
 * what wakes the game thread, and the game thread flushes before it blocks for the next one, so a due flush always
 * meets a flush point. While the game thread is blocked in a plain dialog that doesn't end an input (a
 * {@code sendAndWait} confirm), a due flush waits for the answer. Until the game thread is known (before the game
 * starts) a flush goes out where it's asked for, as it always did.
 *
 * <p>{@link #flushNow()} sends at once from any thread: when the game is over or the forwarder is let go, and for net
 * play's reconnect, which must send the view before {@code openView}. All flushes, and every change to the batch,
 * hold this forwarder's lock, so two threads never encode at the same time and a batch is never split or sent twice.
 */
public class GameEventForwarder implements Observer {
    private final IGuiGame gui;
    private final List<GameEvent> pendingEvents = new ArrayList<>();
    /** Whether anything but a redraw hint is waiting, which is what makes a pass of priority worth a batch. */
    private boolean somethingHappened;
    /** A flush asked for off the game thread, for the game thread's next flush point. */
    private volatile boolean flushDue;
    /** The thread that runs the game loop, once known; set once. */
    private volatile Thread gameThread;

    public GameEventForwarder(IGuiGame gui) {
        this(gui, null);
    }

    /** For a forwarder attached to a game already running: {@code gameThread} is the thread that runs its loop. */
    public GameEventForwarder(IGuiGame gui, Thread gameThread) {
        this.gui = gui;
        this.gameThread = gameThread;
    }

    @Subscribe
    public synchronized void receiveGameEvent(GameEvent ev) {
        if (gameThread == null && (ev instanceof GameEventGameStarted || ev instanceof GameEventPlayerPriority
                || ev instanceof GameEventTurnEnded)) {
            gameThread = Thread.currentThread();
        }
        if (ev instanceof GameEventTurnEnded && (somethingHappened || flushDue)) {
            flush();
        }
        pendingEvents.add(ev);
        if (!(ev instanceof GameEventPlayerPriority)) {
            somethingHappened |= !ev.isRedrawHint();
        } else if (somethingHappened || flushDue) {
            flush();
        }
    }

    /** Sends what is waiting, on the game thread; on any other thread, marks a flush as due for the game thread. */
    public void flush() {
        if (!onGameThread()) {
            flushDue = true;
            return;
        }
        flushNow();
    }

    /**
     * Sends what is waiting, on whatever thread this is. Only for when the game thread can't be waited for: the game
     * is over, this forwarder is being let go, or a reconnect must have the view before {@code openView}.
     */
    public synchronized void flushNow() {
        flushDue = false;
        if (pendingEvents.isEmpty()) {
            return;
        }
        List<GameEvent> batch = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        somethingHappened = false;
        gui.handleGameEvents(batch);
    }

    public synchronized boolean hasPendingEvents() {
        return !pendingEvents.isEmpty();
    }

    /** Whether a flush asked for off the game thread waits for the game thread's next flush point. */
    public boolean isFlushDue() {
        return flushDue;
    }

    /** The thread that runs the game loop, or null before it's known. */
    public Thread getGameThread() {
        return gameThread;
    }

    private boolean onGameThread() {
        Thread known = gameThread;
        return known == null || known == Thread.currentThread();
    }

    /**
     * Called when an InputQueue changes (setInput/removeInput/clearInputs).
     * Flushes any pending events on the game thread before it blocks for input; off it, marks a flush as due.
     */
    @Override
    public void update(Observable o, Object arg) {
        flush();
    }

}
