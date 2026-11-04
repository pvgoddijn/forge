package forge.cardocr.finder;

import java.util.*;

/**
 * Turns a rough rectangle into an exact outline: fits a line to each side, intersects the lines to
 * get corners, rejects shapes that aren't card-like, and scores the fit with a rounded-corner check.
 */
final class EdgeRefiner {

    /** Corner radius as a share of the card's width (about 3 mm of 63 mm). */
    private static final double CORNER_RADIUS = 0.048;
    private static final double CARD_ASPECT = 88.0 / 63.0;
    private static final int SAMPLE_INTERVALS = 60;
    private static final int PEAKS_PER_SAMPLE = 3;
    private static final int PEAK_SUPPRESSION = 4;
    private static final double PEAK_FLOOR = 0.8;
    private static final double MIN_GRADIENT = 8;
    private static final int MIN_POINTS = 12;
    private static final int MIN_INLIERS = 14;
    private static final int LINE_TRIALS = 400;
    private static final double MAX_LINE_ANGLE_COS = Math.cos(Math.toRadians(9));
    /** A line with this share of the best line's support may win by being further out. */
    private static final double OUTER_LINE_SUPPORT = 0.7;
    private static final double MAX_CORNER_SHIFT = 0.18;
    private static final double ASPECT_TOLERANCE = 0.12;
    private static final double ASPECT_PENALTY = 0.25;
    private static final double MAX_OPPOSITE_SIDE_RATIO = 1.2;
    /** An outline with no card evidence keeps this share of its score. */
    private static final double EVIDENCE_FLOOR = 0.3;

    private EdgeRefiner() {
    }

    /**
     * Fits a rough quad to the image. The quad is in the image's coordinates, as is the result.
     *
     * @return the refined quad, or null if an edge wasn't found or the result isn't card-shaped
     */
    static CardQuad refine(LumaImage image, CardQuad rough) {
        Refined refined = refineDetailed(image, rough);
        return refined == null ? null : refined.quad();
    }

    /** As {@link #refine}, also giving the measurements behind the score. */
    static Refined refineDetailed(LumaImage image, CardQuad rough) {
        Corner[] q = {rough.c0(), rough.c1(), rough.c2(), rough.c3()};
        double cardWidth = q[0].distanceTo(q[1]);
        double searchRadius = Math.max(8, cardWidth * 0.08);
        double centreX = (q[0].x() + q[2].x()) / 2;
        double centreY = (q[0].y() + q[2].y()) / 2;

        Line[] lines = new Line[4];
        double inliers = 0;
        double weakestSide = 1;
        for (int i = 0; i < 4; i++) {
            Corner a = q[i];
            Corner b = q[(i + 1) % 4];
            lines[i] = fitEdge(image, a, b, (a.x() + b.x()) / 2 - centreX,
                    (a.y() + b.y()) / 2 - centreY, searchRadius);
            if (lines[i] == null) {
                return null;
            }
            inliers += lines[i].support / 4;
            weakestSide = Math.min(weakestSide, lines[i].support);
        }

        Corner[] c = new Corner[4];
        for (int i = 0; i < 4; i++) {
            c[i] = meet(lines[(i + 3) % 4], lines[i]);
            if (c[i] == null || c[i].distanceTo(q[i]) > cardWidth * MAX_CORNER_SHIFT) {
                return null;
            }
        }
        if (!isCardShaped(c)) {
            return null;
        }
        double arc = roundedCornerScore(image, Homography.fromUnitSquare(c[0], c[1], c[2], c[3]));
        double score = (0.4 * rough.score() + 0.6 * inliers * (0.7 + 0.3 * arc)) * aspectFactor(c);
        CardEvidence.Features features = CardEvidence.measure(image, c, weakestSide, inliers);
        double evidence = CardEvidence.score(features);
        return new Refined(new CardQuad(c[0], c[1], c[2], c[3], score * (EVIDENCE_FLOOR + (1 - EVIDENCE_FLOOR) * evidence),
                true, evidence), features);
    }

    /**
     * Scores down outlines near the limit of the aspect tolerance. Cutting off the title band or
     * stopping at the printed frame gives a good fit with a slightly wrong shape, which perspective
     * alone rarely produces.
     */
    private static double aspectFactor(Corner[] c) {
        double aspect = (c[3].distanceTo(c[0]) + c[1].distanceTo(c[2]))
                / (c[0].distanceTo(c[1]) + c[2].distanceTo(c[3]));
        double deviation = Math.abs(aspect / CARD_ASPECT - 1) / ASPECT_TOLERANCE;
        return 1 - ASPECT_PENALTY * deviation;
    }

    private static boolean isCardShaped(Corner[] c) {
        double top = c[0].distanceTo(c[1]);
        double right = c[1].distanceTo(c[2]);
        double bottom = c[2].distanceTo(c[3]);
        double left = c[3].distanceTo(c[0]);
        double aspect = (left + right) / (top + bottom);
        return aspect >= CARD_ASPECT * (1 - ASPECT_TOLERANCE)
                && aspect <= CARD_ASPECT * (1 + ASPECT_TOLERANCE)
                && Math.max(top, bottom) / Math.min(top, bottom) <= MAX_OPPOSITE_SIDE_RATIO
                && Math.max(left, right) / Math.min(left, right) <= MAX_OPPOSITE_SIDE_RATIO;
    }

