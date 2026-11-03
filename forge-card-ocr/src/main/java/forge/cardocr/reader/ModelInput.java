package forge.cardocr.reader;

/** Pixels into the models' tensors: 3 planes in blue, green, red order. */
final class ModelInput {

    private static final float[] DETECT_MEAN = {.485f, .456f, .406f};
    private static final float[] DETECT_STD = {.229f, .224f, .225f};

    private ModelInput() {
    }

    /** Scaled to -1 to 1. */
    static float[] forRecogniser(int[] rgb) {
        float[] out = new float[3 * rgb.length];
        int green = rgb.length;
        int red = 2 * rgb.length;
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            out[i] = ((p & 255) / 255f - .5f) / .5f;
            out[green + i] = ((p >> 8 & 255) / 255f - .5f) / .5f;
            out[red + i] = ((p >> 16 & 255) / 255f - .5f) / .5f;
        }
        return out;
    }

    /** Normalised with these constants, in this channel order, as the spike did. */
    static float[] forDetector(int[] rgb) {
        float[] out = new float[3 * rgb.length];
        int green = rgb.length;
        int red = 2 * rgb.length;
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            out[i] = ((p & 255) / 255f - DETECT_MEAN[0]) / DETECT_STD[0];
            out[green + i] = ((p >> 8 & 255) / 255f - DETECT_MEAN[1]) / DETECT_STD[1];
            out[red + i] = ((p >> 16 & 255) / 255f - DETECT_MEAN[2]) / DETECT_STD[2];
        }
        return out;
    }
}
