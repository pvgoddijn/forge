package forge.cardocr.finder;

/**
 * A coarse flattened thumbnail of a card, for telling whether the card in an outline has changed.
 * It doesn't depend on where the card lies in the frame. The mean is removed, so an overall
 * exposure change has little effect.
 */
public final class CardSignature {

    private static final int COLUMNS = 16;
    private static final int ROWS = 22;
    private static final double INSET = 0.05;

    private CardSignature() {
    }

    /** Returns null if too much of the card is outside the frame to compare it. */
    public static float[] of(byte[] luma, int width, int height, CardQuad quad) {
        LumaImage.checkSize(luma.length, width, height);
        Homography h = Homography.fromUnitSquare(quad);
        float[] out = new float[COLUMNS * ROWS];
        double total = 0;
        for (int row = 0; row < ROWS; row++) {
            double v = INSET + (1 - 2 * INSET) * (row + 0.5) / ROWS;
            for (int column = 0; column < COLUMNS; column++) {
                double u = INSET + (1 - 2 * INSET) * (column + 0.5) / COLUMNS;
                int x = (int) Math.floor(h.mapX(u, v));
                int y = (int) Math.floor(h.mapY(u, v));
                if (x < 0 || y < 0 || x >= width || y >= height) {
                    return null;
                }
                float value = luma[y * width + x] & 0xFF;
                out[row * COLUMNS + column] = value;
                total += value;
            }
        }
        float mean = (float) (total / out.length);
        for (int i = 0; i < out.length; i++) {
            out[i] -= mean;
        }
        return out;
    }

    /** Mean absolute difference in luma levels, allowing the 180 degree turn outlines can't resolve. */
    public static double distance(float[] a, float[] b) {
        double same = 0;
        double turned = 0;
        for (int i = 0; i < a.length; i++) {
            same += Math.abs(a[i] - b[i]);
            turned += Math.abs(a[i] - b[b.length - 1 - i]);
        }
        return Math.min(same, turned) / a.length;
    }
}
