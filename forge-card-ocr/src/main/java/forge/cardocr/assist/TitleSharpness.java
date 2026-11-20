package forge.cardocr.assist;

import forge.cardocr.finder.*;

/**
 * How sharp and how bright a tracked card's title looks in a low-res analysis frame: the variance of the
 * Laplacian (a standard focus measure, since blur removes the second derivative of fine detail) and the mean luma,
 * over the band where the title sits. The finder can't tell which way up a card is, so both short ends are measured
 * and the sharper one is kept: it is the title or the text box, and either blurs as the title does.
 *
 * <p>Only the frame's own pixels are used, with no resampling, which would itself blur. Values compare between frames
 * of a card at a similar size in the frame, not across very different card sizes.
 */
public final class TitleSharpness {

    /** The title band starts this far into the card's length, past the border. As {@link GlareDetector}. */
    static final double BAND_START = 0.03;
    /** ...and ends here, as {@link GlareDetector#BAND_LENGTH}. */
    static final double BAND_END = GlareDetector.BAND_LENGTH;
    /** Leaves out the card's side borders and any background a loose outline takes in. */
    static final double SIDE_MARGIN = 0.05;
    /** With fewer pixels of a band in the frame than this, it isn't measured. */
    static final int MIN_PIXELS = 64;

    /**
     * One band's measures.
     *
     * @param laplacianVariance variance of the 4-neighbour Laplacian, in squared levels of 255
     * @param meanLuma 0 to 255
     * @param lumaVariance the variance of the luma itself, the band's contrast
     * @param pixels how many pixels were measured; 0 when the band was out of the frame
     */
    public record Reading(double laplacianVariance, double meanLuma, double lumaVariance, int pixels) {

        static final Reading NONE = new Reading(0, 0, 0, 0);

        public boolean measured() {
            return pixels >= MIN_PIXELS;
        }
    }

    private TitleSharpness() {
    }

    /** @param card the outline in the frame's pixels, short sides c0-c1 and c3-c2 */
    public static Reading measure(byte[] luma, int width, int height, CardQuad card) {
        if (width <= 0 || height <= 0 || luma.length < width * height) {
            throw new IllegalArgumentException("luma has " + luma.length + " bytes for " + width + "x" + height);
        }
        Homography h = Homography.fromUnitSquare(card);
        Reading first = band(luma, width, height, h, BAND_START, BAND_END);
        Reading second = band(luma, width, height, h, 1 - BAND_END, 1 - BAND_START);
        if (!second.measured()) {
            return first;
        }
        return !first.measured() || second.laplacianVariance() > first.laplacianVariance() ? second : first;
    }

    private static Reading band(byte[] luma, int width, int height, Homography h, double v0, double v1) {
        double[][] polygon = {
                {h.mapX(SIDE_MARGIN, v0), h.mapY(SIDE_MARGIN, v0)},
                {h.mapX(1 - SIDE_MARGIN, v0), h.mapY(1 - SIDE_MARGIN, v0)},
                {h.mapX(1 - SIDE_MARGIN, v1), h.mapY(1 - SIDE_MARGIN, v1)},
                {h.mapX(SIDE_MARGIN, v1), h.mapY(SIDE_MARGIN, v1)}};
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] p : polygon) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        // The Laplacian needs a neighbour on every side, so the frame's outermost pixels are left out.
        int x0 = Math.max(1, (int) Math.floor(minX));
        int y0 = Math.max(1, (int) Math.floor(minY));
        int x1 = Math.min(width - 2, (int) Math.ceil(maxX));
        int y1 = Math.min(height - 2, (int) Math.ceil(maxY));
        int count = 0;
        double lapSum = 0;
        double lapSquares = 0;
        double lumaSum = 0;
        double lumaSquares = 0;
        for (int y = y0; y <= y1; y++) {
            int row = y * width;
            for (int x = x0; x <= x1; x++) {
                if (!inside(polygon, x + 0.5, y + 0.5)) {
                    continue;
                }
                int i = row + x;
                int centre = luma[i] & 0xFF;
                int laplacian = (luma[i - 1] & 0xFF) + (luma[i + 1] & 0xFF) + (luma[i - width] & 0xFF)
                        + (luma[i + width] & 0xFF) - 4 * centre;
                count++;
                lapSum += laplacian;
                lapSquares += (double) laplacian * laplacian;
                lumaSum += centre;
                lumaSquares += (double) centre * centre;
            }
        }
        if (count < MIN_PIXELS) {
            return new Reading(0, count == 0 ? 0 : lumaSum / count, 0, count);
        }
        double lapMean = lapSum / count;
        double lumaMean = lumaSum / count;
        return new Reading(lapSquares / count - lapMean * lapMean, lumaMean, lumaSquares / count - lumaMean * lumaMean, count);
    }

    /** Whether a point is inside a convex polygon, whichever way round its corners go. */
    private static boolean inside(double[][] polygon, double x, double y) {
        boolean anyPositive = false;
        boolean anyNegative = false;
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] b = polygon[(i + 1) % polygon.length];
            double cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            anyPositive |= cross > 0;
            anyNegative |= cross < 0;
        }
        return !(anyPositive && anyNegative);
    }
}