    /**
     * Fits a line near the segment a-b. Samples strong gradients across the segment, then among
     * lines through them prefers the outermost well-supported one: the inner lines are the card's
     * printed frame.
     *
     * @param outX direction pointing away from the card's centre
     * @param outY direction pointing away from the card's centre
     * @return the line, or null if there is too little support
     */
    static Line fitEdge(LumaImage g, Corner a, Corner b, double outX, double outY, double radius) {
        double length = a.distanceTo(b);
        double tx = (b.x() - a.x()) / length;
        double ty = (b.y() - a.y()) / length;
        double nx = -ty;
        double ny = tx;
        if (nx * outX + ny * outY < 0) {
            nx = -nx;
            ny = -ny;
        }
        int maxPoints = (SAMPLE_INTERVALS + 1) * PEAKS_PER_SAMPLE;
        double[] px = new double[maxPoints];
        double[] py = new double[maxPoints];
        int count = collectEdgePoints(g, a, b, tx, ty, nx, ny, (int) radius, px, py);
        if (count < MIN_POINTS) {
            return null;
        }

        double tolerance = Math.max(1.2, length * 0.004);
        LineTrial pick = pickLine(a, nx, ny, tx, ty, length, tolerance, px, py, count);
        if (pick == null) {
            return null;
        }
        return leastSquaresLine(pick, tolerance, px, py, count);
    }

    private static int collectEdgePoints(LumaImage g, Corner a, Corner b, double tx, double ty,
            double nx, double ny, int radius, double[] px, double[] py) {
        double[] gradient = new double[2 * radius + 1];
        int count = 0;
        for (int i = 0; i <= SAMPLE_INTERVALS; i++) {
            // Keep clear of the rounded corners at both ends.
            double t = 0.10 + 0.80 * i / SAMPLE_INTERVALS;
            double cx = a.x() + (b.x() - a.x()) * t;
            double cy = a.y() + (b.y() - a.y()) * t;
            gradientsAcross(g, cx, cy, tx, ty, nx, ny, radius, gradient);
            for (int peak = 0; peak < PEAKS_PER_SAMPLE; peak++) {
                int best = -1;
                for (int r = 0; r < gradient.length; r++) {
                    if (gradient[r] > MIN_GRADIENT && (best < 0 || gradient[r] > gradient[best])) {
                        best = r;
                    }
                }
                if (best < 0) {
                    break;
                }
                double offset = peakCentre(gradient, best) - radius;
                px[count] = cx + nx * offset;
                py[count] = cy + ny * offset;
                count++;
                int from = Math.max(0, best - PEAK_SUPPRESSION);
                int to = Math.min(gradient.length - 1, best + PEAK_SUPPRESSION);
                for (int r = from; r <= to; r++) {
                    gradient[r] = 0;
                }
            }
        }
        return count;
    }

    /**
     * The middle of the gradient peak around an index. A sharp step gives a flat-topped peak as
     * wide as the sampling window, so the highest index alone would be off by up to 2 pixels, which
     * matters at preview resolution.
     */
    private static double peakCentre(double[] gradient, int peak) {
        double floor = gradient[peak] * PEAK_FLOOR;
        int from = peak;
        while (from > 0 && gradient[from - 1] >= floor) {
            from--;
        }
        int to = peak;
        while (to < gradient.length - 1 && gradient[to + 1] >= floor) {
            to++;
        }
        double weight = 0;
        double sum = 0;
        for (int r = from; r <= to; r++) {
            weight += gradient[r];
            sum += gradient[r] * r;
        }
        return sum / weight;
    }

    private static void gradientsAcross(LumaImage g, double cx, double cy, double tx, double ty,
            double nx, double ny, int radius, double[] gradient) {
        Arrays.fill(gradient, 0);
        for (int r = -radius + 2; r <= radius - 2; r++) {
            double outer = 0;
            double inner = 0;
            boolean outside = false;
            for (int d = -2; d <= 2 && !outside; d += 2) {
                float v1 = g.sample(cx + nx * (r + 2) + tx * d, cy + ny * (r + 2) + ty * d);
                float v0 = g.sample(cx + nx * (r - 2) + tx * d, cy + ny * (r - 2) + ty * d);
                outside = v1 < 0 || v0 < 0;
                outer += v1;
                inner += v0;
            }
            gradient[r + radius] = outside ? 0 : Math.abs(outer - inner) / 3;
        }
    }

