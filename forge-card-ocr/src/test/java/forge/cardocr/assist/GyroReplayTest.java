package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

/**
 * Replays a simulated phone through the real history, mapping and tracker. The outlines come from a
 * separate camera model written from Android's documented axes: world points are projected through
 * the phone's true orientation, so no code is shared with {@link GyroMapping}.
 */
class GyroReplayTest {

    private static final long MS = 1_000_000L;
    private static final int W = 640;
    private static final int H = 480;
    private static final double F = 500;
    private static final long GYRO_STEP_MS = 10;
    private static final long FRAME_MS = 66;

    /** Sensor x and y and the viewing direction, in device axes. */
    private record Axes(double[] ex, double[] ey, double[] look) {
    }

    private static final double[] PX = {1, 0, 0};
    private static final double[] NX = {-1, 0, 0};
    private static final double[] PY = {0, 1, 0};
    private static final double[] NY = {0, -1, 0};
    private static final double[] PZ = {0, 0, 1};
    private static final double[] NZ = {0, 0, -1};

    /**
     * Upright, a rear camera sees +X to its right and -Y downwards, while a front camera sees -X and
     * -Y. SENSOR_ORIENTATION is how far sensor pixels are turned clockwise to be upright.
     */
    private static Axes axes(int orientation, boolean front) {
        double[] look = front ? PZ : NZ;
        return front ? switch (orientation) {
            case 0 -> new Axes(NX, NY, look);
            case 90 -> new Axes(NY, PX, look);
            case 180 -> new Axes(PX, PY, look);
            default -> new Axes(PY, NX, look);
        } : switch (orientation) {
            case 0 -> new Axes(PX, NY, look);
            case 90 -> new Axes(NY, NX, look);
            case 180 -> new Axes(NX, PY, look);
            default -> new Axes(PY, PX, look);
        };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** The phone and a fixed card, stepped at the gyroscope's rate. */
    private static final class Phone {
        final Axes axes;
        final double[][] world;
        /** Rows are device axes in the world, so world-to-device is a plain product. */
        double[][] orientation = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

        Phone(int sensorOrientation, boolean front, double cardX, double cardY, double halfW, double halfH) {
            axes = axes(sensorOrientation, front);
            world = new double[][] {
                    ray(cardX - halfW, cardY - halfH), ray(cardX + halfW, cardY - halfH),
                    ray(cardX + halfW, cardY + halfH), ray(cardX - halfW, cardY + halfH)};
        }

        /** The device-frame point seen at this pixel, at one unit along the view, at the start. */
        private double[] ray(double px, double py) {
            double sx = (px - W / 2.0) / F;
            double sy = (py - H / 2.0) / F;
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) {
                out[i] = sx * axes.ex[i] + sy * axes.ey[i] + axes.look[i];
            }
            return out;
        }

        void turn(double wx, double wy, double wz, double seconds) {
            double angle = Math.sqrt(wx * wx + wy * wy + wz * wz) * seconds;
            if (angle == 0) {
                return;
            }
            double k = seconds / angle;
            double[] axis = {wx * k, wy * k, wz * k};
            double[][] next = new double[3][3];
            for (int row = 0; row < 3; row++) {
                // Each device axis, turned about the rotation axis given in device terms.
                double[] e = {row == 0 ? 1 : 0, row == 1 ? 1 : 0, row == 2 ? 1 : 0};
                double along = dot(axis, e);
                double[] cross = {axis[1] * e[2] - axis[2] * e[1], axis[2] * e[0] - axis[0] * e[2], axis[0] * e[1] - axis[1] * e[0]};
                for (int j = 0; j < 3; j++) {
                    double inDevice = e[j] * Math.cos(angle) + cross[j] * Math.sin(angle) + axis[j] * along * (1 - Math.cos(angle));
                    for (int c = 0; c < 3; c++) {
                        next[row][c] += inDevice * orientation[j][c];
                    }
                }
            }
            orientation = next;
        }

        Corner project(double[] point) {
            double[] device = {dot(orientation[0], point), dot(orientation[1], point), dot(orientation[2], point)};
            double z = dot(device, axes.look);
            return new Corner(W / 2.0 + F * dot(device, axes.ex) / z, H / 2.0 + F * dot(device, axes.ey) / z);
        }

        CardQuad outline(Random noise, double noisePx) {
            Corner[] c = new Corner[4];
            for (int i = 0; i < 4; i++) {
                Corner p = project(world[i]);
                c[i] = new Corner(p.x() + wobble(noise, noisePx), p.y() + wobble(noise, noisePx));
            }
            return new CardQuad(c[0], c[1], c[2], c[3], 1, true);
        }

        private static double wobble(Random noise, double px) {
            return noise == null ? 0 : (noise.nextDouble() * 2 - 1) * px;
        }
    }

