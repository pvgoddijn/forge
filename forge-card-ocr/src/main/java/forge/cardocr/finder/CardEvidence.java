package forge.cardocr.finder;

import java.util.*;

/**
 * Measures how much a fitted outline looks like a whole card rather than just any rectangle. Each
 * measure works in the card's flattened coordinates, so it doesn't depend on angle or size.
 */
final class CardEvidence {

    /** Corner radius as a share of the card's width (about 3 mm of 63 mm). */
    static final double CORNER_RADIUS = 3.0 / 63;
    /** Below this radius in pixels a corner is too small to tell round from sharp. */
    private static final double MIN_RADIUS_PIXELS = 3;
    /** Below this contrast across a corner, in luma levels, its shape can't be seen. */
    private static final double MIN_CORNER_CONTRAST = 12;
    private static final int BAND_SAMPLES = 24;
    private static final int PROFILE_ROWS = 44;
    private static final int PROFILE_COLUMNS = 12;
    /** The share of the evidence that round corners can add. */
    private static final double ROUND_BONUS = 0.3;
    private static final double MAX_REPETITION = 0.75;
    private static final int TEXTURE_COLUMNS = 6;
    private static final int TEXTURE_ROWS = 8;
    /** A cell with this spread of luma, in levels, has something printed in it. */
    private static final double TEXTURE_SPREAD = 6;

    /**
     * @param minSide the weakest side's share of samples on its fitted line
     * @param roundness mean over the visible corners, -1 for sharp to +1 for round
     * @param roundCorners how many corners look round
     * @param sharpCorners how many corners look sharp
     * @param bandSpread typical luma spread within the thin band just inside each side
     * @param continuation how far the side lines run on past the corners, 0 to 1 and lower for a card
     * @param structure strongest step between rows of the interior where a card's art box ends
     * @param texture share of interior cells that aren't flat
     * @param repetition how strongly the interior repeats itself along either axis, -1 to 1. Vents,
     *     slots and key rows repeat evenly, and a card's layout doesn't
     */
    record Features(double minSide, double meanSide, double aspectError, double roundness, int roundCorners,
            int sharpCorners, double bandSpread, double continuation, double structure,
            double texture, double repetition) {
    }

    private CardEvidence() {
    }

    /**
     * Combines the measures into 0 to 1. Weak sides, lines running on past the corners, or an empty
     * interior can each veto an outline. Corners never can: sleeves and home-cut proxies have sharp
     * or hidden ones, so round corners only raise the result.
     */
    static double score(Features f) {
        double sides = ramp(f.minSide(), 0.35, 0.65);
        double stops = ramp(-f.continuation(), -0.60, -0.35);
        double interior = ramp(f.structure(), 4, 20) * ramp(f.texture(), 0.3, 0.7);
        interior *= ramp(-f.repetition(), -MAX_REPETITION, -MAX_REPETITION * 0.8);
        return sides * stops * interior * (1 - ROUND_BONUS + ROUND_BONUS * roundBonus(f));
    }

    /** 1 with two or more round corners and none sharp, falling to 0 with no sign of round ones. */
    private static double roundBonus(Features f) {
        return Math.max(0, Math.min(1, (f.roundCorners() - f.sharpCorners()) / 2.0));
    }

    /** 0 at or below low, 1 at or above high, and linear between. */
    private static double ramp(double value, double low, double high) {
        return Math.max(0, Math.min(1, (value - low) / (high - low)));
    }

    static Features measure(LumaImage g, Corner[] c, double minSide, double meanSide) {
        Homography h = Homography.fromUnitSquare(c[0], c[1], c[2], c[3]);
        double width = (c[0].distanceTo(c[1]) + c[2].distanceTo(c[3])) / 2;
        double length = (c[0].distanceTo(c[3]) + c[1].distanceTo(c[2])) / 2;
        double aspect = length / width;
        double aspectError = Math.abs(aspect / (88.0 / 63) - 1);

        double radiusPixels = CORNER_RADIUS * width;
        int round = 0;
        int sharp = 0;
        int visible = 0;
        double sum = 0;
        if (radiusPixels >= MIN_RADIUS_PIXELS) {
            for (int i = 0; i < 4; i++) {
                double value = cornerRoundness(g, h, i);
                if (Double.isNaN(value)) {
                    continue;
                }
                visible++;
                sum += value;
                if (value > 0.2) {
                    round++;
                } else if (value < -0.2) {
                    sharp++;
                }
            }
        }
        double roundness = visible == 0 ? 0 : sum / visible;
        return new Features(minSide, meanSide, aspectError, roundness, round, sharp, bandSpread(g, h),
                continuation(g, c), structure(g, h), texture(g, h), repetition(g, h));
    }