    /** Random two-point lines, seeded so that results are repeatable. */
    private static LineTrial pickLine(Corner a, double nx, double ny, double tx, double ty,
            double length, double tolerance, double[] px, double[] py, int count) {
        Random random = new Random(12345);
        LineTrial[] trials = new LineTrial[LINE_TRIALS];
        int trialCount = 0;
        int bestSupport = 0;
        for (int trial = 0; trial < LINE_TRIALS; trial++) {
            int i = random.nextInt(count);
            int j = random.nextInt(count);
            double dx = px[j] - px[i];
            double dy = py[j] - py[i];
            double d = Math.hypot(dx, dy);
            if (d < length * 0.25) {
                continue;
            }
            dx /= d;
            dy /= d;
            if (Math.abs(dx * tx + dy * ty) < MAX_LINE_ANGLE_COS) {
                continue;
            }
            int support = 0;
            for (int k = 0; k < count; k++) {
                if (Math.abs((px[k] - px[i]) * dy - (py[k] - py[i]) * dx) < tolerance) {
                    support++;
                }
            }
            double outward = (px[i] - a.x()) * nx + (py[i] - a.y()) * ny;
            trials[trialCount++] = new LineTrial(support, outward, px[i], py[i], dx, dy);
            bestSupport = Math.max(bestSupport, support);
        }
        if (bestSupport < MIN_INLIERS) {
            return null;
        }
        LineTrial pick = null;
        for (int i = 0; i < trialCount; i++) {
            LineTrial t = trials[i];
            if (t.support >= OUTER_LINE_SUPPORT * bestSupport
                    && (pick == null || t.outward > pick.outward)) {
                pick = t;
            }
        }
        return pick;
    }

    private static Line leastSquaresLine(LineTrial pick, double tolerance, double[] px, double[] py,
            int count) {
        double mx = 0;
        double my = 0;
        int n = 0;
        for (int k = 0; k < count; k++) {
            if (Math.abs((px[k] - pick.x) * pick.dy - (py[k] - pick.y) * pick.dx) < tolerance) {
                mx += px[k];
                my += py[k];
                n++;
            }
        }
        mx /= n;
        my /= n;
        double xx = 0;
        double yy = 0;
        double xy = 0;
        for (int k = 0; k < count; k++) {
            if (Math.abs((px[k] - pick.x) * pick.dy - (py[k] - pick.y) * pick.dx) < tolerance) {
                xx += (px[k] - mx) * (px[k] - mx);
                yy += (py[k] - my) * (py[k] - my);
                xy += (px[k] - mx) * (py[k] - my);
            }
        }
        double theta = 0.5 * Math.atan2(2 * xy, xx - yy);
        return new Line(mx, my, Math.cos(theta), Math.sin(theta),
                n / (double) (SAMPLE_INTERVALS + 1));
    }

    /** Where two lines cross, or null if they're too close to parallel. */
    static Corner meet(Line a, Line b) {
        double det = a.dx * b.dy - a.dy * b.dx;
        if (Math.abs(det) < 0.3) {
            return null;
        }
        double t = ((b.x - a.x) * b.dy - (b.y - a.y) * b.dx) / det;
        return new Corner(a.x + t * a.dx, a.y + t * a.dy);
    }

    /**
     * A real card's outline follows the arc at each corner and has no edge at the sharp vertex.
     * Returns 0 to 1, higher when that holds.
     */
    private static double roundedCornerScore(LumaImage g, Homography h) {
        double arcGradient = 0;
        double vertexGradient = 0;
        int arcSamples = 0;
        double ru = CORNER_RADIUS;
        double rv = CORNER_RADIUS * 63 / 88;
        for (int i = 0; i < 4; i++) {
            boolean left = i == 0 || i == 3;
            boolean top = i < 2;
            double su = left ? 1 : -1;
            double sv = top ? 1 : -1;
            double cu = left ? 0 : 1;
            double cv = top ? 0 : 1;
            for (int a = 1; a <= 3; a++) {
                double theta = a * Math.PI / 8;
                double ou = Math.cos(theta);
                double ov = Math.sin(theta);
                arcGradient += Math.abs(
                        sample(g, h, cu + su * ru * (1 - ou * 0.6), cv + sv * rv * (1 - ov * 0.6))
                        - sample(g, h, cu + su * ru * (1 - ou * 1.4), cv + sv * rv * (1 - ov * 1.4)));
                arcSamples++;
            }
            vertexGradient += Math.abs(
                    sample(g, h, cu + su * ru * 0.1, cv + sv * rv * 0.1)
                    - sample(g, h, cu - su * ru * 0.25, cv - sv * rv * 0.25));
        }
        return Math.min(1, (arcGradient / arcSamples) / Math.max(6, vertexGradient / 4 + 6));
    }

    private static double sample(LumaImage g, Homography h, double u, double v) {
        float value = g.sample(h.mapX(u, v), h.mapY(u, v));
        return value < 0 ? 0 : value;
    }

    /** A fitted outline and the measurements of it. */
    record Refined(CardQuad quad, CardEvidence.Features features) {
    }

    /** A point, a unit direction, and the share of samples supporting it. */
    record Line(double x, double y, double dx, double dy, double support) {
    }

    private record LineTrial(int support, double outward, double x, double y, double dx, double dy) {
    }
}
