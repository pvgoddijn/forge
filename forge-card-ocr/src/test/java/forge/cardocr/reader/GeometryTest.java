package forge.cardocr.reader;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.util.*;
import org.junit.jupiter.api.*;

class GeometryTest {

    private static ArgbImage gradient(int w, int h) {
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                p[y * w + x] = 0xFF000000 | (x * 255 / w) << 16 | (y * 255 / h) << 8;
            }
        }
        return new ArgbImage(p, w, h);
    }

    private static final CardQuad UPRIGHT = new CardQuad(new Corner(100, 100), new Corner(730, 100), new Corner(730, 980),
            new Corner(100, 980), 1, true);

    @Test
    void titleBandComesFromTheCardsTop() {
        ArgbImage image = gradient(1000, 1200);
        TitleBands.Band band = TitleBands.title(image, UPRIGHT, TitleBands.NARROW_HIGH);
        assertEquals(48, band.height());
        // 0.77 * 630 wide over 0.08 * 880 high.
        assertEquals((int) (48 * 485.1 / 70.4), band.width());
        int middle = band.rgb()[24 * band.width() + band.width() / 2];
        double expectedX = 100 + 630 * (.05 + .77 / 2);
        double expectedY = 100 + 880 * .06;
        assertEquals(expectedX * 255 / 1000, middle >> 16 & 255, 3);
        assertEquals(expectedY * 255 / 1200, middle >> 8 & 255, 3);
    }

    @Test
    void flippedCardReadsFromTheOtherEnd() {
        ArgbImage image = gradient(1000, 1200);
        TitleBands.Band band = TitleBands.title(image, UPRIGHT.flipped(), TitleBands.NARROW_HIGH);
        int middle = band.rgb()[24 * band.width() + band.width() / 2];
        assertEquals((980 - 880 * .06) * 255 / 1200, middle >> 8 & 255, 3);
    }

    @Test
    void bandWidthStaysWithinLimits() {
        ArgbImage image = gradient(100, 100);
        CardQuad tiny = new CardQuad(new Corner(0, 0), new Corner(2, 0), new Corner(2, 90), new Corner(0, 90), 1, true);
        assertEquals(64, TitleBands.title(image, tiny, TitleBands.NARROW_HIGH).width());
        CardQuad flat = new CardQuad(new Corner(0, 0), new Corner(5000, 0), new Corner(5000, 4), new Corner(0, 4), 1, true);
        assertEquals(800, TitleBands.title(image, flat, TitleBands.WIDE_HIGH).width());
    }

    @Test
    void outsideTheImageIsBlack() {
        ArgbImage image = gradient(10, 10);
        CardQuad away = new CardQuad(new Corner(100, 100), new Corner(200, 100), new Corner(200, 240), new Corner(100, 240), 1, true);
        for (int p : TitleBands.title(image, away, TitleBands.NARROW_HIGH).rgb()) {
            assertEquals(0, p);
        }
    }

    @Test
    void detectorInputIsInGridMultiplesWithinTheLimit() {
        assertEquals(new TextDetection.InputSize(1600, 1216), TextDetection.inputSize(4160, 3120, 1600));
        assertEquals(new TextDetection.InputSize(320, 256), TextDetection.inputSize(320, 240, 1600));
    }

    @Test
    void aHorizontalBlobBecomesAWideGrownBox() {
        int w = 64;
        int h = 32;
        float[] map = new float[w * h];
        for (int y = 10; y < 18; y++) {
            for (int x = 10; x < 50; x++) {
                map[y * w + x] = 0.9f;
            }
        }
        List<TextBox> boxes = TextDetection.boxes(map, w, h, 2, 2);
        assertEquals(1, boxes.size());
        TextBox box = boxes.get(0);
        assertEquals(59, box.centreX(), 0.5);
        assertEquals(27, box.centreY(), 0.5);
        assertEquals(1, Math.abs(box.unitX()), 1e-3);
        assertTrue(box.length() > 78, "length " + box.length());
        assertTrue(box.thickness() > 14, "thickness " + box.thickness());
    }

    @Test
    void faintSmallAndSquareRegionsAreNotText() {
        int w = 64;
        float[] faint = new float[w * w];
        Arrays.fill(faint, 0.35f);
        assertTrue(TextDetection.boxes(faint, w, w, 1, 1).isEmpty());

        float[] small = new float[w * w];
        for (int i = 0; i < 20; i++) {
            small[i] = 0.9f;
        }
        assertTrue(TextDetection.boxes(small, w, w, 1, 1).isEmpty());

        float[] square = new float[w * w];
        for (int y = 5; y < 25; y++) {
            for (int x = 5; x < 25; x++) {
                square[y * w + x] = 0.9f;
            }
        }
        assertTrue(TextDetection.boxes(square, w, w, 1, 1).isEmpty());
    }

    @Test
    void regionsDoNotWrapAroundRowEnds() {
        int w = 40;
        float[] map = new float[w * 8];
        for (int y = 0; y < 8; y++) {
            map[y * w] = 0.9f;
            map[y * w + w - 1] = 0.9f;
        }
        for (int x = 0; x < 12; x++) {
            map[x] = 0.9f;
        }
        TextDetection.boxes(map, w, 8, 1, 1).forEach(b -> assertTrue(b.length() < 30));
    }

    @Test
    void scalingAveragesWhenShrinking() {
        ArgbImage image = new ArgbImage(new int[] {0xFF000000, 0xFF000000 | 200 << 16, 0xFF000000, 0xFF000000 | 200 << 16}, 2, 2);
        ArgbImage one = image.scaledTo(1, 1);
        assertEquals(100, one.pixels()[0] >> 16 & 255);
    }
}
