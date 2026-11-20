package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** The auto-capture's steadiness, sharpness and light gate on synthetic analysis frames, with exactly known corners. */
class SharpnessGateTest {

    private static final int W = 640;
    private static final int H = 480;
    /** A portrait card 300 pixels tall in the middle of the frame, title at the top (c0-c1). */
    private static final CardQuad CARD = card(0, 0);
    private static final long FRAME_MS = 66;
    private static final byte[] SHARP = titledCard(CARD, 1);
    private static final byte[] BLURRED = boxBlurred(SHARP, 3);

    private static CardQuad card(double dx, double dy) {
        double halfH = 150;
        double halfW = halfH * 63 / 88;
        return new CardQuad(new Corner(320 - halfW + dx, 240 - halfH + dy), new Corner(320 + halfW + dx, 240 - halfH + dy),
                new Corner(320 + halfW + dx, 240 + halfH + dy), new Corner(320 - halfW + dx, 240 + halfH + dy), 1, true);
    }

    /**
     * A pale card on a dark table with a line of letter-like glyphs in its title band: each glyph a few of a
     * seven-segment cell's strokes, 1 or 2 pixels wide, as small print looks in a 640x480 frame.
     */
    private static byte[] titledCard(CardQuad card, long seed) {
        byte[] luma = new byte[W * H];
        Arrays.fill(luma, (byte) 50);
        int left = (int) card.c0().x();
        int top = (int) card.c0().y();
        int right = (int) card.c2().x();
        int bottom = (int) card.c2().y();
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                luma[y * W + x] = (byte) 190;
            }
        }
        Random random = new Random(seed);
        int baseline = top + 8;
        for (int x = left + 14; x + 8 < right - 14; x += 9) {
            int[][] segments = {{0, 0, 6, 0}, {0, 5, 6, 5}, {0, 10, 6, 10}, {0, 0, 0, 5}, {0, 5, 0, 10}, {6, 0, 6, 5}, {6, 5, 6, 10}};
            for (int[] s : segments) {
                if (random.nextInt(3) == 0) {
                    continue;
                }
                int thick = 1 + random.nextInt(2);
                for (int yy = s[1]; yy <= s[3] + (s[1] == s[3] ? thick - 1 : 0); yy++) {
                    for (int xx = s[0]; xx <= s[2] + (s[0] == s[2] ? thick - 1 : 0); xx++) {
                        luma[(baseline + yy) * W + x + xx] = (byte) 25;
                    }
                }
            }
        }
        return luma;
    }

    private static byte[] boxBlurred(byte[] in, int radius) {
        byte[] out = in.clone();
        for (int y = radius; y < H - radius; y++) {
            for (int x = radius; x < W - radius; x++) {
                int sum = 0;
                for (int a = -radius; a <= radius; a++) {
                    for (int b = -radius; b <= radius; b++) {
                        sum += in[(y + a) * W + x + b] & 0xFF;
                    }
                }
                out[y * W + x] = (byte) (sum / ((2 * radius + 1) * (2 * radius + 1)));
            }
        }
        return out;
    }

    private static byte[] darkened(byte[] in, int divisor) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (byte) ((in[i] & 0xFF) / divisor);
        }
        return out;
    }

    private static TrackedCard tracked(int id, CardQuad quad) {
        return new TrackedCard(id, quad, 0, 0, true);
    }

    /** Feeds the same frame and card from t = 0 until the given time, and returns the last decision. */
    private static SharpnessGate.Decision feedUntil(SharpnessGate gate, byte[] luma, long untilMs, Long exposureNs) {
        SharpnessGate.Decision last = null;
        for (long t = 0; t <= untilMs; t += FRAME_MS) {
            last = gate.update(tracked(1, CARD), luma, W, H, t, exposureNs);
        }
        return last;
    }

    @Test
    void measuresASharpTitleAboveTheThresholdAndABlurredOneBelowIt() {
        TitleSharpness.Reading sharp = TitleSharpness.measure(SHARP, W, H, CARD);
        TitleSharpness.Reading blurred = TitleSharpness.measure(BLURRED, W, H, CARD);
        assertTrue(sharp.measured());
        assertTrue(sharp.laplacianVariance() >= SharpnessGate.MIN_SHARPNESS, "sharp " + sharp);
        assertTrue(blurred.laplacianVariance() < SharpnessGate.MIN_SHARPNESS, "blurred " + blurred);
        assertTrue(sharp.meanLuma() > 100 && sharp.meanLuma() < 190, "mean " + sharp.meanLuma());
    }

    @Test
    void findsTheTitleWhicheverWayUpTheOutlineIs() {
        double right = TitleSharpness.measure(SHARP, W, H, CARD).laplacianVariance();
        assertEquals(right, TitleSharpness.measure(SHARP, W, H, CARD.flipped()).laplacianVariance(), 1e-9);
    }

    @Test
    void aBandOutsideTheFrameIsNotMeasured() {
        CardQuad offFrame = card(1000, 1000);
        assertFalse(TitleSharpness.measure(SHARP, W, H, offFrame).measured());
    }

    @Test
    void aSteadySharpCardIsReadyOnceWatchedForTheWindow() {
        SharpnessGate gate = new SharpnessGate();
        assertEquals(SharpnessGate.Verdict.HOLD_STILL, gate.update(tracked(1, CARD), SHARP, W, H, 0).verdict(), "only just seen");
        SharpnessGate.Decision ready = feedUntil(gate, SHARP, SharpnessGate.STEADY_WINDOW_MS + FRAME_MS, null);
        assertEquals(SharpnessGate.Verdict.READY, ready.verdict());
        assertFalse(ready.timedOut());
        assertEquals(0, ready.cornerSpread(), 1e-9);
    }

    @Test
    void jitteringCornersAskToHoldStill() {
        SharpnessGate gate = new SharpnessGate();
        SharpnessGate.Decision last = null;
        double shift = 0.03 * 300; // 3 % of the long side, beyond the allowed spread
        for (int i = 0; i * FRAME_MS < 2000; i++) {
            CardQuad quad = card(i % 2 == 0 ? 0 : shift, i % 3 == 0 ? 0 : -shift / 2);
            last = gate.update(tracked(1, quad), SHARP, W, H, i * FRAME_MS);
            assertNotEquals(SharpnessGate.Verdict.READY, last.verdict(), "frame " + i);
        }
        assertEquals(SharpnessGate.Verdict.HOLD_STILL, last.verdict());
        assertTrue(last.cornerSpread() > SharpnessGate.MAX_CORNER_SPREAD);
    }

    @Test
    void aSmallFitNoiseStillCountsAsSteady() {
        SharpnessGate gate = new SharpnessGate();
        SharpnessGate.Decision last = null;
        for (int i = 0; i * FRAME_MS < 600; i++) {
            last = gate.update(tracked(1, card(i % 2 == 0 ? 0 : 1.5, 0)), SHARP, W, H, i * FRAME_MS);
        }
        assertEquals(SharpnessGate.Verdict.READY, last.verdict());
    }

    @Test
    void aDarkFrameAsksForMoreLight() {
        byte[] dark = darkened(SHARP, 8);
        SharpnessGate.Decision decision = feedUntil(new SharpnessGate(), dark, 600, null);
        assertTrue(decision.reading().meanLuma() < SharpnessGate.MIN_MEAN_LUMA, "mean " + decision.reading().meanLuma());
        assertEquals(SharpnessGate.Verdict.MORE_LIGHT, decision.verdict());
    }

    @Test
    void aSteadyButBlurredTitleAsksForMoreLightInALongExposure() {
        assertEquals(SharpnessGate.Verdict.MORE_LIGHT, feedUntil(new SharpnessGate(), BLURRED, 600, 40_000_000L).verdict());
        assertEquals(SharpnessGate.Verdict.MORE_LIGHT, feedUntil(new SharpnessGate(), BLURRED, 600, null).verdict(), "exposure unknown");
    }

    @Test
    void aSteadyButBlurredTitleInAShortExposureAsksToHoldStill() {
        assertEquals(SharpnessGate.Verdict.HOLD_STILL, feedUntil(new SharpnessGate(), BLURRED, 600, 10_000_000L).verdict());
    }

    @Test
    void aBlurredCardIsCapturedAfterTheFallbackTimeAtItsBestFrame() {
        SharpnessGate gate = new SharpnessGate();
        byte[] blurrier = boxBlurred(SHARP, 5);
        long t = 0;
        for (; t < SharpnessGate.FALLBACK_AFTER_MS; t += FRAME_MS) {
            // The best frame seen comes early; then the card goes on looking worse.
            byte[] frame = t == 5 * FRAME_MS ? BLURRED : blurrier;
            assertNotEquals(SharpnessGate.Verdict.READY, gate.update(tracked(1, CARD), frame, W, H, t).verdict(), "at " + t);
        }
        assertNotEquals(SharpnessGate.Verdict.READY, gate.update(tracked(1, CARD), blurrier, W, H, t).verdict(),
                "far worse than the best frame seen");
        t += FRAME_MS;
        SharpnessGate.Decision best = gate.update(tracked(1, CARD), BLURRED, W, H, t);
        assertEquals(SharpnessGate.Verdict.READY, best.verdict());
        assertTrue(best.timedOut());
    }

    @Test
    void aCardThatNeverSettlesIsCapturedAtTheForceTime() {
        SharpnessGate gate = new SharpnessGate();
        SharpnessGate.Decision last = null;
        long t = 0;
        for (int i = 0; t < SharpnessGate.FORCE_AFTER_MS; i++, t = i * FRAME_MS) {
            last = gate.update(tracked(1, card(i % 2 == 0 ? 0 : 20, 0)), darkened(SHARP, 8), W, H, t);
            if (t < SharpnessGate.FALLBACK_AFTER_MS) {
                assertNotEquals(SharpnessGate.Verdict.READY, last.verdict(), "at " + t);
            }
        }
        last = gate.update(tracked(1, card(20, 0)), SHARP, W, H, t);
        assertEquals(SharpnessGate.Verdict.READY, last.verdict());
        assertTrue(last.timedOut());
    }

    @Test
    void aNewCardWaitsAfresh() {
        SharpnessGate gate = new SharpnessGate();
        feedUntil(gate, BLURRED, SharpnessGate.FALLBACK_AFTER_MS - FRAME_MS, null);
        long t = SharpnessGate.FALLBACK_AFTER_MS + FRAME_MS;
        assertEquals(SharpnessGate.Verdict.HOLD_STILL, gate.update(tracked(2, CARD), BLURRED, W, H, t).verdict());
        for (long until = t + SharpnessGate.FALLBACK_AFTER_MS - FRAME_MS; t < until; t += FRAME_MS) {
            assertNotEquals(SharpnessGate.Verdict.READY, gate.update(tracked(2, CARD), BLURRED, W, H, t).verdict(), "at " + t);
        }
    }

    @Test
    void noCardOrAGapIsNoCard() {
        SharpnessGate gate = new SharpnessGate();
        assertEquals(SharpnessGate.Verdict.NO_CARD, gate.update(null, SHARP, W, H, 0).verdict());
        feedUntil(gate, SHARP, 600, null);
        TrackedCard gap = new TrackedCard(1, CARD, 0, 0, false);
        assertEquals(SharpnessGate.Verdict.NO_CARD, gate.update(gap, SHARP, W, H, 700).verdict());
    }

    @Test
    void anotherOutlineOfTheSameCardRestartsTheSteadinessWindow() {
        SharpnessGate gate = new SharpnessGate();
        feedUntil(gate, SHARP, 600, null);
        TrackedCard reshaped = new TrackedCard(1, CARD, 0, 0, true, card(10, 0), 1);
        assertEquals(SharpnessGate.Verdict.HOLD_STILL, gate.update(reshaped, SHARP, W, H, 700).verdict());
    }

    /** The whole pipeline on one still card, focus granted at once, auto capture on and no gyroscope. */
    private static final class Session {
        final AssistedPipeline pipeline = new AssistedPipeline(null);
        long now;
        int generation;
        AssistedPipeline.Outcome last;

        AssistedPipeline.Outcome step(byte[] luma, Long exposureNs) {
            now += FRAME_MS;
            FocusStatus focus = generation == 0 ? FocusStatus.NONE : new FocusStatus(generation, FocusStatus.Outcome.FOCUSED, 50);
            last = pipeline.onFrame(new AssistedPipeline.Input(new FindResult(List.of(CARD), 0, 0, 0), luma, W, H, now, focus,
                    true, 0, false, null, exposureNs));
            if (last.focusAction() == AssistedPipeline.FocusAction.FOCUS_ON_CARD) {
                generation = last.focusGeneration();
            }
            return last;
        }

        /** Steps until a capture or the time, and returns when it captured, or -1. */
        long runUntil(byte[] luma, Long exposureNs, long untilMs) {
            while (now < untilMs) {
                if (step(luma, exposureNs).captureNow()) {
                    return now;
                }
            }
            return -1;
        }

        String status() {
            return StatusText.main(last.card() != null, last.latched(), false, true, last.sharpness().verdict());
        }
    }

    private static final Long EVENING_EXPOSURE_NS = 33_000_000L;

    @Test
    void aBlurredSteadyCardInDimLightIsNotCapturedAndAsksForMoreLight() {
        Session session = new Session();
        long captured = session.runUntil(BLURRED, EVENING_EXPOSURE_NS, SharpnessGate.FALLBACK_AFTER_MS - FRAME_MS);
        assertEquals(-1, captured, "captured at " + captured);
        assertNotNull(session.last.card());
        assertFalse(session.last.latched(), "held back without latching");
        assertEquals(SharpnessGate.Verdict.MORE_LIGHT, session.last.sharpness().verdict());
        assertEquals("More light", session.status());
        assertEquals(EVENING_EXPOSURE_NS, session.last.exposureNs());
    }

    @Test
    void aSharpSteadyCardIsCapturedPromptly() {
        Session session = new Session();
        long captured = session.runUntil(SHARP, EVENING_EXPOSURE_NS, 2000);
        assertTrue(captured > 0 && captured <= 800, "captured at " + captured);
        assertTrue(session.last.sharpness().ready());
        assertFalse(session.last.sharpness().timedOut());
        assertTrue(session.last.latched());
        Map<String, Object> recorded = session.last.sharpness().describe(session.last.exposureNs());
        assertEquals("READY", recorded.get("verdict"));
        assertEquals(EVENING_EXPOSURE_NS, recorded.get("exposureNs"));
        assertTrue((Double) recorded.get("laplacianVariance") >= SharpnessGate.MIN_SHARPNESS, recorded.toString());
        assertTrue(recorded.containsKey("meanLuma") && recorded.containsKey("cornerSpread"), recorded.toString());
    }

    @Test
    void aCardThatTurnsSharpIsCapturedOnceItDoes() {
        Session session = new Session();
        assertEquals(-1, session.runUntil(BLURRED, EVENING_EXPOSURE_NS, 1500));
        long captured = session.runUntil(SHARP, EVENING_EXPOSURE_NS, 2500);
        assertTrue(captured > 1500 && captured <= 1500 + 2 * FRAME_MS, "captured at " + captured);
    }

    @Test
    void untilTheWindowIsCoveredItSaysHoldStill() {
        Session session = new Session();
        AssistedPipeline.Outcome first = null;
        while (first == null || first.card() == null) {
            first = session.step(SHARP, EVENING_EXPOSURE_NS);
        }
        assertEquals(SharpnessGate.Verdict.HOLD_STILL, first.sharpness().verdict());
        assertEquals("Hold still", session.status());
    }

    @Test
    void aBlurredCardIsStillCapturedAfterTheFallback() {
        Session session = new Session();
        long captured = session.runUntil(BLURRED, EVENING_EXPOSURE_NS, SharpnessGate.FORCE_AFTER_MS + 1000);
        assertTrue(captured >= SharpnessGate.FALLBACK_AFTER_MS, "captured at " + captured);
        assertTrue(session.last.sharpness().timedOut());
    }

    @Test
    void theShutterIsNotHeldBackByABlurredTitle() {
        Session session = new Session();
        session.runUntil(BLURRED, EVENING_EXPOSURE_NS, 1000);
        session.pipeline.latch(session.last.signature());
        AssistedPipeline.Outcome after = session.step(BLURRED, EVENING_EXPOSURE_NS);
        assertTrue(after.latched(), "a shutter press marks the card captured whatever its sharpness");
        assertFalse(after.captureNow());
    }
}
