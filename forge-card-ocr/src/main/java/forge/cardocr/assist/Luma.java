package forge.cardocr.assist;

/** Greyscale conversion for decoded photos. */
public final class Luma {

    private Luma() {
    }

    /** Rec. 601 luma, the weights a camera's Y plane uses. */
    public static byte[] fromArgb(int[] pixels) {
        byte[] out = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            out[i] = (byte) ((299 * (p >> 16 & 255) + 587 * (p >> 8 & 255) + 114 * (p & 255)) / 1000);
        }
        return out;
    }
}