    /**
     * Looks at the small pocket a rounded corner leaves between its arc and the sharp vertex. On a
     * card the pocket shows the background, and on a sharp rectangle it shows the same as inside.
     * Returns +1 for a pocket like the outside, -1 for one like the inside, NaN if there's too
     * little contrast to say.
     */
    private static double cornerRoundness(LumaImage g, Homography h, int corner) {
        boolean left = corner == 0 || corner == 3;
        boolean top = corner < 2;
        double ru = CORNER_RADIUS;
        double rv = CORNER_RADIUS * 63 / 88;
        double cu = left ? 0 : 1;
        double cv = top ? 0 : 1;
        double su = left ? 1 : -1;
        double sv = top ? 1 : -1;
        double pocket = mean(g, h, cu, cv, su * ru, sv * rv, 0.14, 0.2);
        double inside = mean(g, h, cu, cv, su * ru, sv * rv, 0.8, 1.2);
        double outside = mean(g, h, cu, cv, su * ru, sv * rv, -0.9, -0.5);
        if (Double.isNaN(pocket) || Double.isNaN(inside) || Double.isNaN(outside)
                || Math.abs(inside - outside) < MIN_CORNER_CONTRAST) {
            return Double.NaN;
        }
        return (Math.abs(pocket - inside) - Math.abs(pocket - outside)) / Math.abs(inside - outside);
    }

    /** Mean of two samples on the corner's diagonal, at two distances in radii. */
    private static double mean(LumaImage g, Homography h, double cu, double cv, double du, double dv,
            double near, double far) {
        float a = g.sample(h.mapX(cu + du * near, cv + dv * near), h.mapY(cu + du * near, cv + dv * near));
        float b = g.sample(h.mapX(cu + du * far, cv + dv * far), h.mapY(cu + du * far, cv + dv * far));
        return a < 0 || b < 0 ? Double.NaN : (a + b) / 2.0;
    }

    /** The second largest of the four sides' luma spreads, so one side with glare doesn't count. */
    private static double bandSpread(LumaImage g, Homography h) {
        double inset = 0.022;
        double insetV = inset * 63 / 88;
        double[] spreads = {
            lineSpread(g, h, 0.08, insetV, 0.92, insetV),
            lineSpread(g, h, 0.08, 1 - insetV, 0.92, 1 - insetV),
            lineSpread(g, h, inset, 0.08, inset, 0.92),
            lineSpread(g, h, 1 - inset, 0.08, 1 - inset, 0.92)};
        Arrays.sort(spreads);
        return spreads[2];
    }

    private static double lineSpread(LumaImage g, Homography h, double u0, double v0, double u1, double v1) {
        double sum = 0;
        double squares = 0;
        int count = 0;
        for (int i = 0; i < BAND_SAMPLES; i++) {
            double t = (i + 0.5) / BAND_SAMPLES;
            double u = u0 + (u1 - u0) * t;
            double v = v0 + (v1 - v0) * t;
            float value = g.sample(h.mapX(u, v), h.mapY(u, v));
            if (value >= 0) {
                sum += value;
                squares += value * value;
                count++;
            }
        }
        if (count < 2) {
            return 0;
        }
        double mean = sum / count;
        return Math.sqrt(Math.max(0, squares / count - mean * mean));
    }

    /**
     * A card's edges stop at its corners, while desk edges, case slots and key rows run on. Compares
     * the step across each side's line just past its ends with the step along the side itself.
     */
    private static double continuation(LumaImage g, Corner[] c) {
        double total = 0;
        for (int i = 0; i < 4; i++) {
            total += sideContinuation(g, c[i], c[(i + 1) % 4]);
        }
        return total / 4;
    }

    private static double sideContinuation(LumaImage g, Corner a, Corner b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double length = Math.hypot(dx, dy);
        double nx = -dy / length;
        double ny = dx / length;
        double reach = Math.max(2, length * 0.012);
        double along = stepAlong(g, a, dx, dy, nx, ny, reach, 0.2, 0.8);
        double before = stepAlong(g, a, dx, dy, nx, ny, reach, -0.2, -0.06);
        double after = stepAlong(g, a, dx, dy, nx, ny, reach, 1.06, 1.2);
        if (along < 1) {
            return 1;
        }
        double past = (Math.max(before, 0) + Math.max(after, 0)) / 2;
        return Math.min(1, past / along);
    }

