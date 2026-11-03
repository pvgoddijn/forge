package forge.app;

import forge.cardocr.OcrCardNameReader;
import forge.cardocr.PhotoDecoder;
import forge.cardocr.reader.ArgbImage;
import forge.gamemodes.companion.CardNameMatcher;
import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.CardPhoto;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Card photos on the desktop: a file chooser stands in for the camera, so Companion's photo path can be tried without
 * a phone. This jar is also the shipped PC build, so it's off unless the system property {@value #ENABLE_PROPERTY} is
 * {@code true}. Setting {@value #PHOTO_PROPERTY} to a file turns it on too, and uses that file instead of asking, for
 * scripted checks.
 */
final class DesktopCardPhotos {
    static final String ENABLE_PROPERTY = "forge.companion.photos";
    static final String PHOTO_PROPERTY = "forge.companion.cardPhoto";
    private static CardNameReader reader;

    private DesktopCardPhotos() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY) || System.getProperty(PHOTO_PROPERTY) != null;
    }

    /** Asks for a photo on Swing's thread, off the game's, so the game screen keeps drawing while the chooser is open. */
    static void choose(Consumer<CardPhoto> onResult) {
        String scripted = System.getProperty(PHOTO_PROPERTY);
        if (scripted != null) {
            onResult.accept(new CardPhoto(new File(scripted), null, false));
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Photo of a card");
            chooser.setFileFilter(new FileNameExtensionFilter("Photos", "jpg", "jpeg", "png"));
            boolean chosen = chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION;
            // the chosen photo is the operator's own file, so it isn't deleted after reading
            onResult.accept(chosen ? new CardPhoto(chooser.getSelectedFile(), null, false) : null);
        });
    }

    static synchronized CardNameReader reader() {
        if (reader == null) {
            reader = new OcrCardNameReader(CardNameMatcher::cardFaceNames, DesktopCardPhotos::decode);
        }
        return reader;
    }

    private static PhotoDecoder.Decoded decode(File jpeg, int longSide) {
        BufferedImage photo;
        try {
            photo = ImageIO.read(jpeg);
        } catch (IOException e) {
            return null;
        }
        if (photo == null) {
            return null;
        }
        int width = photo.getWidth();
        int height = photo.getHeight();
        ArgbImage image = new ArgbImage(photo.getRGB(0, 0, width, height, null, 0, width), width, height);
        double scale = 1;
        // halved like Android's inSampleSize, so both backends read the same pixels
        while (Math.max(image.width(), image.height()) / 2 >= longSide) {
            image = image.scaledTo(image.width() / 2, image.height() / 2);
            scale /= 2;
        }
        return new PhotoDecoder.Decoded(image, scale);
    }
}
