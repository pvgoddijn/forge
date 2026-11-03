package forge.cardocr.finder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

/** Cards with known corners. Corner error is the worst of the four corners, in input pixels. */
class SyntheticGeometryTest {

    /** At 640x480, one pixel is about 0.3 mm on a card filling 300 pixels. */
    private static final double TOLERANCE_640 = 1.5;
    private static final double TOLERANCE_1280 = 2.0;

    @ParameterizedTest(name = "{0} degrees, keystone {1}")
    @CsvSource({"0,0", "7,0", "23,0", "45,0", "68,0", "90,0", "135,0", "12,0.05", "33,0.08",
            "60,0.05", "90,0.08", "101,0.05"})
    void findsPlainDarkCardAt640(double degrees, double keystone) {
        assertFinds(640, 480, 300, degrees, keystone, TOLERANCE_640, 30, 220, 30, 30);
    }

    @ParameterizedTest(name = "{0} degrees, keystone {1}")
    @CsvSource({"0,0", "23,0", "45,0", "90,0", "12,0.05", "33,0.08", "101,0.05"})
    void findsPlainDarkCardAt1280(double degrees, double keystone) {
        assertFinds(1280, 960, 600, degrees, keystone, TOLERANCE_1280, 30, 220, 30, 30);
    }

    /** A real card has inner lines at its frame and art box, and the outermost must be chosen. */
    @ParameterizedTest(name = "{0} degrees, keystone {1}")
    @CsvSource({"0,0", "17,0", "52,0", "90,0", "28,0.05"})
    void choosesOuterEdgeOverPrintedFrameAt640(double degrees, double keystone) {
        assertFinds(640, 480, 300, degrees, keystone, TOLERANCE_640, 25, 150, 190, 80);
    }

    @ParameterizedTest(name = "{0} degrees")
    @CsvSource({"0", "90", "15"})
    void acceptsSmallCardInLowResolution(double degrees) {
        assertFinds(640, 480, 150, degrees, 0, TOLERANCE_640, 30, 220, 30, 30);
    }

    /** A neighbour at the same angle is normal. The centred card must win. */
    @Test
    void prefersCentredCardOverNeighbourAtTheSameAngle() {
        byte[] luma = SyntheticCard.blank(960, 720, 200);
        CardQuad centred = SyntheticCard.corners(480, 360, 400, 10, 0);
        CardQuad neighbour = SyntheticCard.corners(860, 420, 400, 10, 0);
        SyntheticCard.draw(luma, 960, 720, centred, 25, 150, 80);
        SyntheticCard.draw(luma, 960, 720, neighbour, 25, 150, 80);
        CardQuad top = new CardFinder().find(luma, 960, 720).candidates().get(0);
        double error = SyntheticCard.cornerError(top, centred);
        assertTrue(error <= TOLERANCE_1280, "corner error " + error);
    }

    @Test
    void findsNothingInABlankFrame() {
        FindResult result = new CardFinder().find(SyntheticCard.blank(640, 480, 128), 640, 480);
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.elapsedNanos() > 0);
    }

    @Test
    void refineGivesUpOnAFlatImage() {
        LumaImage flat = new LumaImage(new float[640 * 480], 640, 480);
        assertNull(EdgeRefiner.refine(flat, SyntheticCard.corners(320, 240, 300, 0, 0)));
    }

    /**
     * A card whose top edge has so little contrast that the search still counts it but the edge
     * fit, which needs a stronger step, doesn't.
     */
    @Test
    void keepsRoughRectangleWhenRefinementFails() {
        int width = 640;
        int height = 480;
        byte[] luma = SyntheticCard.blank(width, height, 220);
        int x0 = 220;
        int y0 = 100;
        int w = 200;
        int h = 280;
        for (int y = y0; y <= y0 + h; y++) {
            for (int x = x0; x <= x0 + w; x++) {
                boolean border = x - x0 < 12 || x0 + w - x < 12 || y0 + h - y < 12;
                luma[y * width + x] = (byte) (border ? 20 : 215);
            }
        }
        CardQuad truth = new CardQuad(new Corner(x0, y0), new Corner(x0 + w, y0),
                new Corner(x0 + w, y0 + h), new Corner(x0, y0 + h), 1, false);
        CardQuad nearest = null;
        for (CardQuad quad : new CardFinder().find(luma, width, height).candidates()) {
            if (nearest == null || SyntheticCard.cornerError(quad, truth)
                    < SyntheticCard.cornerError(nearest, truth)) {
                nearest = quad;
            }
        }
        assertNotNull(nearest);
        assertFalse(nearest.refined());
        assertTrue(SyntheticCard.cornerError(nearest, truth) < 8);
        // The search scores this rectangle at most 1, and a fallback halves it.
        assertTrue(nearest.score() <= 0.5, "score " + nearest.score());
    }

    /** An art box with far stronger edges than the card, sideways-shaped, mustn't win. */
    @ParameterizedTest(name = "{0} degrees")
    @CsvSource({"0", "15", "90"})
    void prefersCardOverItsArtBox(double degrees) {
        byte[] luma = SyntheticCard.blank(960, 720, 120);
        CardQuad card = SyntheticCard.corners(480, 360, 560, degrees, 0);
        SyntheticCard.draw(luma, 960, 720, card, 70, 170, 10);
        CardQuad top = new CardFinder().find(luma, 960, 720).candidates().get(0);
        double error = SyntheticCard.cornerError(top, card);
        assertTrue(error <= TOLERANCE_1280, "corner error " + error);
    }

    @Test
    void acceptsFloatInputWithTheSameResult() {
        byte[] luma = SyntheticCard.blank(640, 480, 220);
        SyntheticCard.draw(luma, 640, 480, SyntheticCard.corners(320, 240, 300, 20, 0), 30, 30, 30);
        float[] floats = new float[luma.length];
        for (int i = 0; i < luma.length; i++) {
            floats[i] = luma[i] & 0xFF;
        }
        CardQuad fromBytes = new CardFinder().find(luma, 640, 480).candidates().get(0);
        CardQuad fromFloats = new CardFinder().find(floats, 640, 480).candidates().get(0);
        assertEquals(0, fromBytes.maxCornerDistance(fromFloats), 1e-9);
    }

    @Test
    void rejectsArrayThatIsTooSmall() {
        assertThrows(IllegalArgumentException.class,
                () -> new CardFinder().find(new byte[10], 640, 480));
    }

    private static void assertFinds(int width, int height, double cardHeight, double degrees,
            double keystone, double tolerance, int border, int background, int face, int art) {
        byte[] luma = SyntheticCard.blank(width, height, background);
        CardQuad truth = SyntheticCard.corners(width / 2.0, height / 2.0, cardHeight, degrees,
                keystone);
        SyntheticCard.draw(luma, width, height, truth, border, face, art);
        FindResult result = new CardFinder().find(luma, width, height);
        CardQuad top = result.candidates().get(0);
        double error = SyntheticCard.cornerError(top, truth);
        assertTrue(top.refined(), "top candidate wasn't refined, error " + error);
        assertTrue(error <= tolerance, "corner error " + error + " px over " + tolerance);
    }
}
