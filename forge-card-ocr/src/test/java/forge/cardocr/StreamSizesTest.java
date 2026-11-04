package forge.cardocr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StreamSizesTest {

    private static final Dim BOUND = new Dim(1920, 1080);

    @Test
    void largestPicksGreatestArea() {
        assertEquals(new Dim(4000, 3000), StreamSizes.largest(List.of(new Dim(1920, 1080), new Dim(4000, 3000), new Dim(4000, 2250))));
    }

    @Test
    void selectPrefersThePhotosAspectRatioOverALargerStream() {
        List<Dim> sizes = List.of(new Dim(1920, 1080), new Dim(1440, 1080), new Dim(640, 480));
        assertEquals(new Dim(1440, 1080), StreamSizes.select(sizes, new Dim(4000, 3000), BOUND));
    }

    @Test
    void selectIgnoresSizesOverTheBound() {
        List<Dim> sizes = List.of(new Dim(3840, 2160), new Dim(1280, 720));
        assertEquals(new Dim(1280, 720), StreamSizes.select(sizes, new Dim(3840, 2160), BOUND));
    }

    @Test
    void selectFallsBackToTheClosestRatio() {
        List<Dim> sizes = List.of(new Dim(1920, 800), new Dim(1280, 720));
        assertEquals(new Dim(1280, 720), StreamSizes.select(sizes, new Dim(4000, 3000), BOUND));
    }

    @Test
    void selectTakesTheSmallestWhenNothingFits() {
        List<Dim> sizes = List.of(new Dim(3840, 2160), new Dim(2560, 1440));
        assertEquals(new Dim(2560, 1440), StreamSizes.select(sizes, new Dim(4000, 3000), BOUND));
    }

    @Test
    void selectTreatsNearlyEqualRatiosAsTheSame() {
        // Both are about 16:9 and the larger one wins, though the smaller is the exact ratio.
        List<Dim> sizes = List.of(new Dim(1280, 720), new Dim(1600, 904));
        assertEquals(new Dim(1600, 904), StreamSizes.select(sizes, new Dim(3840, 2160), BOUND));
    }
}
