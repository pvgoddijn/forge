package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.*;
import forge.cardocr.finder.*;
import org.junit.jupiter.api.*;

class MotionLogicTest {

    private static final long MS = 1_000_000L;
    private static final CardQuad QUAD = new CardQuad(new Corner(0, 0), new Corner(1, 0), new Corner(1, 1), new Corner(0, 1), 1, true);
    private static final TrackedCard STILL_CARD = new TrackedCard(1, QUAD, 0, 0, true);
    private static final float[] SIGNATURE = new float[352];

    private static GyroHistory constantRate(double x, double y, double z, long untilMs) {
        GyroHistory history = new GyroHistory();
        for (long t = 10; t <= untilMs; t += 10) {
            history.add(t * MS, x, y, z);
        }
        return history;
    }

    @Test
    void integratesAConstantRateOverPartsOfSampleIntervals() {
        GyroHistory.Rotation r = constantRate(2, -1, 0.5, 1000).integrate(105 * MS, 305 * MS);
        assertEquals(0.4, r.x(), 1e-9);
        assertEquals(-0.2, r.y(), 1e-9);
        assertEquals(0.1, r.z(), 1e-9);
    }

    @Test
    void integratesARampWithLinearInterpolation() {
        GyroHistory history = new GyroHistory();
        history.add(0, 0, 0, 0);
        history.add(100 * MS, 1, 0, 0);
        assertEquals(0.05, history.integrate(1, 100 * MS).x(), 1e-6);
        assertEquals(0.0125, history.integrate(1, 50 * MS).x(), 1e-6);
    }

    @Test
    void holdsTheLastRateBrieflyWhenAFrameIsNewerThanTheLastSample() {
        GyroHistory history = constantRate(1, 0, 0, 100);
        assertEquals(0.02, history.integrate(100 * MS, 120 * MS).x(), 1e-9);
    }

    @Test
    void uncoveredIntervalsAreUnknownAndNotNoMotion() {
        GyroHistory history = constantRate(1, 0, 0, 100);
        assertNull(history.integrate(1, 50 * MS), "before the first sample");
        assertNull(history.integrate(50 * MS, 900 * MS), "long after the last sample");
        assertNull(new GyroHistory().integrate(1, 2));
    }

    @Test
    void outOfOrderSamplesAreIgnored() {
        GyroHistory history = constantRate(1, 0, 0, 100);
        history.add(50 * MS, 100, 0, 0);
        assertEquals(1, history.peakSpeed(100 * MS, 100 * MS), 1e-9);
    }

    @Test
    void ringKeepsOnlyTheLatestSamples() {
        GyroHistory history = constantRate(1, 0, 0, 20_000);
        assertNull(history.integrate(100 * MS, 200 * MS));
        assertEquals(0.1, history.integrate(19_800 * MS, 19_900 * MS).x(), 1e-9);
    }

    @Test
    void meanAndPeakSpeedCoverTheirWindowOnly() {
        GyroHistory history = new GyroHistory();
        history.add(100 * MS, 3, 4, 0);
        history.add(200 * MS, 0, 0, 1);
        history.add(300 * MS, 0, 0, 2);
        assertEquals(1.5, history.meanSpeed(300 * MS, 150 * MS), 1e-9);
        assertEquals(5, history.peakSpeed(300 * MS, 300 * MS), 1e-9);
        assertEquals(2, history.peakSpeed(300 * MS, 150 * MS), 1e-9);
        assertTrue(Double.isNaN(history.meanSpeed(50 * MS, 10 * MS)));
    }

    @Test
    void focalLengthComesFromSensorSizeAndTheCroppedStream() {
        // A 4 mm lens on a 6.4 mm wide sensor is 2500 of 4000 pixels, so 400 of 640.
        GyroMapping mapping = GyroMapping.of(4f, 6.4, 4.8, new Dim(4000, 3000), 90, false, new Dim(640, 480), new ActiveRect(0, 0, 4000, 3000));
        assertEquals(400, mapping.focalX(), 1e-6);
        assertEquals(400, mapping.focalY(), 1e-6);
        // A 16:9 stream shows the sensor's full width and less of its height, at the same scale.
        GyroMapping wide = GyroMapping.of(4f, 6.4, 4.8, new Dim(4000, 3000), 90, false, new Dim(640, 360), new ActiveRect(0, 0, 4000, 3000));
        assertEquals(400, wide.focalY(), 1e-6);
        // Half the sensor shown means twice the focal length in the stream's pixels.
        GyroMapping zoomed = GyroMapping.of(4f, 6.4, 4.8, new Dim(4000, 3000), 90, false, new Dim(640, 480), new ActiveRect(1000, 750, 2000, 1500));
        assertEquals(800, zoomed.focalX(), 1e-6);
    }

