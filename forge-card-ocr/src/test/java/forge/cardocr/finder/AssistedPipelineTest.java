package forge.cardocr.finder;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.assist.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Replays whole sessions through the real finder and pipeline, on drawn frames at preview size. */
class AssistedPipelineTest {

    private static final int W = 640;
    private static final int H = 480;
    private static final long FRAME_MS = 100;
    private static final long STEADY_MS = 800;
    private static final CardQuad CARD = SyntheticCard.corners(320, 240, 300, 12, 0);
    private static final FocusStatus NOT_ASKED = FocusStatus.NONE;

    private final CardFinder finder = new CardFinder();
    private AssistedPipeline pipeline = new AssistedPipeline(null);
    private long now = 10_000;
    private int focusGeneration;
    private boolean inFlight;
    private boolean auto = true;

    private static byte[] frameWith(CardQuad card, int face, int art) {
        byte[] luma = SyntheticCard.blank(W, H, 200);
        if (card != null) {
            SyntheticCard.draw(luma, W, H, card, 25, face, art);
        }
        return luma;
    }

    /** Runs one frame with focus that settles at once, as a device would be told to do. */
    private AssistedPipeline.Outcome step(byte[] luma) {
        now += FRAME_MS;
        FocusStatus focus = focusGeneration == 0 ? NOT_ASKED
                : new FocusStatus(focusGeneration, FocusStatus.Outcome.FOCUSED, 50);
        AssistedPipeline.Outcome out = pipeline.onFrame(new AssistedPipeline.Input(finder.find(luma, W, H), luma, W, H,
                now, focus, auto, STEADY_MS, inFlight));
        if (out.focusAction() == AssistedPipeline.FocusAction.FOCUS_ON_CARD) {
            focusGeneration = out.focusGeneration();
        }
        return out;
    }

    /** Frames that fit well inside the time limit on recapturing the same card. */
    private static final int WITHIN_BLOCK = (int) (CaptureGate.HOLD_MAX_MS / FRAME_MS) - 2;

