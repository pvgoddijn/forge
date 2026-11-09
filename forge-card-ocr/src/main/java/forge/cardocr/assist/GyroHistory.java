package forge.cardocr.assist;

import java.util.*;

/**
 * Recent gyroscope samples in device axes. Written on the sensor thread, read on the analyser and
 * main threads, so every method is synchronized. Each holds the lock only for a short array walk.
 */
public final class GyroHistory {

    /** Rotation in radians about each axis, summed over an interval. */
    public record Rotation(double x, double y, double z) {
    }

    private static final int CAPACITY = 1024;
    /** A longer silence between samples is a gap, not a slow change of rate. */
    private static final long MAX_SAMPLE_GAP_NS = 200_000_000L;
    /** The last rate is assumed to hold for this long, since a frame can be newer than the last sample. */
    private static final long MAX_HOLD_NS = 50_000_000L;

    private final long[] times = new long[CAPACITY];
    private final double[] xs = new double[CAPACITY];
    private final double[] ys = new double[CAPACITY];
    private final double[] zs = new double[CAPACITY];
    private int total;

    /** Add samples in time order. An older sample than the newest is ignored. */
    public synchronized void add(long timeNs, double x, double y, double z) {
        if (total > 0 && timeNs <= times[index(total - 1)]) {
            return;
        }
        int i = index(total);
        times[i] = timeNs;
        xs[i] = x;
        ys[i] = y;
        zs[i] = z;
        total++;
    }

    private static int index(int sample) {
        return sample % CAPACITY;
    }

    private int oldest() {
        return Math.max(0, total - CAPACITY);
    }

    /** The newest sample's time, or -1 with none yet. */
    public synchronized long latestNs() {
        return total == 0 ? -1 : times[index(total - 1)];
    }

    /**
     * The rotation over an interval, with the rate varying linearly between samples.
     *
     * @return null if the samples don't cover the interval, so a caller can't mistake it for no motion
     */
    public synchronized Rotation integrate(long fromNs, long toNs) {
        if (total == 0 || toNs <= fromNs || fromNs < times[index(oldest())]
                || toNs > times[index(total - 1)] + MAX_SAMPLE_GAP_NS) {
            return null;
        }
        double sumX = 0;
        double sumY = 0;
        double sumZ = 0;
        for (int s = oldest(); s < total - 1; s++) {
            int a = index(s);
            int b = index(s + 1);
            long start = Math.max(fromNs, times[a]);
            long end = Math.min(toNs, times[b]);
            long span = times[b] - times[a];
            if (end <= start || span > MAX_SAMPLE_GAP_NS) {
                continue;
            }
            double seconds = (end - start) / 1e9;
            double middle = ((start + end) / 2.0 - times[a]) / span;
            sumX += seconds * (xs[a] + middle * (xs[b] - xs[a]));
            sumY += seconds * (ys[a] + middle * (ys[b] - ys[a]));
            sumZ += seconds * (zs[a] + middle * (zs[b] - zs[a]));
        }
        int last = index(total - 1);
        long held = Math.min(toNs - Math.max(fromNs, times[last]), MAX_HOLD_NS);
        if (held > 0) {
            sumX += held / 1e9 * xs[last];
            sumY += held / 1e9 * ys[last];
            sumZ += held / 1e9 * zs[last];
        }
        return new Rotation(sumX, sumY, sumZ);
    }

    /** Mean angular speed in rad/s over the window ending at {@code endNs}. NaN with no samples there. */
    public synchronized double meanSpeed(long endNs, long windowNs) {
        double sum = 0;
        int count = 0;
        for (int s = total - 1; s >= oldest(); s--) {
            int i = index(s);
            if (times[i] <= endNs - windowNs) {
                break;
            }
            if (times[i] <= endNs) {
                sum += speed(i);
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    /** Highest angular speed in rad/s over the window ending at {@code endNs}. NaN with no samples there. */
    public synchronized double peakSpeed(long endNs, long windowNs) {
        double peak = Double.NaN;
        for (int s = total - 1; s >= oldest(); s--) {
            int i = index(s);
            if (times[i] <= endNs - windowNs) {
                break;
            }
            if (times[i] <= endNs && !(peak >= speed(i))) {
                peak = speed(i);
            }
        }
        return peak;
    }

    private double speed(int i) {
        return Math.sqrt(xs[i] * xs[i] + ys[i] * ys[i] + zs[i] * zs[i]);
    }
}