    private interface Rates {
        double[] at(long ms);
    }

    /** A hand's slow sway on two axes and a little roll, in rad/s. */
    private static final Rates HAND = ms -> new double[] {
            0.12 * Math.sin(ms / 1000.0 * 2 * Math.PI * 1.1),
            0.10 * Math.sin(ms / 1000.0 * 2 * Math.PI * 1.7 + 1),
            0.05 * Math.sin(ms / 1000.0 * 2 * Math.PI * 0.9 + 2)};

    private static final class Run {
        int framesTracked;
        final Set<Integer> ids = new HashSet<>();
        double errorSum;
        int errorFrames;
        MotionAudit.Summary audit;
        double meanError() {
            return errorSum / errorFrames;
        }
    }

    /**
     * Runs the phone through the tracker.
     *
     * @param compensate whether the tracker is told the gyroscope's motion
     * @param missing true for a frame the finder doesn't find the card on
     * @param mapping what the app believes about the camera
     */
    private static Run replay(Phone phone, GyroMapping mapping, Rates rates, long durationMs, boolean compensate,
            java.util.function.LongPredicate missing, long settleMs) {
        MotionModel model = new MotionModel();
        model.setMapping(mapping);
        CardTracker tracker = new CardTracker();
        MotionAudit audit = new MotionAudit();
        Random noise = new Random(7);
        Run run = new Run();
        long nextFrame = 100;
        long previousFrame = 0;
        for (long t = 10; t <= durationMs; t += GYRO_STEP_MS) {
            double[] w = rates.at(t);
            phone.turn(w[0], w[1], w[2], GYRO_STEP_MS / 1000.0);
            model.history().add(t * MS, w[0], w[1], w[2]);
            if (t < nextFrame) {
                continue;
            }
            nextFrame += FRAME_MS;
            ImageMotion motion = compensate && previousFrame > 0 ? model.between(previousFrame * MS, t * MS) : ImageMotion.NONE;
            previousFrame = t;
            List<CardQuad> candidates = missing.test(t) ? List.of() : List.of(phone.outline(noise, 0.6));
            TrackedCard card = tracker.update(candidates, t, motion);
            audit.observe(card, motion);
            if (card != null) {
                run.ids.add(card.id());
                run.framesTracked++;
                if (t >= settleMs) {
                    run.errorSum += Quads.distance(card.quad(), phone.outline(null, 0));
                    run.errorFrames++;
                }
            }
        }
        run.audit = audit.summary();
        return run;
    }

    private static GyroMapping trueMapping(int orientation, boolean front) {
        return new GyroMapping(orientation, front, F, F, W / 2.0, H / 2.0);
    }

    private static Stream<Arguments> cameras() {
        return Stream.of(Arguments.of(0, false), Arguments.of(90, false), Arguments.of(180, false), Arguments.of(270, false),
                Arguments.of(0, true), Arguments.of(90, true), Arguments.of(180, true), Arguments.of(270, true));
    }

    @ParameterizedTest
    @MethodSource("cameras")
    void aSmallSwayKeepsTheCardTrackedAsOneTrack(int orientation, boolean front) {
        Run run = replay(new Phone(orientation, front, 320, 240, 100, 140), trueMapping(orientation, front), HAND, 4000, true, t -> false, 500);
        assertEquals(1, run.ids.size(), "tracks " + run.ids);
        assertTrue(run.framesTracked > 45, "frames " + run.framesTracked);
    }

