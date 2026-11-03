package forge.cardocr.assist;

import java.util.*;

/**
 * Hands the newest frame from a producer to one slow consumer, dropping any it didn't get to.
 * Frame objects are recycled so a stream doesn't allocate a buffer per frame.
 */
public final class LatestFrameSlot<T> {

    private final ArrayDeque<T> spare = new ArrayDeque<>();
    private T latest;
    private boolean closed;
    private long dropped;

    /** A recycled frame to fill, or null if the producer should make a new one. */
    public synchronized T acquire() {
        return spare.poll();
    }

    /** Publishes a frame, replacing and dropping any the consumer hasn't taken. Ignored once closed. */
    public synchronized void publish(T frame) {
        if (closed) {
            return;
        }
        if (latest != null) {
            spare.add(latest);
            dropped++;
        }
        latest = frame;
        notifyAll();
    }

    /** Waits for a frame. Returns null once closed or if the thread is interrupted. */
    public synchronized T take() {
        while (latest == null && !closed) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        if (closed) {
            return null;
        }
        T frame = latest;
        latest = null;
        return frame;
    }

    /** Returns a taken frame for reuse. */
    public synchronized void release(T frame) {
        if (!closed) {
            spare.add(frame);
        }
    }

    /** Wakes the consumer and lets go of all frames. */
    public synchronized void close() {
        closed = true;
        latest = null;
        spare.clear();
        notifyAll();
    }

    public synchronized long dropped() {
        return dropped;
    }
}
