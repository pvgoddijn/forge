package forge.cardocr;

/** Downsampling for showing a large photo without exhausting memory. */
public final class ImageScaling {

    private ImageScaling() {
    }

    /** The largest power of two that keeps the decoded image at least as big as the request. */
    public static int sampleSize(Dim image, int maxLongSide) {
        int sample = 1;
        while (image.longSide() / (sample * 2) >= maxLongSide) {
            sample *= 2;
        }
        return sample;
    }

    /** Clockwise rotation for an EXIF orientation value. Camera photos are never mirrored here. */
    public static int rotationDegrees(int exifOrientation) {
        return switch (exifOrientation) {
            case 3 -> 180;
            case 6 -> 90;
            case 8 -> 270;
            default -> 0;
        };
    }
}
