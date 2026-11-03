package forge.cardocr.finder;

/**
 * Laplacian variance, a standard focus measure: blur removes fine detail, so the second derivative
 * varies less. Values only compare between images of the same scene and area, such as two photos of one card.
 */
public final class CardSharpness {

    /** Keeps the card's outer edge, and the background beyond it, out of the measurement. */
    private static final double INSET = 0.08;
    private static final int MIN_SAMPLES = 16;

    private CardSharpness() {
    }

    /** @param area the card to measure inside, or null for the whole image */
    public static double laplacianVariance(byte[] luma, int width, int height, CardQuad area) {
        LumaImage.checkSize(luma.length, width, height);
        Homography h = area == null ? null : Homography.fromUnitSquare(area);
        long count = 0;
        double sum = 0;
        double squares = 0;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                if (h != null && !inside(h.unmap(x + 0.5, y + 0.5))) {
                    continue;
                }
                int i = row + x;
                int laplacian = (luma[i - 1] & 0xFF) + (luma[i + 1] & 0xFF) + (luma[i - width] & 0xFF)
                        + (luma[i + width] & 0xFF) - 4 * (luma[i] & 0xFF);
                count++;
                sum += laplacian;
                squares += (double) laplacian * laplacian;
            }
        }
        if (count < MIN_SAMPLES) {
            return 0;
        }
        double mean = sum / count;
        return squares / count - mean * mean;
    }

    private static boolean inside(Corner uv) {
        return uv.x() >= INSET && uv.x() <= 1 - INSET && uv.y() >= INSET && uv.y() <= 1 - INSET;
    }
}
