package forge.gui.control;

import com.google.common.eventbus.Subscribe;
import forge.game.event.GameEvent;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventTurnEnded;
import forge.gui.interfaces.IGuiGame;
import forge.util.ThreadUtil;

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
 * <p>No daemon thread — all delta collection runs on the game thread to avoid race issues. A flush
 * asked for on any other thread (an input ended by a tap on the render thread, a Netty thread) only
 * marks a flush as due, and the game thread does it at its next flush point: the next priority
 * (even after nothing but redraw hints), input or end of turn. Ending an input is what wakes the
 * game thread, and the game thread flushes before it blocks for the next one, so a due flush always
 * meets a flush point. Once the game is over, {@link #flushNow()} flushes from any thread.
 */
public class GameEventForwarder implements Observer {
    private final IGuiGame gui;
    private final List<GameEvent> pendingEvents = new ArrayList<>();
    /** Whether anything but a redraw hint is waiting, which is what makes a pass of priority worth a batch. */
    private boolean somethingHappened;
    /** A flush asked for off the game thread, for the game thread's next flush point. */
    private volatile boolean flushDue;
    /** The thread that delivers the game's events: the game thread, also when it isn't one of {@link ThreadUtil}'s. */
    private volatile Thread eventThread;

    public GameEventForwarder(IGuiGame gui) {
        this.gui = gui;
    }

    @Subscribe
    public void receiveGameEvent(GameEvent ev) {
        eventThread = Thread.currentThread();
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
     * Sends what is waiting, on whatever thread this is. Only for when nothing changes the view any more: the game is
     * over, or this forwarder is being let go.
     */
    public void flushNow() {
        flushDue = false;
        if (pendingEvents.isEmpty()) {
            return;
        }
        List<GameEvent> batch = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        somethingHappened = false;
        gui.handleGameEvents(batch);
    }

    public boolean hasPendingEvents() {
        return !pendingEvents.isEmpty();
    }

    /** Whether a flush asked for off the game thread waits for the game thread's next flush point. */
    public boolean isFlushDue() {
        return flushDue;
    }

    private boolean onGameThread() {
        Thread current = Thread.currentThread();
        return current == eventThread || ThreadUtil.isGameThread();
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
