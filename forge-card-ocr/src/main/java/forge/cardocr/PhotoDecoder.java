package forge.cardocr;

import forge.cardocr.reader.ArgbImage;

import java.io.File;

/**
 * Decodes a JPEG into pixels. Each backend brings its own, as this module has no image types: ImageIO on the
 * desktop, BitmapFactory on Android.
 */
public interface PhotoDecoder {
    /**
     * The photo, made smaller by a whole factor where that keeps its long side at {@code longSide} or more.
     *
     * @param scale decoded pixels per stored pixel, for mapping points in the stored photo
     */
    record Decoded(ArgbImage image, double scale) {
    }

    /** @return the decoded photo, or null if it can't be decoded */
    Decoded decode(File jpeg, int longSide);
}
