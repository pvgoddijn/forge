package forge.cardocr.assist;

import java.util.*;

/**
 * How long the phone has been steady according to the gyroscope. Steady means the mean speed over a
 * short window is within the blur limit, so a hand hovering near the limit doesn't keep restarting
 * the clock. The clock starts from the window's oldest sample: what the window covered counts.
 */
public final class PhoneSteadiness {

    static final long ROLLING_MS = 300;
    /** One reading this far over the limit is a real move, and restarts at once. At 0.4 rad/s and 1/60 s that's 18 pixels. */
    static final double BUMP_FACTOR = 3;

    private final ArrayDeque<double[]> samples = new ArrayDeque<>();
    private long steadySinceMs = -1;

    public void update(double speedRadS, long nowMs) {
        update(speedRadS, BlurBudget.limitRadS(null), nowMs);
    }

    public void update(double speedRadS, double limitRadS, long nowMs) {
        if (speedRadS > limitRadS * BUMP_FACTOR) {
            reset();
            return;
        }
        samples.addLast(new double[] {nowMs, speedRadS});
        while (nowMs - samples.peekFirst()[0] > ROLLING_MS) {
            samples.removeFirst();
        }
        double mean = samples.stream().mapToDouble(s -> s[1]).average().orElse(0);
        if (mean > limitRadS) {
            steadySinceMs = -1;
        } else if (steadySinceMs < 0) {
            steadySinceMs = (long) samples.peekFirst()[0];
        }
    }

    public void reset() {
        samples.clear();
        steadySinceMs = -1;
    }

    public long steadyForMs(long nowMs) {
        return steadySinceMs < 0 ? 0 : nowMs - steadySinceMs;
    }
}