    @ParameterizedTest
    @MethodSource("cameras")
    void aSmallSwayAlsoSurvivesWithoutCompensation(int orientation, boolean front) {
        Run run = replay(new Phone(orientation, front, 320, 240, 100, 140), trueMapping(orientation, front), HAND, 4000, false, t -> false, 500);
        assertEquals(1, run.ids.size(), "tracks " + run.ids);
    }

    @ParameterizedTest
    @MethodSource("cameras")
    void compensationReducesOutlineError(int orientation, boolean front) {
        // A finder that misses two frames of three shows it most, but it must help on every frame too.
        java.util.function.LongPredicate gappy = t -> ((t - 100) / FRAME_MS) % 3 != 0;
        GyroMapping mapping = trueMapping(orientation, front);
        Run gappyOn = replay(new Phone(orientation, front, 320, 240, 100, 140), mapping, HAND, 4000, true, gappy, 500);
        Run gappyOff = replay(new Phone(orientation, front, 320, 240, 100, 140), mapping, HAND, 4000, false, gappy, 500);
        assertTrue(gappyOn.meanError() < 0.6 * gappyOff.meanError(), gappyOn.meanError() + " vs " + gappyOff.meanError());
        Run on = replay(new Phone(orientation, front, 320, 240, 100, 140), mapping, HAND, 4000, true, t -> false, 500);
        Run off = replay(new Phone(orientation, front, 320, 240, 100, 140), mapping, HAND, 4000, false, t -> false, 500);
        assertTrue(on.meanError() < off.meanError(), on.meanError() + " vs " + off.meanError());
    }

    @ParameterizedTest
    @MethodSource("cameras")
    void aFastSwingThatLosesTheCardEndsTheTrack(int orientation, boolean front) {
        // Sweeps away at 3 rad/s for 300 ms, about 50 degrees, then comes back to the card.
        Rates swing = ms -> {
            double w = ms < 1000 ? 0 : ms < 1300 ? 3 : ms < 1900 ? 0 : ms < 2200 ? -3 : 0;
            return new double[] {0, w, 0};
        };
        Run run = replay(new Phone(orientation, front, 320, 240, 100, 140), trueMapping(orientation, front), swing, 3500, true,
                t -> t >= 1000 && t < 2200, 500);
        assertEquals(2, run.ids.size(), "tracks " + run.ids);
    }

    @Test
    void aCardJumpingWithNoPhoneMotionRestartsTrackingEvenWithAGyroscope() {
        MotionModel model = new MotionModel();
        model.setMapping(trueMapping(90, false));
        CardTracker tracker = new CardTracker();
        Phone near = new Phone(90, false, 200, 240, 100, 140);
        Phone far = new Phone(90, false, 400, 240, 100, 140);
        TrackedCard card = null;
        long t = 0;
        for (; t < 1000; t += FRAME_MS) {
            card = tracker.update(List.of(near.outline(null, 0)), t, ImageMotion.NONE);
        }
        int first = card.id();
        for (; t < 2500; t += FRAME_MS) {
            card = tracker.update(List.of(far.outline(null, 0)), t, ImageMotion.NONE);
        }
        assertNotEquals(first, card.id());
    }

    /** Hand-worked cases, so the simulation's own axes are pinned to Android's too. */
    @ParameterizedTest
    @CsvSource({
        // A portrait rear camera with its sensor's x along the phone's -Y, the usual layout.
        // +X turns the phone's top towards the user, so the camera looks up and the scene drops: +x.
        "90, false, 0.01, 0, 0, 5, 0",
        // +Y turns the rear camera to the phone's left, so the scene goes right on screen, which is -y.
        "90, false, 0, 0.01, 0, 0, -5",
        "270, false, 0.01, 0, 0, -5, 0",
        "270, false, 0, 0.01, 0, 0, 5",
        "0, false, 0.01, 0, 0, 0, 5",
        "0, false, 0, 0.01, 0, 5, 0",
        "180, false, 0.01, 0, 0, 0, -5",
        "180, false, 0, 0.01, 0, -5, 0",
    })
    void signsMatchHandWorkedCases(int orientation, boolean front, double x, double y, double z, double expectX, double expectY) {
        GyroMapping mapping = trueMapping(orientation, front);
        Corner centre = new Corner(W / 2.0, H / 2.0);
        Corner moved = mapping.motion(new GyroHistory.Rotation(x, y, z)).moved(centre);
        assertEquals(expectX, moved.x() - centre.x(), 0.01);
        assertEquals(expectY, moved.y() - centre.y(), 0.01);
    }

