package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CaptureLogicTest {

    private static final CardQuad QUAD = new CardQuad(new Corner(0, 0), new Corner(1, 0), new Corner(1, 1), new Corner(0, 1), 1, true);
    private static final TrackedCard STEADY = new TrackedCard(1, QUAD, 0, 0, true);
    private static final float[] SAME = new float[352];
    private static final float[] OTHER = new float[352];

    static {
        Arrays.fill(OTHER, 100f);
    }

    private static boolean fire(CaptureGate gate, TrackedCard card, float[] signature, long now) {
        return gate.update(card, signature, now, true, 800, true, false);
    }

    @Test
    void firesOnlyWhenAllConditionsHold() {
        CaptureGate gate = new CaptureGate(null);
        assertFalse(gate.update(STEADY, SAME, 799, true, 800, true, false), "not steady long enough");
        assertFalse(gate.update(STEADY, SAME, 900, false, 800, true, false), "focus not settled");
        assertFalse(gate.update(STEADY, SAME, 900, true, 800, false, false), "auto off");
        assertFalse(gate.update(STEADY, SAME, 900, true, 800, true, true), "capture in flight");
        assertFalse(gate.update(null, null, 900, true, 800, true, false), "no card");
        assertTrue(gate.update(STEADY, SAME, 900, true, 800, true, false));
    }

    @Test
    void neverFiresOnAGapTheTrackerIsBridging() {
        TrackedCard ghost = new TrackedCard(1, QUAD, 0, 0, false);
        assertFalse(fire(new CaptureGate(null), ghost, null, 5000));
    }

    @Test
    void firesOnceThenBlocksTheSameCardUntilTheTimeLimit() {
        CaptureGate gate = new CaptureGate(null);
        assertTrue(fire(gate, STEADY, SAME, 1000));
        for (long t = 1100; t < 1100 + CaptureGate.HOLD_MAX_MS; t += 100) {
            assertFalse(fire(gate, STEADY, SAME, t), "at " + t);
        }
        assertTrue(fire(gate, STEADY, SAME, 1100 + CaptureGate.HOLD_MAX_MS));
    }

    /** The run that took about 60 s: reopened with a card that looked the same and hardly moved. */
    @Test
    void aCardThatLooksTheSameCanNotBlockForLong() {
        CaptureGate gate = new CaptureGate(SAME);
        long t = 1000;
        while (!fire(gate, STEADY, SAME, t)) {
            t += 100;
            assertTrue(t < 1000 + CaptureGate.HOLD_MAX_MS + 200, "still blocked at " + t);
        }
        assertEquals("time limit", gate.releasedBy());
        assertEquals(CaptureGate.HOLD_MAX_MS, gate.blockedMs());
    }

    @Test
    void movingWellAwayLiftsTheBlockButAShakeDoesNot() {
        CaptureGate gate = new CaptureGate(SAME);
        assertFalse(fire(gate, STEADY, SAME, 1000));
        TrackedCard moved = new TrackedCard(1, shifted(0.6), 0, 0, true);
        assertFalse(fire(gate, moved, SAME, 1100));
        assertFalse(fire(gate, STEADY, SAME, 1200), "back again: it was only a shake");
        for (long t = 1300; t < 1300 + CaptureGate.CHANGED_MS; t += 100) {
            assertFalse(fire(gate, moved, SAME, t));
        }
        assertTrue(fire(gate, moved, SAME, 1300 + CaptureGate.CHANGED_MS));
        assertEquals("card moved", gate.releasedBy());
    }

    @Test
    void aSmallDriftDoesNotLiftTheBlock() {
        CaptureGate gate = new CaptureGate(SAME);
        TrackedCard drifted = new TrackedCard(1, shifted(0.3), 0, 0, true);
        assertFalse(fire(gate, STEADY, SAME, 1000));
        for (long t = 1100; t < 1000 + CaptureGate.HOLD_MAX_MS; t += 100) {
            assertFalse(fire(gate, drifted, SAME, t));
        }
    }

    @Test
    void theTimeLimitWaitsForTheCaptureToFinish() {
        CaptureGate gate = new CaptureGate(SAME);
        for (long t = 1000; t < 1000 + 2 * CaptureGate.HOLD_MAX_MS; t += 100) {
            assertFalse(gate.update(STEADY, SAME, t, true, 800, true, true));
        }
        assertTrue(gate.isLatched());
        assertFalse(fire(gate, STEADY, SAME, 1000 + 2 * CaptureGate.HOLD_MAX_MS));
    }

    private static boolean fireWithGyro(CaptureGate gate, long steadyMs, double speed, long phoneSteadyMs, long now) {
        return gate.update(STEADY, SAME, now, true, steadyMs, true, false, new CaptureGate.PhoneState(speed, phoneSteadyMs));
    }

    @Test
    void withNoHoldTimeItShootsAsSoonAsThePhoneIsNotPlainlyMoving() {
        assertFalse(fireWithGyro(new CaptureGate(null), 0, CaptureGate.MOVING_RAD_S + 0.01, 0, 1000));
        assertTrue(fireWithGyro(new CaptureGate(null), 0, CaptureGate.MOVING_RAD_S, 0, 1000));
    }

    @Test
    void aHoldTimeCanStillBeChosenToCompare() {
        assertFalse(fireWithGyro(new CaptureGate(null), 300, 0.01, 299, 1000));
        assertTrue(fireWithGyro(new CaptureGate(null), 300, 0.01, 300, 1000));
    }

    @Test
    void withoutAGyroscopeTheOutlineMustHoldStillAWhile() {
        TrackedCard held = new TrackedCard(1, QUAD, 0, 1000, true);
        assertFalse(new CaptureGate(null).update(held, SAME, 1000 + CaptureGate.NO_GYRO_MIN_STEADY_MS - 1, true, 0, true, false));
        assertTrue(new CaptureGate(null).update(held, SAME, 1000 + CaptureGate.NO_GYRO_MIN_STEADY_MS, true, 0, true, false));
    }

    private static CardQuad shifted(double dx) {
        return new CardQuad(new Corner(dx, 0), new Corner(1 + dx, 0), new Corner(1 + dx, 1), new Corner(dx, 1), 1, true);
    }

    @Test
    void shortAbsenceKeepsTheLatchAndLongAbsenceReleases() {
        CaptureGate gate = new CaptureGate(null);
        fire(gate, STEADY, SAME, 1000);
        fire(gate, null, null, 1100);
        fire(gate, null, null, 1100 + CaptureGate.LEFT_AFTER_MS - 1);
        assertFalse(fire(gate, STEADY, SAME, 2200));
        fire(gate, null, null, 3000);
        fire(gate, null, null, 3000 + CaptureGate.LEFT_AFTER_MS);
        assertFalse(gate.isLatched());
        assertTrue(fire(gate, STEADY, SAME, 5000));
    }

    @Test
    void differentCardMustShowForSeveralFrames() {
        CaptureGate gate = new CaptureGate(null);
        fire(gate, STEADY, SAME, 1000);
        assertFalse(fire(gate, STEADY, OTHER, 1100));
        assertFalse(fire(gate, STEADY, SAME, 1200), "one matching frame resets the count");
        for (int i = 0; i < CaptureGate.CHANGED_FRAMES - 1; i++) {
            assertFalse(fire(gate, STEADY, OTHER, 1300 + i));
        }
        assertFalse(fire(gate, STEADY, OTHER, 1300 + CaptureGate.CHANGED_MS - 1), "too soon, however many frames");
        assertTrue(fire(gate, STEADY, OTHER, 1300 + CaptureGate.CHANGED_MS));
        assertEquals("card changed", gate.releasedBy());
    }

    @Test
    void unknownAppearanceDoesNotCountAsChanged() {
        CaptureGate gate = new CaptureGate(SAME);
        for (int i = 0; i < 10; i++) {
            assertFalse(fire(gate, STEADY, null, 1000 + i));
        }
    }

    @Test
    void startsLatchedOnACardCapturedBeforeAPause() {
        assertTrue(new CaptureGate(SAME).isLatched());
        assertFalse(new CaptureGate(null).isLatched());
    }

    @Test
    void latchWithoutACardAdoptsTheNextOne() {
        CaptureGate gate = new CaptureGate(null);
        gate.latch(null);
        assertFalse(fire(gate, STEADY, SAME, 1000));
        assertFalse(fire(gate, STEADY, SAME, 1100));
    }

    @Test
    void releaseReArms() {
        CaptureGate gate = new CaptureGate(SAME);
        gate.release();
        assertTrue(fire(gate, STEADY, SAME, 1000));
    }

    @Test
    void statsSummariseTimings() {
        FinderStats stats = new FinderStats();
        assertEquals(0, stats.summary().frames());
        for (int i = 1; i <= 100; i++) {
            stats.add(i, i * 100L);
        }
        FinderStats.Summary summary = stats.summary();
        assertEquals(100, summary.frames());
        assertEquals(50.5, summary.meanMs(), 1e-9);
        assertEquals(50, summary.p50Ms());
        assertEquals(95, summary.p95Ms());
        assertEquals(100, summary.maxMs());
    }

    @Test
    void statsKeepTheLatestSamplesOnly() {
        FinderStats stats = new FinderStats();
        for (int i = 0; i < 2000; i++) {
            stats.add(i < 1000 ? 1000 : 1, i);
        }
        assertEquals(2000, stats.summary().frames());
        assertEquals(1, stats.summary().maxMs());
    }

    @Test
    void rateCoversTheRecentWindow() {
        FinderStats stats = new FinderStats();
        assertEquals(0, stats.framesPerSecond());
        for (int i = 0; i < 50; i++) {
            stats.add(1, i * 100L);
        }
        assertEquals(10, stats.framesPerSecond(), 0.01);
        stats.add(1, 60_000);
        assertEquals(0, stats.framesPerSecond());
    }

    @Test
    void lumaUsesRec601Weights() {
        byte[] luma = Luma.fromArgb(new int[] {0xFFFFFFFF, 0xFF000000, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF});
        assertEquals(255, luma[0] & 0xFF);
        assertEquals(0, luma[1] & 0xFF);
        assertEquals(76, luma[2] & 0xFF);
        assertEquals(149, luma[3] & 0xFF);
        assertEquals(29, luma[4] & 0xFF);
    }

    /** Assisted logic must run in a plain JVM, so it uses {@link Camera2Values} instead of camera2's constants. */
    @Test
    void assistSourcesUseNoAndroid() throws IOException {
        File[] sources = new File("src/main/java/forge/cardocr/assist").listFiles((d, n) -> n.endsWith(".java"));
        assertNotNull(sources);
        assertTrue(sources.length > 5);
        for (File source : sources) {
            for (String line : Files.readAllLines(source.toPath())) {
                assertFalse(line.startsWith("import android."), source.getName() + ": " + line);
            }
        }
    }
}
