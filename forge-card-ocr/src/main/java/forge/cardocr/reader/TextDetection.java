package forge.cardocr.reader;

import java.util.*;

/** Turns the detection model's probability map into oriented text boxes. Pure geometry. */
public final class TextDetection {

    private static final float PIXEL_THRESHOLD = 0.3f;
    private static final double MIN_REGION_MEAN = 0.5;
    private static final int MIN_REGION_PIXELS = 30;
    private static final double MIN_THICKNESS = 4;
    private static final double MIN_ELONGATION = 1.8;
    /** The model marks shrunken text regions, and this is the standard factor for growing them back. */
    private static final double UNCLIP = 1.5;
    private static final int GRID = 32;
    private static final int MIN_INPUT_SIDE = 32;

    /** Size to resize to for the model. */
    public record InputSize(int width, int height) {
    }

    private TextDetection() {
    }

    /** No bigger than the limit, unless rounding says so, in multiples of the model's grid. */
    public static InputSize inputSize(int width, int height, int longSideLimit) {
        double scale = Math.min(1.0, (double) longSideLimit / Math.max(width, height));
        return new InputSize(Math.max(MIN_INPUT_SIDE, (int) Math.round(width * scale / GRID) * GRID),
                Math.max(MIN_INPUT_SIDE, (int) Math.round(height * scale / GRID) * GRID));
    }

    /**
     * @param probability the model's output, row by row
     * @param scaleX image pixels per map pixel across
     * @param scaleY image pixels per map pixel down
     * @return boxes in image coordinates
     */
    public static List<TextBox> boxes(float[] probability, int width, int height, double scaleX, double scaleY) {
        boolean[] seen = new boolean[probability.length];
        int[] region = new int[probability.length];
        List<TextBox> out = new ArrayList<>();
        for (int start = 0; start < probability.length; start++) {
            if (seen[start] || probability[start] < PIXEL_THRESHOLD) {
                continue;
            }
            int count = flood(probability, width, start, seen, region);
            double sum = 0;
            for (int i = 0; i < count; i++) {
                sum += probability[region[i]];
            }
            if (count >= MIN_REGION_PIXELS && sum / count >= MIN_REGION_MEAN) {
                TextBox box = fit(region, count, width, scaleX, scaleY);
                if (box != null) {
                    out.add(box);
                }
            }
        }
        return out;
    }

    /** Collects the 4-connected region from start into the array, and returns its size. */
    private static int flood(float[] p, int width, int start, boolean[] seen, int[] region) {
        int head = 0;
        int tail = 0;
        region[tail++] = start;
        seen[start] = true;
        while (head < tail) {
            int c = region[head++];
            int x = c % width;
            if (x > 0) {
                tail = visit(p, c - 1, seen, region, tail);
            }
            if (x < width - 1) {
                tail = visit(p, c + 1, seen, region, tail);
            }
            if (c >= width) {
                tail = visit(p, c - width, seen, region, tail);
            }
            if (c + width < p.length) {
                tail = visit(p, c + width, seen, region, tail);
            }
        }
        return tail;
    }

    private static int visit(float[] p, int i, boolean[] seen, int[] region, int tail) {
        if (!seen[i] && p[i] >= PIXEL_THRESHOLD) {
            seen[i] = true;
            region[tail++] = i;
            return tail;
        }
        return tail;
    }

    /** The region's principal axis gives the direction. Null if it isn't line-shaped. */
    private static TextBox fit(int[] region, int count, int width, double sx, double sy) {
        double mx = 0;
        double my = 0;
        for (int i = 0; i < count; i++) {
            mx += region[i] % width * sx;
            my += region[i] / width * sy;
        }
        mx /= count;
        my /= count;
        double xx = 0;
        double yy = 0;
        double xy = 0;
        for (int i = 0; i < count; i++) {
            double dx = region[i] % width * sx - mx;
            double dy = region[i] / width * sy - my;
            xx += dx * dx;
            yy += dy * dy;
            xy += dx * dy;
        }
        double angle = .5 * Math.atan2(2 * xy, xx - yy);
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double dx = region[i] % width * sx - mx;
            double dy = region[i] / width * sy - my;
            double along = dx * ux + dy * uy;
            double across = -dx * uy + dy * ux;
            lo = Math.min(lo, along);
            hi = Math.max(hi, along);
            low = Math.min(low, across);
            high = Math.max(high, across);
        }
        double length = hi - lo;
        double thickness = high - low;
        if (thickness < MIN_THICKNESS || length / thickness < MIN_ELONGATION) {
            return null;
        }
        double grow = length * thickness * UNCLIP / (2 * (length + thickness));
        return new TextBox(mx, my, ux, uy, lo - grow, hi + grow, low - grow, high + grow);
    }
}
