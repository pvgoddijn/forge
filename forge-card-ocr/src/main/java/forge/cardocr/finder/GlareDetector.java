package forge.cardocr.finder;

/**
 * Measures blown-out pixels in a card's title band, using the outline's flattened coordinates. The
 * finder can't tell which way up a card is, so both short ends are measured.
 *
 * <p>All thresholds are guesses until tried on real glare. A pale title bar on a bright exposure
 * may read as glare.
 */
public final class GlareDetector {

    /** A Y value at or above this counts as blown out. Guess. */
    public static final int BLOWN_LUMA = 245;
    /** Warn when this share of a band's pixels is blown out. Guess. */
    public static final double WARN_SHARE = 0.10;
    /** The title band covers this share of the card's length. */
    public static final double BAND_LENGTH = 0.12;
    /** Skips the card's border and any background caught by a slightly loose outline. */
    private static final double EDGE_MARGIN = 0.03;
    private static final int COLUMNS = 48;
    private static final int ROWS = 8;
    /** With less of a band in the frame than this, it can't be judged. */
    private static final double MIN_VISIBLE = 0.5;

    /** @param worstShare the larger of the two bands' blown-out shares, 0 to 1 */
    public record Reading(double worstShare) {

        public boolean warns() {
            return worstShare >= WARN_SHARE;
        }
    }

    private GlareDetector() {
    }

    public static Reading measure(byte[] luma, int width, int height, CardQuad quad) {
        LumaImage.checkSize(luma.length, width, height);
        Homography h = Homography.fromUnitSquare(quad);
        double first = bandShare(luma, width, height, h, EDGE_MARGIN);
        double second = bandShare(luma, width, height, h, 1 - BAND_LENGTH);
        return new Reading(Math.max(first, second));
    }

    private static double bandShare(byte[] luma, int width, int height, Homography h, double vStart) {
        double vLength = BAND_LENGTH - EDGE_MARGIN;
        int visible = 0;
        int blown = 0;
        for (int row = 0; row < ROWS; row++) {
            double v = vStart + vLength * (row + 0.5) / ROWS;
            for (int column = 0; column < COLUMNS; column++) {
                double u = EDGE_MARGIN + (1 - 2 * EDGE_MARGIN) * (column + 0.5) / COLUMNS;
                int x = (int) Math.floor(h.mapX(u, v));
                int y = (int) Math.floor(h.mapY(u, v));
                if (x >= 0 && y >= 0 && x < width && y < height) {
                    visible++;
                    if ((luma[y * width + x] & 0xFF) >= BLOWN_LUMA) {
                        blown++;
                    }
                }
            }
        }
        return visible >= MIN_VISIBLE * COLUMNS * ROWS ? (double) blown / visible : 0;
    }
}
