package forge.cardocr.assist;

import java.util.*;

/** Keeps finder timings for the run record, and the rate of frames processed for the screen. */
public final class FinderStats {

    /** Timings summary in milliseconds, over the latest samples. */
    public record Summary(int frames, double meanMs, double p50Ms, double p95Ms, double maxMs) {
    }

    private static final int MAX_SAMPLES = 512;
    private static final long RATE_WINDOW_MS = 2000;

    private final double[] samples = new double[MAX_SAMPLES];
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private int total;

    public void add(double finderMs, long nowMs) {
        samples[total % MAX_SAMPLES] = finderMs;
        total++;
        recent.addLast(nowMs);
        while (recent.peekFirst() <= nowMs - RATE_WINDOW_MS) {
            recent.removeFirst();
        }
    }

    public int frames() {
        return total;
    }

    /** Frames processed per second over the last two seconds. */
    public double framesPerSecond() {
        if (recent.size() < 2) {
            return 0;
        }
        long span = recent.peekLast() - recent.peekFirst();
        return span <= 0 ? 0 : (recent.size() - 1) * 1000.0 / span;
    }

    public Summary summary() {
        int count = Math.min(total, MAX_SAMPLES);
        if (count == 0) {
            return new Summary(0, 0, 0, 0, 0);
        }
        double[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        double sum = 0;
        for (double value : sorted) {
            sum += value;
        }
        return new Summary(total, sum / count, sorted[(count - 1) / 2], sorted[(int) Math.ceil(0.95 * count) - 1], sorted[count - 1]);
    }
}
