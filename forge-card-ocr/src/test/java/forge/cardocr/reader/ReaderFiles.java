package forge.cardocr.reader;

import forge.cardocr.finder.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** What real-model tests need: the models, the card names, and each photo's true card. */
final class ReaderFiles {

    /** The shipped models, from the module's own resources. */
    static final Path MODELS = Path.of("src/main/resources/forge/cardocr/ocr");
    /**
     * Every card name, as the camera PoC exported them from Forge's scripts. Only on the developer's machine: in the
     * app the names come from Forge's card database.
     */
    static final Path ALL_NAMES = Path.of("/mnt/wsl/git/forge-camera-poc/app/src/main/assets/ocr/card-names.txt");

    private ReaderFiles() {
    }

    static boolean haveAllNames() {
        return Files.exists(ALL_NAMES);
    }

    static CardNames names() {
        try {
            return CardNames.of(Files.readAllLines(ALL_NAMES));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static CharClasses classes() {
        try {
            return new CharClasses(Files.readAllLines(MODELS.resolve("languages_english_dict.txt")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static OnnxModels models() {
        try {
            return new OnnxModels(Files.readAllBytes(MODELS.resolve("languages_english_rec.onnx")),
                    Files.readAllBytes(MODELS.resolve("detection_v3_det.onnx")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A photo's card from its file name: a slug, perhaps with a -2 for another copy. -1 if unknown. */
    static int truth(CardNames names, File photo) {
        String slug = photo.getName().replaceFirst("\\.jpg$", "").replaceFirst("-\\d+$", "");
        String normalised = CardNames.normalise(slug);
        int exact = names.indexOf(normalised);
        if (exact >= 0) {
            return exact;
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.normalised(i).startsWith(normalised) && names.name(i).contains(" // ")) {
                return i;
            }
        }
        return -1;
    }

    /** The photo as the phone would decode it: halved until one more halving would go below the limit. */
    static ArgbImage load(File photo, int decodeLongSide) {
        PhotoFiles.Argb argb = PhotoFiles.load(photo);
        ArgbImage image = new ArgbImage(argb.pixels(), argb.width(), argb.height());
        while (decodeLongSide > 0 && Math.max(image.width(), image.height()) / 2 >= decodeLongSide) {
            image = image.scaledTo(image.width() / 2, image.height() / 2);
        }
        return image;
    }
}