    /** Mean absolute luma step across the line between two positions along the side. */
    private static double stepAlong(LumaImage g, Corner a, double dx, double dy, double nx, double ny,
            double reach, double t0, double t1) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < 8; i++) {
            double t = t0 + (t1 - t0) * (i + 0.5) / 8;
            double x = a.x() + dx * t;
            double y = a.y() + dy * t;
            float p = g.sample(x + nx * reach, y + ny * reach);
            float q = g.sample(x - nx * reach, y - ny * reach);
            if (p >= 0 && q >= 0) {
                sum += Math.abs(p - q);
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    /**
     * A portrait card changes abruptly from art to text about half way down. Returns the largest
     * step between neighbouring row means in the bands where this happens, either way up, in
     * luma levels.
     */
    private static double structure(LumaImage g, Homography h) {
        double[] rows = new double[PROFILE_ROWS];
        for (int r = 0; r < PROFILE_ROWS; r++) {
            double v = (r + 0.5) / PROFILE_ROWS;
            double sum = 0;
            int count = 0;
            for (int k = 0; k < PROFILE_COLUMNS; k++) {
                double u = 0.1 + 0.8 * (k + 0.5) / PROFILE_COLUMNS;
                float value = g.sample(h.mapX(u, v), h.mapY(u, v));
                if (value >= 0) {
                    sum += value;
                    count++;
                }
            }
            rows[r] = count == 0 ? Double.NaN : sum / count;
        }
        double best = 0;
        for (int r = 2; r + 2 < PROFILE_ROWS; r++) {
            double v = (r + 0.5) / PROFILE_ROWS;
            boolean inBand = (v > 0.48 && v < 0.64) || (v > 0.36 && v < 0.52);
            if (inBand) {
                double step = Math.abs(rows[r + 2] - rows[r - 2]);
                if (step > best) {
                    best = step;
                }
            }
        }
        return best;
    }

    /** A printed card has detail nearly everywhere. A panel, a screen or a blank box is mostly flat. */
    private static double texture(LumaImage g, Homography h) {
        int busy = 0;
        for (int row = 0; row < TEXTURE_ROWS; row++) {
            for (int column = 0; column < TEXTURE_COLUMNS; column++) {
                double sum = 0;
                double squares = 0;
                int count = 0;
                for (int a = 0; a < 3; a++) {
                    for (int b = 0; b < 3; b++) {
                        double u = 0.08 + 0.84 * (column + (a + 0.5) / 3) / TEXTURE_COLUMNS;
                        double v = 0.06 + 0.88 * (row + (b + 0.5) / 3) / TEXTURE_ROWS;
                        float value = g.sample(h.mapX(u, v), h.mapY(u, v));
                        if (value >= 0) {
                            sum += value;
                            squares += value * value;
                            count++;
                        }
                    }
                }
                if (count > 1) {
                    double mean = sum / count;
                    if (Math.sqrt(Math.max(0, squares / count - mean * mean)) >= TEXTURE_SPREAD) {
                        busy++;
                    }
                }
            }
        }
        return busy / (double) (TEXTURE_ROWS * TEXTURE_COLUMNS);
    }

    private static double repetition(LumaImage g, Homography h) {
        double[] rows = new double[PROFILE_ROWS];
        double[] columns = new double[PROFILE_ROWS];
        for (int a = 0; a < PROFILE_ROWS; a++) {
            double across = 0.1 + 0.8 * (a + 0.5) / PROFILE_ROWS;
            for (int b = 0; b < PROFILE_COLUMNS; b++) {
                double along = 0.1 + 0.8 * (b + 0.5) / PROFILE_COLUMNS;
                rows[a] += Math.max(0, g.sample(h.mapX(along, across), h.mapY(along, across)));
                columns[a] += Math.max(0, g.sample(h.mapX(across, along), h.mapY(across, along)));
            }
        }
        return Math.max(strongestRepeat(rows), strongestRepeat(columns));
    }

    /** The highest correlation of a profile with itself shifted, past its first dip. */
    private static double strongestRepeat(double[] values) {
        int n = values.length;
        double mean = 0;
        for (double value : values) {
            mean += value / n;
        }
        double energy = 0;
        for (double value : values) {
            energy += (value - mean) * (value - mean);
        }
        if (energy < 1) {
            return 0;
        }
        double best = -1;
        boolean dipped = false;
        for (int lag = 1; lag <= n / 2; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < n; i++) {
                sum += (values[i] - mean) * (values[i + lag] - mean);
            }
            double correlation = sum / energy * n / (n - lag);
            dipped |= correlation < 0;
            if (dipped && correlation > best) {
                best = correlation;
            }
        }
        return best;
    }
}
