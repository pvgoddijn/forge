package forge.cardocr.assist;

import java.util.*;

/**
 * Holds an automatic capture back until the tracked card's title is sharp and bright enough in the analysis frame, and
 * says when more light would help. It sits beside {@link CaptureGate}, which judges focus, the gyroscope (so phone
 * movement) and recapturing the same card: a capture should start only when both agree. Phone misses were mostly titles blurred in
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
        /**
         * Not ready yet, with nothing to ask of the operator: the title band couldn't be measured, or it is blurred
         * in a short exposure. The owner dropped a "hold still" prompt for this (2026-11-21): {@link CaptureGate}'s
         * gyroscope and focus check already holds a capture back while the phone moves.
         */
        WAITING,
        /** The title is too dark, or too blurred in a long or unknown exposure, as in dim light. */
        MORE_LIGHT,
        READY
    }

    /**
     * One frame's decision.
     *
     * @param timedOut READY only because the card has waited too long, not because the frame passed
     * @param reading the title band's measures, or null when not measured
     */
    public record Decision(Verdict verdict, boolean timedOut, TitleSharpness.Reading reading) {

        public boolean ready() {
            return verdict == Verdict.READY;
        }

        /**
         * The decision as names and values for a training capture's record, to calibrate the thresholds from phone
         * captures. Measures not taken are left out.
         *
         * @param exposureNs the camera's exposure on the frame, or null if not known
         */
        public Map<String, Object> describe(Long exposureNs) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("verdict", verdict.name());
            out.put("timedOut", timedOut);
            if (reading != null && reading.measured()) {
                out.put("laplacianVariance", reading.laplacianVariance());
                out.put("meanLuma", reading.meanLuma());
            }
            if (exposureNs != null) {
                out.put("exposureNs", exposureNs);
            }
            return out;
        }
    }

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
     * 10 ms, read right. A blurred title at a shorter exposure isn't put down to light, so it just waits.
     */
    static final long LONG_EXPOSURE_NS = 25_000_000;
    /** Guess: after this long with one card, a frame close to the best seen is good enough. */
    static final long FALLBACK_AFTER_MS = 3000;
    /** In the fallback, a frame this close to the sharpest seen for the card counts as its best. */
    static final double FALLBACK_BEST_SHARE = 0.85;
    /** Guess: after this long with one card, any frame showing it is captured. */
    static final long FORCE_AFTER_MS = 5000;

    private int cardId = -1;
    private long cardSinceMs = -1;
    private double bestSharpness;

    /** Forgets the card, so the next one waits afresh. Call after every capture. */
    public void reset() {
        cardId = -1;
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
            return new Decision(Verdict.NO_CARD, false, null);
        }
        if (card.id() != cardId) {
            reset();
            cardId = card.id();
            cardSinceMs = nowMs;
        }
        if (!card.seenNow() || card.seenQuad() == null) {
            return new Decision(Verdict.NO_CARD, false, null);
        }
        TitleSharpness.Reading reading = TitleSharpness.measure(luma, width, height, card.seenQuad());
        if (reading.measured()) {
            bestSharpness = Math.max(bestSharpness, reading.laplacianVariance());
        }

        long waited = nowMs - cardSinceMs;
        if (waited >= FORCE_AFTER_MS
                || waited >= FALLBACK_AFTER_MS && reading.measured() && reading.laplacianVariance() >= FALLBACK_BEST_SHARE * bestSharpness) {
            return new Decision(Verdict.READY, verdict(reading, exposureNs) != Verdict.READY, reading);
        }
        return new Decision(verdict(reading, exposureNs), false, reading);
    }

    private static Verdict verdict(TitleSharpness.Reading reading, Long exposureNs) {
        if (!reading.measured()) {
            return Verdict.WAITING;
        }
        if (reading.meanLuma() < MIN_MEAN_LUMA) {
            return Verdict.MORE_LIGHT;
        }
        if (reading.laplacianVariance() < MIN_SHARPNESS) {
            return exposureNs != null && exposureNs < LONG_EXPOSURE_NS ? Verdict.WAITING : Verdict.MORE_LIGHT;
        }
        return Verdict.READY;
    }
}
