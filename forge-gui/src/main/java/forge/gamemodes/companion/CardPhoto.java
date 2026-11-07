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
 * @param training where the photo is kept for the OCR benchmark, when "Keep photos for training" is on (the exception
 *     to AC-7; the photo is then not temporary); null otherwise. The photo dialog records the read and the card picked
 *     in it.
 */
public record CardPhoto(File jpeg, float[] cardCorners, boolean temporary, TrainingCaptures.Run training) {
    public CardPhoto {
        if (cardCorners != null && cardCorners.length != 8) {
            throw new IllegalArgumentException("cardCorners needs 4 corners, as 8 numbers");
        }
        if (training != null && temporary) {
            throw new IllegalArgumentException("a photo kept for training isn't temporary");
        }
    }

    public CardPhoto(File jpeg, float[] cardCorners, boolean temporary) {
        this(jpeg, cardCorners, temporary, null);
    }
}
