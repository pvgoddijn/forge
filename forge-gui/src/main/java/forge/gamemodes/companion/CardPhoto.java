package forge.gamemodes.companion;

import java.io.File;

/**
 * A photo of one card, from the camera or a file.
 *
 * @param jpeg the photo
 * @param cardCorners where the camera saw the card when it took the photo: 4 corners as x, y pairs, in the stored
 *     photo's pixels before any EXIF rotation, going round the card with the first two on a short side. Null if it
 *     didn't track one. The reader starts there, which is faster and steadier than finding the card again.
 * @param temporary whether the photo should be deleted once it's read (AC-7 keeps no photos)
 */
public record CardPhoto(File jpeg, float[] cardCorners, boolean temporary) {
    public CardPhoto {
        if (cardCorners != null && cardCorners.length != 8) {
            throw new IllegalArgumentException("cardCorners needs 4 corners, as 8 numbers");
        }
    }
}
