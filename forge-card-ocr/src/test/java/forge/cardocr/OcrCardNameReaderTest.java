package forge.cardocr;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import forge.cardocr.finder.PhotoFiles;
import forge.cardocr.reader.ArgbImage;
import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.CardPhoto;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The reader through the interface the game uses, with a desktop decoder like the dev build's. */
class OcrCardNameReaderTest {

    private static final PhotoDecoder DECODER = (jpeg, longSide) -> {
        PhotoFiles.Argb argb = PhotoFiles.load(jpeg);
        ArgbImage image = new ArgbImage(argb.pixels(), argb.width(), argb.height());
        double scale = 1;
        while (Math.max(image.width(), image.height()) / 2 >= longSide) {
            image = image.scaledTo(image.width() / 2, image.height() / 2);
            scale /= 2;
        }
        return new PhotoDecoder.Decoded(image, scale);
    };

    @Test
    void aRealPhotoSuggestsItsCardConfidently() {
        File photo = new File(PhotoFiles.FOLDER, "westfold-rider.jpg");
        assumeTrue(photo.exists(), "no photos");
        CardNameReader reader = new OcrCardNameReader(
                () -> List.of("Westfold Rider", "Woe Strider", "Wasteland Raider", "Lightning Bolt", "Plains"), DECODER);
        CardNameReader.Reading reading = reader.read(new CardPhoto(photo, null, false));
        assertEquals("Westfold Rider", reading.cards().get(0));
        assertTrue(reading.confident());
        assertTrue(reading.bestScore() >= 0.10, "the best score passes the reader's own cut-off");
        assertTrue(reading.bestScore() > reading.secondScore(), "the scores come best first");
        assertTrue(reading.cards().size() <= CardNameReader.MAX_SUGGESTIONS);
        assertTrue(photo.exists(), "a photo that isn't temporary is kept");
    }

    @Test
    void aBlankPhotoIsNotConfidentAndATemporaryPhotoIsDeleted() throws IOException {
        File blank = Files.createTempFile("blank", ".jpg").toFile();
        PhotoDecoder grey = (jpeg, longSide) -> {
            int[] pixels = new int[640 * 480];
            java.util.Arrays.fill(pixels, 0xFF808080);
            return new PhotoDecoder.Decoded(new ArgbImage(pixels, 640, 480), 1);
        };
        CardNameReader reader = new OcrCardNameReader(() -> List.of("Plains", "Lightning Bolt"), grey);
        CardNameReader.Reading reading = reader.read(new CardPhoto(blank, null, true));
        assertFalse(reading.confident());
        assertFalse(blank.exists(), "photos aren't kept after reading");
    }

    @Test
    void anUndecodablePhotoReadsNothing() throws IOException {
        File broken = Files.createTempFile("broken", ".jpg").toFile();
        CardNameReader reader = new OcrCardNameReader(List::of, (jpeg, longSide) -> null);
        CardNameReader.Reading reading = reader.read(new CardPhoto(broken, null, true));
        assertEquals(CardNameReader.Reading.NOTHING, reading);
        assertEquals(0, reading.bestScore());
        assertFalse(broken.exists());
    }
}
