package forge.cardocr.assist;

import forge.cardocr.finder.*;

/**
 * Compares what the gyroscope predicted with how the fitted outline really moved between consecutive
 * finder frames. This is how a wrong sign or swapped axis shows up on a real phone, since neither
 * can be proved off-device. It also measures outline jitter with and without compensation.
 */
public final class MotionAudit {

    /** One frame's comparison, in analysis pixels and radians. */
    public record Step(double predictedX, double predictedY, double observedX, double observedY,
            double predictedRoll, double observedRoll, double residual, double uncompensated) {
    }

    /**
     * Correlations run from -1 to 1. A gain is observed over predicted, and should be about 1. OIS and
     * moving the phone sideways can move it.
     *
     * @param samples steps behind the correlations, the latest {@link #WINDOW} at most
     * @param rX predicted x against observed x, positive when the sign is right
     * @param rY predicted y against observed y
     * @param crossXY predicted x against observed y, near 0 unless axes are swapped
     * @param crossYX predicted y against observed x
     * @param verdict {@link #OK}, {@link #WRONG_SIGN}, {@link #AXES_SWAPPED}, {@link #WRONG_ROLL_SIGN},
     *     {@link #UNCLEAR} or {@link #NOT_ENOUGH_MOTION}
     * @param jitterMeanPx mean worst-corner gap between the moved previous outline and this one
     * @param jitterP95Px its 95th percentile
     * @param uncompensatedMeanPx the same gap without moving the previous outline
     */
    public record Summary(int samples, double rX, double rY, double crossXY, double crossYX,
            double gainX, double gainY, double rRoll, double gainRoll, String verdict,
            double jitterMeanPx, double jitterP95Px, double uncompensatedMeanPx) {
    }

    public static final String OK = "OK";
    public static final String WRONG_SIGN = "WRONG_SIGN";
    public static final String AXES_SWAPPED = "AXES_SWAPPED";
    public static final String WRONG_ROLL_SIGN = "WRONG_ROLL_SIGN";
    public static final String UNCLEAR = "UNCLEAR";
    public static final String NOT_ENOUGH_MOTION = "NOT_ENOUGH_MOTION";

    static final int WINDOW = 256;
    private static final int MIN_SAMPLES = 30;
    /** Predicted shifts must vary by this many pixels for an axis to say anything. */
    private static final double MIN_SIGNAL_PX = 0.3;
    private static final double MIN_SIGNAL_RAD = 0.001;
    private static final double CLEAR_CORRELATION = 0.3;

    private final double[][] series = new double[6][WINDOW];
    private final double[] residuals = new double[WINDOW];
    private final double[] uncompensated = new double[WINDOW];
    private int total;
    private CardQuad previous;
    private int previousId;

    /** Feed every frame, with the motion given to the tracker. Null unless this and the last frame saw the card. */
    public Step observe(TrackedCard card, ImageMotion motion) {
        CardQuad seen = card == null ? null : card.seenQuad();
        Step step = null;
        if (seen != null && previous != null && card.id() == previousId) {
            step = compare(previous, seen, motion);
            record(step);
        }
        previous = seen;
        previousId = card == null ? 0 : card.id();
        return step;
    }

    private static Step compare(CardQuad before, CardQuad after, ImageMotion motion) {
        CardQuad predicted = motion.moved(before);
        Corner from = Quads.centre(before);
        Corner to = Quads.centre(predicted);
        Corner seen = Quads.centre(after);
        return new Step(to.x() - from.x(), to.y() - from.y(), seen.x() - from.x(), seen.y() - from.y(),
                turn(before, predicted), turn(before, after), Quads.distance(predicted, after), Quads.distance(before, after));
    }

