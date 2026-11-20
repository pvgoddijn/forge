package forge.cardocr.assist;

import forge.cardocr.finder.*;
import java.util.*;

/**
 * Holds an automatic capture back until the tracked card is steady and its title sharp and bright enough in the
 * analysis frame, and says why it's waiting. It sits beside {@link CaptureGate}, which judges focus, the gyroscope and
 * recapturing the same card: a capture should start only when both agree. Phone misses were mostly titles blurred in
 * evening light, at 30 to 40 ms exposures (docs/companion slice 8, AC-32).
 *
 * <p>So the operator is never stuck, after {@link #FALLBACK_AFTER_MS} with the same card it is ready on any frame
 * nearly as sharp as the sharpest seen so far, and after {@link #FORCE_AFTER_MS} on any frame showing the card.
 *
 * <p>Feed every analysis frame from one thread, and {@link #reset()} after each capture.
 */
public final class SharpnessGate {

    public enum Verdict {
        /** No card tracked, or the tracker is bridging a gap and this frame may not show it. */
        NO_CARD,
        /** The outline is moving, or hasn't been watched long enough to tell. */
        HOLD_STILL,
        /** Steady, but the title is too dark or too blurred, as long exposures in dim light blur it. */
        MORE_LIGHT,
        READY
    }

    /**
     * One frame's decision.
     *
     * @param timedOut READY only because the card has waited too long, not because the frame passed
     * @param reading the title band's measures, or null when not measured
     * @param cornerSpread how far the outline's corners wandered over the steadiness window, as a share of its long
     *     side, or NaN before the window is covered
     */
    public record Decision(Verdict verdict, boolean timedOut, TitleSharpness.Reading reading, double cornerSpread) {

        public boolean ready() {
            return verdict == Verdict.READY;
        }
    }

    /** How long the outline must have been watched, and kept still, before a capture. */
    static final long STEADY_WINDOW_MS = 300;
    /**
     * Guess: the farthest any corner may wander within the window, as a share of the long side. Above the tracker's
     * fit noise ({@link CardTracker#NOISE_SHARE}, 0.6 %), below what it calls a real move (2.5 %).
     */
    static final double MAX_CORNER_SPREAD = 0.02;
    /**
     * Laplacian variance of the title band in a 640x480 analysis frame. Calibrated on the 55 phone captures with an
     * outline (photo downscaled to the analysis size, measured at the tracked outline): every one of the 16 misses was
     * at or below 379, every one of the 33 reads above it right; 300 holds back 13 of the misses and 4 of 39 good
     * reads. Not yet measured on real analysis frames, whose noise and processing differ from a downscaled photo.
     */
    static final double MIN_SHARPNESS = 300;
    /** Guess: darker than this the title can't be read well. Two good reads were at 45, none of the misses below 73. */
    static final double MIN_MEAN_LUMA = 35;
    /**
     * Exposures at least this long mean a dim room. All 16 misses were at 30 or 40 ms and every daylight capture, at
     * 10 ms, read right. A blurred title at a shorter exposure is put down to movement instead.
     */
    static final long LONG_EXPOSURE_NS = 25_000_000;
    /** Guess: after this long with one card, a frame close to the best seen is good enough. */
    static final long FALLBACK_AFTER_MS = 3000;
    /** In the fallback, a frame this close to the sharpest seen for the card counts as its best. */
    static final double FALLBACK_BEST_SHARE = 0.85;
    /** Guess: after this long with one card, any frame showing it is captured. */
    static final long FORCE_AFTER_MS = 5000;

    private record Sample(long ms, Corner[] corners) {
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private int cardId = -1;
    private int outlineRevision = -1;
    private long cardSinceMs = -1;
    private double bestSharpness;

    /** Forgets the card, so the next one waits afresh. Call after every capture. */
    public void reset() {
        samples.clear();
        cardId = -1;
        outlineRevision = -1;
        cardSinceMs = -1;
        bestSharpness = 0;
    }

    /** As the full update, with the exposure not known. */
    public Decision update(TrackedCard card, byte[] luma, int width, int height, long nowMs) {
        return update(card, luma, width, height, nowMs, null);
    }