    @ParameterizedTest
    @MethodSource("cameras")
    void mappingAgreesWithTheCameraModelForEachAxis(int orientation, boolean front) {
        GyroMapping mapping = trueMapping(orientation, front);
        double[][] rotations = {{0.03, 0, 0}, {0, 0.03, 0}, {0, 0, 0.03}, {0.02, -0.02, 0.01}};
        for (double[] w : rotations) {
            Phone phone = new Phone(orientation, front, 200, 150, 60, 90);
            CardQuad before = phone.outline(null, 0);
            phone.turn(w[0], w[1], w[2], 1);
            CardQuad truth = phone.outline(null, 0);
            CardQuad predicted = mapping.motion(new GyroHistory.Rotation(w[0], w[1], w[2])).moved(before);
            double moved = Quads.distance(before, truth);
            assertTrue(moved > 1, "the turn moved the outline");
            assertTrue(Quads.distance(predicted, truth) < 0.01 * moved + 0.01,
                    Arrays.toString(w) + " left " + Quads.distance(predicted, truth) + " of " + moved);
        }
    }

    @ParameterizedTest
    @MethodSource("cameras")
    void aRightMappingPassesTheOnDeviceCheck(int orientation, boolean front) {
        Run run = replay(new Phone(orientation, front, 320, 240, 100, 140), trueMapping(orientation, front), HAND, 8000, true, t -> false, 500);
        assertEquals(MotionAudit.OK, run.audit.verdict(), run.audit.toString());
        assertTrue(run.audit.rX() > 0.8 && run.audit.rY() > 0.8, run.audit.toString());
        assertEquals(1, run.audit.gainX(), 0.1);
        assertEquals(1, run.audit.gainY(), 0.1);
        assertTrue(run.audit.jitterMeanPx() < 0.5 * run.audit.uncompensatedMeanPx(), run.audit.toString());
    }

    /** The orientation half a turn out flips both axes, which must be plain in the check. */
    @ParameterizedTest
    @MethodSource("cameras")
    void aWrongSignFailsTheOnDeviceCheck(int orientation, boolean front) {
        GyroMapping wrong = trueMapping((orientation + 180) % 360, front);
        Run run = replay(new Phone(orientation, front, 320, 240, 100, 140), wrong, HAND, 8000, true, t -> false, 500);
        assertEquals(MotionAudit.WRONG_SIGN, run.audit.verdict(), run.audit.toString());
    }

    /** A quarter turn out swaps the axes. */
    @ParameterizedTest
    @MethodSource("cameras")
    void swappedAxesFailTheOnDeviceCheck(int orientation, boolean front) {
        GyroMapping wrong = trueMapping((orientation + 90) % 360, front);
        Run run = replay(new Phone(orientation, front, 320, 240, 100, 140), wrong, HAND, 8000, true, t -> false, 500);
        assertEquals(MotionAudit.AXES_SWAPPED, run.audit.verdict(), run.audit.toString());
    }

    @Test
    void rollIsPredictedWithTheRightSign() {
        Rates rolling = ms -> new double[] {0.1 * Math.sin(ms / 300.0), 0.1 * Math.sin(ms / 170.0 + 1), 0.3 * Math.sin(ms / 400.0)};
        Run run = replay(new Phone(90, false, 320, 240, 100, 140), trueMapping(90, false), rolling, 8000, true, t -> false, 500);
        assertTrue(run.audit.rRoll() > 0.8, run.audit.toString());
        assertEquals(1, run.audit.gainRoll(), 0.15);
    }

