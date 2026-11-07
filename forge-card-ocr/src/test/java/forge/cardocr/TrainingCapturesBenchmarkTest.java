package forge.cardocr;

import static org.junit.jupiter.api.Assertions.*;

import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.TrainingCaptures;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The folder "Keep photos for training" fills is one {@link CardNameBenchmark} reads as it is. */
class TrainingCapturesBenchmarkTest {

    private static final float[] CORNERS = {10, 20, 410, 22, 405, 590, 8, 585};

    @TempDir
    Path temp;

    private static File photo(Path dir, String name) throws IOException {
        Path photo = dir.resolve(name);
        Files.write(photo, "not decoded".getBytes(StandardCharsets.UTF_8));
        return photo.toFile();
    }

    @Test
    void readsTheLabelledRunsWithTheirOutlines() throws IOException {
        TrainingCaptures captures = new TrainingCaptures(temp.resolve(TrainingCaptures.FOLDER).toFile());
        TrainingCaptures.Run tracked = captures.keep(photo(temp, "a.jpg"),
                new TrainingCaptures.Capture(1000, "Test phone", 3000, 4000, 6, CORNERS));
        TrainingCaptures.Run untracked = captures.keep(photo(temp, "b.jpg"),
                new TrainingCaptures.Capture(2000, "Test phone", 3000, 4000, 6, null));
        TrainingCaptures.Run cancelled = captures.keep(photo(temp, "c.jpg"),
                new TrainingCaptures.Capture(3000, "Test phone", 3000, 4000, 6, CORNERS));
        tracked.recordReading(new CardNameReader.Reading("Lightning", List.of("Lightning Bolt"), true, 1.2, 0.3));
        tracked.label("Lightning Bolt");
        untracked.label("Borrowing 100,000 Arrows");
        cancelled.label(TrainingCaptures.UNLABELLED);

        List<CardNameBenchmark.Photo> photos = CardNameBenchmark.photos(captures.root(), Map.of());

        assertEquals(2, photos.size(), "the UNLABELLED run is left out");
        CardNameBenchmark.Photo first = photos.get(0);
        assertEquals(tracked.photo(), first.file());
        assertEquals("Lightning Bolt", first.card());
        assertEquals(tracked.dir().getName(), first.label());
        assertArrayEquals(CORNERS, first.corners(), 0.001f, "the outline is in the photo's own pixels: scale 1");
        CardNameBenchmark.Photo second = photos.get(1);
        assertEquals(untracked.photo(), second.file());
        assertEquals("Borrowing 100,000 Arrows", second.card());
        assertNull(second.corners());
    }
}