    /**
     * @param card the tracked card, or null
     * @param luma the analysis frame the card was tracked in
     * @param exposureNs the camera's latest exposure, or null if not known
     */
    public Decision update(TrackedCard card, byte[] luma, int width, int height, long nowMs, Long exposureNs) {
        if (card == null) {
            reset();
            return new Decision(Verdict.NO_CARD, false, null, Double.NaN);
        }
        if (card.id() != cardId) {
            reset();
            cardId = card.id();
            cardSinceMs = nowMs;
        }
        if (!card.seenNow() || card.seenQuad() == null) {
            return new Decision(Verdict.NO_CARD, false, null, Double.NaN);
        }
        if (card.outlineRevision() != outlineRevision) {
            // Another outline of the same card jumps the corners without the card moving.
            samples.clear();
            outlineRevision = card.outlineRevision();
        }
        CardQuad quad = card.seenQuad();
        double spread = cornerSpread(quad, nowMs);
        TitleSharpness.Reading reading = TitleSharpness.measure(luma, width, height, quad);
        if (reading.measured()) {
            bestSharpness = Math.max(bestSharpness, reading.laplacianVariance());
        }

        long waited = nowMs - cardSinceMs;
        if (waited >= FORCE_AFTER_MS
                || waited >= FALLBACK_AFTER_MS && reading.measured() && reading.laplacianVariance() >= FALLBACK_BEST_SHARE * bestSharpness) {
            return new Decision(Verdict.READY, !passes(spread, reading), reading, spread);
        }
        return new Decision(verdict(spread, reading, exposureNs), false, reading, spread);
    }

    private static boolean passes(double spread, TitleSharpness.Reading reading) {
        return steady(spread) && reading.measured() && reading.meanLuma() >= MIN_MEAN_LUMA
                && reading.laplacianVariance() >= MIN_SHARPNESS;
    }

    private static boolean steady(double spread) {
        return !Double.isNaN(spread) && spread <= MAX_CORNER_SPREAD;
    }

    private static Verdict verdict(double spread, TitleSharpness.Reading reading, Long exposureNs) {
        if (!steady(spread) || !reading.measured()) {
            return Verdict.HOLD_STILL;
        }
        if (reading.meanLuma() < MIN_MEAN_LUMA) {
            return Verdict.MORE_LIGHT;
        }
        if (reading.laplacianVariance() < MIN_SHARPNESS) {
            return exposureNs != null && exposureNs < LONG_EXPOSURE_NS ? Verdict.HOLD_STILL : Verdict.MORE_LIGHT;
        }
        return Verdict.READY;
    }

    /**
     * Adds this frame's corners and returns the farthest any corner has wandered since the start of the window, as a
     * share of the long side, or NaN while the samples don't yet cover the window.
     */
    private double cornerSpread(CardQuad quad, long nowMs) {
        samples.addLast(new Sample(nowMs, quad.corners().toArray(new Corner[0])));
        // Keep the newest sample at or before the window's start, so coverage can be told.
        while (samples.size() > 1 && nowMs - secondOldest().ms() >= STEADY_WINDOW_MS) {
            samples.removeFirst();
        }
        if (nowMs - samples.peekFirst().ms() < STEADY_WINDOW_MS) {
            return Double.NaN;
        }
        Corner[] now = samples.peekLast().corners();
        double longSide = Math.max(Math.max(now[0].distanceTo(now[3]), now[1].distanceTo(now[2])), 1);
        double worst = 0;
        Sample[] all = samples.toArray(new Sample[0]);
        for (int a = 0; a < all.length; a++) {
            for (int b = a + 1; b < all.length; b++) {
                for (int k = 0; k < 4; k++) {
                    worst = Math.max(worst, all[a].corners()[k].distanceTo(all[b].corners()[k]));
                }
            }
        }
        return worst / longSide;
    }

    private Sample secondOldest() {
        Iterator<Sample> it = samples.iterator();
        it.next();
        return it.next();
    }
}
