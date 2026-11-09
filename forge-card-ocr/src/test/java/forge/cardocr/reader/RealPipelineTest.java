package forge.cardocr.reader;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import forge.cardocr.finder.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * The real models through ONNX Runtime. The photo test needs the OCR spike's photos and runs only where they are:
 * those photos tuned the pipeline, so it guards against breaking it and says nothing about new photos.
 */
class RealPipelineTest {

    @Test
    void defaultBudgetReadsTheRealPhotos() {
        List<File> photos = PhotoFiles.list();
        assumeFalse(photos.isEmpty(), "no photos");
        assumeTrue(ReaderFiles.haveAllNames(), "no full name list");

        CardNames names = ReaderFiles.names();
        NameTrie trie = new NameTrie(names);
        try (OnnxModels models = ReaderFiles.models()) {
            CardReader reader = new CardReader(models, names, trie, ReaderFiles.classes());
            int inTop3 = 0;
            for (File photo : photos) {
                int truth = ReaderFiles.truth(names, photo);
                assertTrue(truth >= 0, photo.getName());
                ReadResult result = reader.read(ReaderFiles.load(photo, 2000), ReadBudget.ON_PHONE.get(0));
                assertTrue(result.matches().size() <= 3);
                for (ReadResult.Match match : result.matches()) {
                    if (match.name().equals(names.name(truth))) {
                        inTop3++;
                        break;
                    }
                }
            }
            assertTrue(inTop3 >= 25, "top-3 " + inTop3 + "/" + photos.size());
        }
    }

    /** The shipped models load and run, and an image with no card gives no card rather than a crash. */
    @Test
    void aBlankImageGivesNoCrashAndAWholeImageFallback() {
        CardNames names = CardNames.of(List.of("Plains", "Lightning Bolt", "Westfold Rider"));
        try (OnnxModels models = ReaderFiles.models()) {
            CardReader reader = new CardReader(models, names, new NameTrie(names), ReaderFiles.classes());
            int[] grey = new int[640 * 480];
            Arrays.fill(grey, 0xFF808080);
            ReadResult result = reader.read(new ArgbImage(grey, 640, 480), ReadBudget.ON_PHONE.get(0));
            assertNull(result.card());
            assertTrue(result.wholeUsed());
            assertTrue(result.matches().isEmpty());
        }
    }
}