    @Test
    void aWrongRollSignFailsTheOnDeviceCheck() {
        MotionAudit audit = new MotionAudit();
        Random random = new Random(1);
        CardQuad card = new CardQuad(new Corner(220, 100), new Corner(420, 100), new Corner(420, 380), new Corner(220, 380), 1, true);
        audit.observe(new TrackedCard(1, card, 0, 0, true), ImageMotion.NONE);
        for (int i = 0; i < 40; i++) {
            double angle = 0.01 * (random.nextDouble() - 0.5);
            double dx = 4 * (random.nextDouble() - 0.5);
            double dy = 4 * (random.nextDouble() - 0.5);
            ImageMotion predicted = point -> new Corner(point.x() + dx - angle * (point.y() - 240), point.y() + dy + angle * (point.x() - 320));
            ImageMotion actual = point -> new Corner(point.x() + dx + angle * (point.y() - 240), point.y() + dy - angle * (point.x() - 320));
            card = actual.moved(card);
            audit.observe(new TrackedCard(1, card, 0, 0, true), predicted);
        }
        assertEquals(MotionAudit.WRONG_ROLL_SIGN, audit.summary().verdict(), audit.summary().toString());
    }

    @Test
    void aStillPhoneSaysNotEnoughMotionAndNotAFailure() {
        Rates still = ms -> new double[] {0, 0, 0};
        Run run = replay(new Phone(90, false, 320, 240, 100, 140), trueMapping(90, false), still, 6000, true, t -> false, 500);
        assertEquals(MotionAudit.NOT_ENOUGH_MOTION, run.audit.verdict(), run.audit.toString());
    }

    /** The whole pipeline, past the finder: a sway keeps it waiting, and it fires once the hand calms. */
    @Test
    void captureWaitsForTheGyroscopeAndFiresInTheCalm() {
        MotionModel model = new MotionModel();
        model.setMapping(trueMapping(90, false));
        Phone phone = new Phone(90, false, 320, 240, 100, 140);
        AssistedPipeline pipeline = new AssistedPipeline(null);
        Rates rates = ms -> ms < 2500 ? new double[] {0.3 * Math.sin(ms / 100.0), 0, 0} : new double[] {0.02, 0, 0};
        byte[] luma = new byte[W * H];
        long nextFrame = 100;
        long previous = 0;
        long firedAt = -1;
        int generation = 0;
        for (long t = 10; t <= 6000 && firedAt < 0; t += GYRO_STEP_MS) {
            double[] w = rates.at(t);
            phone.turn(w[0], w[1], w[2], GYRO_STEP_MS / 1000.0);
            model.history().add(t * MS, w[0], w[1], w[2]);
            if (t < nextFrame) {
                continue;
            }
            nextFrame += FRAME_MS;
            FindResult found = new FindResult(List.of(phone.outline(new Random(t), 0.6)), 0, 0, 1);
            FocusStatus focus = generation == 0 ? FocusStatus.NONE : new FocusStatus(generation, FocusStatus.Outcome.FOCUSED, 50);
            AssistedPipeline.Outcome out = pipeline.onFrame(new AssistedPipeline.Input(found, luma, W, H, t, focus, true, 800, false,
                    model.forFrame(previous * MS, t * MS, t * MS)));
            previous = t;
            if (out.focusAction() == AssistedPipeline.FocusAction.FOCUS_ON_CARD) {
                generation = out.focusGeneration();
            }
            if (out.captureNow()) {
                firedAt = t;
                assertNotNull(out.steadyWaitMs());
                assertTrue(out.motion().speedRadS() <= BlurBudget.limitRadS(null));
            }
        }
        // The rolling window reaches back into the end of the sway, whose mean is already within the limit.
        assertTrue(firedAt >= 2500 + 800 - PhoneSteadiness.ROLLING_MS, "fired at " + firedAt);
        assertTrue(firedAt <= 2500 + 800 + 600, "fired at " + firedAt);
    }
}