    @Test
    void missingOpticsGiveNoMapping() {
        ActiveRect crop = new ActiveRect(0, 0, 4000, 3000);
        assertNull(GyroMapping.of(null, 6.4, 4.8, new Dim(4000, 3000), 90, false, new Dim(640, 480), crop));
        assertNull(GyroMapping.of(4f, null, 4.8, new Dim(4000, 3000), 90, false, new Dim(640, 480), crop));
        assertNull(GyroMapping.of(4f, 6.4, 4.8, null, 90, false, new Dim(640, 480), crop));
    }

    @Test
    void modelWithoutSamplesOrWithStaleSamplesReportsNoMotion() {
        MotionModel model = new MotionModel();
        assertNull(model.forFrame(0, 100 * MS, 100 * MS));
        model.history().add(100 * MS, 0.1, 0, 0);
        assertNotNull(model.forFrame(0, 100 * MS, 100 * MS));
        assertNull(model.forFrame(0, 100 * MS, 100 * MS + MotionModel.STALE_NS + 1));
    }

    @Test
    void modelStillGivesSpeedWithoutAMapping() {
        MotionModel model = new MotionModel();
        for (long t = 10; t <= 500; t += 10) {
            model.history().add(t * MS, 0.1, 0, 0);
        }
        FrameMotion motion = model.forFrame(100 * MS, 200 * MS, 500 * MS);
        assertSame(ImageMotion.NONE, motion.image());
        assertEquals(0.1, motion.speedRadS(), 1e-9);
    }

    @Test
    void overlayFollowsThePreviewClockWhenUsableAndStopsAtItsAgeLimit() {
        MotionModel model = new MotionModel();
        model.setMapping(new GyroMapping(0, false, 500, 500, 320, 240));
        for (long t = 10; t <= 3000; t += 10) {
            model.history().add(t * MS, 1, 0, 0);
        }
        Corner centre = new Corner(320, 240);
        // Preview frame known, 100 ms after the result.
        assertEquals(240 + 500 * Math.tan(0.1), model.forOverlay(1000 * MS, 1100 * MS, 1150 * MS).moved(centre).y(), 0.1);
        // Unusable preview time: now less an assumed lag.
        assertEquals(240 + 500 * Math.tan(0.15 - MotionModel.PREVIEW_LAG_NS / 1e9), model.forOverlay(1000 * MS, 0, 1150 * MS).moved(centre).y(), 0.1);
        // An old result moves no further than the limit.
        double limited = model.forOverlay(1000 * MS, 0, 2500 * MS).moved(centre).y();
        double expected = 240 + 500 * Math.tan(MotionModel.MAX_OVERLAY_AGE_NS / 1e9);
        assertEquals(expected, limited, 1);
    }

    @Test
    void aHandHoveringAroundTheOldLimitCountsAsSteady() {
        PhoneSteadiness phone = new PhoneSteadiness();
        for (long t = 0; t <= 1000; t += 33) {
            phone.update(t % 500 < 100 ? 0.14 : 0.07, t);
        }
        assertTrue(phone.steadyForMs(1000) >= 900, "got " + phone.steadyForMs(1000));
    }

    @Test
    void oneReallyLargeBumpRestartsTheClockButASmallOneDoesNot() {
        PhoneSteadiness phone = new PhoneSteadiness();
        for (long t = 0; t <= 400; t += 33) {
            phone.update(0.05, t);
        }
        phone.update(0.2, 420);
        assertTrue(phone.steadyForMs(420) >= 400, "small bump");
        phone.update(BlurBudget.limitRadS(null) * PhoneSteadiness.BUMP_FACTOR * 1.01, 450);
        assertEquals(0, phone.steadyForMs(450));
    }

    @Test
    void sustainedMovementEndsSteadiness() {
        PhoneSteadiness phone = new PhoneSteadiness();
        for (long t = 0; t <= 1000; t += 33) {
            phone.update(0.2, t);
        }
        assertEquals(0, phone.steadyForMs(1000));
    }

