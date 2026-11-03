package forge.cardocr.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Runs on the real photos at preview size. They have no ground-truth corners, so this checks only
 * structural facts. Accuracy is judged by eye from FinderEvaluation's contact sheets.
 */
class RealPhotosTest {

    @Test
    void producesRankedPlausibleCandidatesAt640() {
        List<File> photos = PhotoFiles.list();
        Assumptions.assumeFalse(photos.isEmpty(), "No photos in " + PhotoFiles.FOLDER);
        CardFinder finder = new CardFinder();
        for (File file : photos) {
            PhotoFiles.Argb small = PhotoFiles.load(file).fitted(640);
            FindResult result = finder.find(small.luma(), small.width(), small.height());
            List<CardQuad> found = result.candidates();
            assertFalse(found.isEmpty(), file.getName());
            assertTrue(found.size() <= FinderOptions.DEFAULTS.maxCandidates(), file.getName());
            assertTrue(result.elapsedNanos() > 0, file.getName());
            for (int i = 0; i < found.size(); i++) {
                CardQuad quad = found.get(i);
                if (i > 0) {
                    assertTrue(found.get(i - 1).score() >= quad.score(), file.getName());
                }
                double shortSide = quad.c0().distanceTo(quad.c1());
                double longSide = quad.c0().distanceTo(quad.c3());
                assertTrue(longSide > shortSide, file.getName() + " rank " + i);
            }
        }
    }
}