    /** The angle between two outlines' long sides. */
    private static double turn(CardQuad a, CardQuad b) {
        double angle = direction(b) - direction(a);
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    private static double direction(CardQuad q) {
        return Math.atan2(q.c3().y() - q.c0().y() + q.c2().y() - q.c1().y(), q.c3().x() - q.c0().x() + q.c2().x() - q.c1().x());
    }

    private void record(Step step) {
        int i = total % WINDOW;
        series[0][i] = step.predictedX();
        series[1][i] = step.predictedY();
        series[2][i] = step.observedX();
        series[3][i] = step.observedY();
        series[4][i] = step.predictedRoll();
        series[5][i] = step.observedRoll();
        residuals[i] = step.residual();
        uncompensated[i] = step.uncompensated();
        total++;
    }

    public Summary summary() {
        int n = Math.min(total, WINDOW);
        double rX = correlation(series[0], series[2], n);
        double rY = correlation(series[1], series[3], n);
        double crossXY = correlation(series[0], series[3], n);
        double crossYX = correlation(series[1], series[2], n);
        double rRoll = correlation(series[4], series[5], n);
        return new Summary(n, rX, rY, crossXY, crossYX, gain(series[0], series[2], n), gain(series[1], series[3], n),
                rRoll, gain(series[4], series[5], n), verdict(n, rX, rY, crossXY, crossYX, rRoll),
                mean(residuals, n), percentile95(residuals, n), mean(uncompensated, n));
    }

    private String verdict(int n, double rX, double rY, double crossXY, double crossYX, double rRoll) {
        if (n < MIN_SAMPLES) {
            return NOT_ENOUGH_MOTION;
        }
        boolean xSpeaks = deviation(series[0], n) >= MIN_SIGNAL_PX;
        boolean ySpeaks = deviation(series[1], n) >= MIN_SIGNAL_PX;
        boolean rollSpeaks = deviation(series[4], n) >= MIN_SIGNAL_RAD;
        if (!xSpeaks && !ySpeaks) {
            return NOT_ENOUGH_MOTION;
        }
        if ((xSpeaks && Math.abs(crossXY) > Math.abs(rX) + 0.2) || (ySpeaks && Math.abs(crossYX) > Math.abs(rY) + 0.2)) {
            return AXES_SWAPPED;
        }
        if ((xSpeaks && rX < -CLEAR_CORRELATION) || (ySpeaks && rY < -CLEAR_CORRELATION)) {
            return WRONG_SIGN;
        }
        if (rollSpeaks && rRoll < -CLEAR_CORRELATION) {
            return WRONG_ROLL_SIGN;
        }
        return (!xSpeaks || rX > CLEAR_CORRELATION) && (!ySpeaks || rY > CLEAR_CORRELATION) ? OK : UNCLEAR;
    }

    private static double mean(double[] values, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += values[i];
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double deviation(double[] values, int n) {
        double mean = mean(values, n);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (values[i] - mean) * (values[i] - mean);
        }
        return n == 0 ? 0 : Math.sqrt(sum / n);
    }

    /** NaN when either side doesn't vary. */
    private static double correlation(double[] a, double[] b, int n) {
        double meanA = mean(a, n);
        double meanB = mean(b, n);
        double sumAB = 0;
        double sumAA = 0;
        double sumBB = 0;
        for (int i = 0; i < n; i++) {
            sumAB += (a[i] - meanA) * (b[i] - meanB);
            sumAA += (a[i] - meanA) * (a[i] - meanA);
            sumBB += (b[i] - meanB) * (b[i] - meanB);
        }
        return sumAA == 0 || sumBB == 0 ? Double.NaN : sumAB / Math.sqrt(sumAA * sumBB);
    }

    /** The best-fitting observed = gain * predicted. NaN with nothing predicted. */
    private static double gain(double[] predicted, double[] observed, int n) {
        double sumPO = 0;
        double sumPP = 0;
        for (int i = 0; i < n; i++) {
            sumPO += predicted[i] * observed[i];
            sumPP += predicted[i] * predicted[i];
        }
        return sumPP == 0 ? Double.NaN : sumPO / sumPP;
    }

    private static double percentile95(double[] values, int n) {
        if (n == 0) {
            return 0;
        }
        double[] sorted = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(sorted);
        return sorted[(int) Math.ceil(0.95 * n) - 1];
    }
}