    @Test
    void theLimitFollowsTheExposureWithinItsBounds() {
        assertEquals(0.129, BlurBudget.limitRadS(null), 0.001);
        assertEquals(BlurBudget.MIN_LIMIT_RAD_S, BlurBudget.limitRadS(33_000_000L));
        assertEquals(BlurBudget.MAX_LIMIT_RAD_S, BlurBudget.limitRadS(1_000_000L));
        assertEquals(0.216, BlurBudget.limitRadS(10_000_000L), 0.001);
        assertEquals(BlurBudget.BUDGET_PX, BlurBudget.blurPx(BlurBudget.limitRadS(null), BlurBudget.DEFAULT_EXPOSURE_NS), 0.01);
    }

    @Test
    void smallMotionDoesNotMoveTheOverlayAndLargeMotionDoes() {
        assertSame(QUAD, OverlayCompensation.apply(QUAD, ImageMotion.shift(1.5, 0)));
        assertEquals(QUAD.c0().x() + 10, OverlayCompensation.apply(QUAD, ImageMotion.shift(10, 0)).c0().x(), 1e-9);
        assertEquals(QUAD.c0().x() + 1.5, OverlayCompensation.apply(QUAD, ImageMotion.shift(3, 0)).c0().x(), 1e-9, "fades in");
    }

    private static boolean gate(CaptureGate gate, TrackedCard card, long now, double speed, long phoneSteadyMs) {
        return gate.update(card, SIGNATURE, now, true, 800, true, false, new CaptureGate.PhoneState(speed, phoneSteadyMs));
    }

    @Test
    void withAGyroscopeAStillOutlineIsNotEnough() {
        CaptureGate gate = new CaptureGate(null);
        assertFalse(gate(gate, STILL_CARD, 5000, 0.2, 0), "phone moving");
        assertFalse(gate(gate, STILL_CARD, 5100, 0.01, 700), "not steady long enough");
        assertTrue(gate(gate, STILL_CARD, 5300, 0.01, 900));
    }

    @Test
    void withAGyroscopeACardMovingInASteadyHandDoesNotFire() {
        TrackedCard moved = new TrackedCard(1, QUAD, 0, 4900, true);
        CaptureGate gate = new CaptureGate(null);
        assertFalse(gate(gate, moved, 5000, 0.01, 2000));
        assertTrue(gate(gate, moved, 4900 + CaptureGate.CARD_STILL_MS, 0.01, 2000));
    }

    @Test
    void withAGyroscopeAShakyOutlineClockCanStillFireOnceTheCardHasHeldForAMoment() {
        // The outline's clock alone would have to reach the 800 ms setting, and the gyroscope's does.
        TrackedCard card = new TrackedCard(1, QUAD, 0, 1000 - CaptureGate.CARD_STILL_MS, true);
        assertTrue(gate(new CaptureGate(null), card, 1000, 0.01, 800));
    }

    @Test
    void gateFiresAsSoonAsAllHoldAndReportsTheWait() {
        CaptureGate gate = new CaptureGate(null);
        assertFalse(gate(gate, STILL_CARD, 1000, 0.2, 0), "waiting starts here");
        assertTrue(gate(gate, STILL_CARD, 2000, 0.09, 900), "no watching for a calmer moment");
        assertEquals(1000, gate.lastWaitMs());
        assertTrue(gate.isLatched());
    }

    @Test
    void aMissedFrameHoldsFireButDoesNotEndTheWait() {
        TrackedCard ghost = new TrackedCard(1, QUAD, 0, 0, false);
        CaptureGate gate = new CaptureGate(null);
        gate(gate, STILL_CARD, 1000, 0.2, 0);
        assertFalse(gate(gate, ghost, 2000, 0.01, 900));
        assertTrue(gate(gate, STILL_CARD, 2066, 0.01, 966));
        assertEquals(1066, gate.lastWaitMs());
    }

    @Test
    void withoutAGyroscopeTheGateFiresAtTheFirstQualifyingMoment() {
        CaptureGate gate = new CaptureGate(null);
        assertFalse(gate.update(STILL_CARD, SIGNATURE, 799, true, 800, true, false, null));
        assertTrue(gate.update(STILL_CARD, SIGNATURE, 800, true, 800, true, false, null));
    }

    @Test
    void noStabilisationListIsNotSupported() {
        assertFalse(HardwareSupport.hasOpticalStabilization(null));
        assertFalse(HardwareSupport.hasOpticalStabilization(new int[] {0}));
        assertTrue(HardwareSupport.hasOpticalStabilization(new int[] {0, 1}));
    }
}