    private int framesUntilCapture(byte[] luma, int limit) {
        for (int i = 1; i <= limit; i++) {
            if (step(luma).captureNow()) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void outlinesTheCardAfterSeveralFramesNotOnTheFirst() {
        byte[] luma = frameWith(CARD, 150, 80);
        assertNull(step(luma).card());
        AssistedPipeline.Outcome out = null;
        for (int i = 0; i < 4; i++) {
            out = step(luma);
        }
        assertNotNull(out.card());
        assertTrue(SyntheticCard.cornerError(out.card().quad(), CARD) < 2);
    }

    @Test
    void autoCapturesOnceWhenSteadyAndFocused() {
        byte[] luma = frameWith(CARD, 150, 80);
        int frames = framesUntilCapture(luma, 30);
        assertTrue(frames > 0, "never captured");
        assertTrue(frames * FRAME_MS >= STEADY_MS, "too early: " + frames);
        assertEquals(-1, framesUntilCapture(luma, WITHIN_BLOCK), "captured the same card twice straight away");
        assertTrue(framesUntilCapture(luma, 30) > 0, "the block has a time limit");
    }

    @Test
    void focusIsRequestedOnceForAStillCard() {
        byte[] luma = frameWith(CARD, 150, 80);
        int requests = 0;
        for (int i = 0; i < 30; i++) {
            if (step(luma).focusAction() == AssistedPipeline.FocusAction.FOCUS_ON_CARD) {
                requests++;
            }
        }
        assertEquals(1, requests);
    }

    @Test
    void autoCaptureWaitsForFocusToSettle() {
        byte[] luma = frameWith(CARD, 150, 80);
        for (int i = 0; i < 30; i++) {
            now += FRAME_MS;
            AssistedPipeline.Outcome out = pipeline.onFrame(new AssistedPipeline.Input(finder.find(luma, W, H), luma, W, H,
                    now, new FocusStatus(1, FocusStatus.Outcome.PENDING, -1), true, STEADY_MS, false));
            assertFalse(out.captureNow());
        }
    }

    @Test
    void aSettledOlderFocusRequestDoesNotCount() {
        byte[] luma = frameWith(CARD, 150, 80);
        for (int i = 0; i < 30; i++) {
            now += FRAME_MS;
            AssistedPipeline.Outcome out = pipeline.onFrame(new AssistedPipeline.Input(finder.find(luma, W, H), luma, W, H,
                    now, new FocusStatus(0, FocusStatus.Outcome.FOCUSED, 10), true, STEADY_MS, false));
            assertFalse(out.captureNow());
        }
    }

    @Test
    void toggleDisablesAutoCapture() {
        auto = false;
        assertEquals(-1, framesUntilCapture(frameWith(CARD, 150, 80), 30));
        auto = true;
        assertTrue(framesUntilCapture(frameWith(CARD, 150, 80), 3) > 0, "should fire once enabled");
    }

    @Test
    void reArmsAfterTheCardLeaves() {
        byte[] luma = frameWith(CARD, 150, 80);
        assertTrue(framesUntilCapture(luma, 30) > 0);
        byte[] empty = frameWith(null, 0, 0);
        assertEquals(-1, framesUntilCapture(empty, 20));
        assertTrue(framesUntilCapture(luma, 30) > 0);
    }

    @Test
    void reArmsWhenADifferentCardReplacesItInPlace() {
        assertTrue(framesUntilCapture(frameWith(CARD, 150, 80), 30) > 0);
        assertTrue(framesUntilCapture(frameWith(CARD, 80, 170), 30) > 0);
    }

    @Test
    void movingTheCardWellAwayLetsItCaptureAgain() {
        assertTrue(framesUntilCapture(frameWith(CARD, 150, 80), 30) > 0);
        assertTrue(framesUntilCapture(frameWith(SyntheticCard.corners(340, 250, 290, 12, 0), 150, 80), 30) > 0);
    }

    /** The result screen pauses the camera, and the same card must not fire again at once on return. */
    @Test
    void capturedCardSurvivesARestart() {
        byte[] luma = frameWith(CARD, 150, 80);
        float[] signature = null;
        for (int i = 0; i < 30 && signature == null; i++) {
            AssistedPipeline.Outcome out = step(luma);
            if (out.captureNow()) {
                signature = out.signature();
            }
        }
        assertNotNull(signature);
        pipeline = new AssistedPipeline(signature);
        focusGeneration = 0;
        assertEquals(-1, framesUntilCapture(luma, WITHIN_BLOCK));
    }

    @Test
    void noCaptureOrRefocusWhileACaptureIsInFlight() {
        inFlight = true;
        byte[] luma = frameWith(CARD, 150, 80);
        for (int i = 0; i < 30; i++) {
            AssistedPipeline.Outcome out = step(luma);
            assertFalse(out.captureNow());
            assertEquals(AssistedPipeline.FocusAction.NONE, out.focusAction());
        }
    }

    @Test
    void shutterLatchStopsAutoAndFailedCaptureReleasesIt() {
        byte[] luma = frameWith(CARD, 150, 80);
        AssistedPipeline.Outcome out = null;
        for (int i = 0; i < 5; i++) {
            out = step(luma);
        }
        pipeline.latch(out.signature());
        assertEquals(-1, framesUntilCapture(luma, WITHIN_BLOCK));
        pipeline.releaseLatch();
        assertTrue(framesUntilCapture(luma, 20) > 0);
    }

    @Test
    void focusIsReleasedAfterTheCardHasBeenGoneAWhile() {
        byte[] luma = frameWith(CARD, 150, 80);
        for (int i = 0; i < 6; i++) {
            step(luma);
        }
        byte[] empty = frameWith(null, 0, 0);
        int releases = 0;
        for (int i = 0; i < 30; i++) {
            if (step(empty).focusAction() == AssistedPipeline.FocusAction.RELEASE) {
                releases++;
            }
        }
        assertEquals(1, releases);
    }

    @Test
    void glareOnTheTitleWarnsAndIsRemembered() {
        byte[] luma = frameWith(CARD, 150, 80);
        Homography h = Homography.fromUnitSquare(CARD);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                Corner uv = h.unmap(x + 0.5, y + 0.5);
                if (uv.x() > 0.3 && uv.x() < 0.7 && uv.y() > 0.04 && uv.y() < 0.11) {
                    luma[y * W + x] = (byte) 255;
                }
            }
        }
        boolean warned = false;
        for (int i = 0; i < 8; i++) {
            warned |= step(luma).glareNow();
        }
        assertTrue(warned);
        AssistedPipeline.Outcome clean = null;
        for (int i = 0; i < 3; i++) {
            clean = step(frameWith(CARD, 150, 80));
        }
        assertFalse(clean.glareNow());
        assertTrue(clean.glareWarnedForCard());
    }

    @Test
    void reportsTimings() {
        byte[] luma = frameWith(CARD, 150, 80);
        AssistedPipeline.Outcome out = null;
        for (int i = 0; i < 5; i++) {
            out = step(luma);
        }
        assertEquals(5, out.timings().frames());
        assertEquals(10, out.framesPerSecond(), 0.01);
        assertTrue(out.finderMs() > 0);
    }

    /** A sideways card with a neighbour: the tracker stays on one, and its outline is exact. */
    @Test
    void keepsOneSidewaysCardWithANeighbourInView() {
        CardQuad main = SyntheticCard.corners(250, 240, 240, 90, 0);
        CardQuad neighbour = SyntheticCard.corners(520, 240, 240, 90, 0);
        byte[] luma = SyntheticCard.blank(W, H, 200);
        SyntheticCard.draw(luma, W, H, main, 25, 150, 80);
        SyntheticCard.draw(luma, W, H, neighbour, 25, 150, 80);
        Set<Integer> ids = new HashSet<>();
        CardQuad last = null;
        for (int i = 0; i < 15; i++) {
            AssistedPipeline.Outcome out = step(luma);
            if (out.card() != null) {
                ids.add(out.card().id());
                last = out.card().quad();
            }
        }
        assertEquals(1, ids.size());
        assertTrue(SyntheticCard.cornerError(last, main) < 3 || SyntheticCard.cornerError(last, neighbour) < 3);
    }

    @Test
    void finderKeepsUpOnAMovingCardWithoutCapturing() {
        for (int i = 0; i < 20; i++) {
            CardQuad moving = SyntheticCard.corners(200 + i * 12, 240, 300, 12, 0);
            assertFalse(step(frameWith(moving, 150, 80)).captureNow(), "frame " + i);
        }
    }
}
