package forge.cardocr.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class HomographyTest {

    private static final CardQuad TILTED = new CardQuad(new Corner(120.5, 80.25),
            new Corner(410, 130), new Corner(350, 560), new Corner(40, 470), 1, true);

    @Test
    void mapsUnitSquareCornersOntoTheQuad() {
        Homography h = Homography.fromUnitSquare(TILTED);
        assertClose(TILTED.c0(), h.map(0, 0));
        assertClose(TILTED.c1(), h.map(1, 0));
        assertClose(TILTED.c2(), h.map(1, 1));
        assertClose(TILTED.c3(), h.map(0, 1));
    }

    @Test
    void unmapUndoesMapAcrossTheCard() {
        Homography h = Homography.fromUnitSquare(TILTED);
        for (double u = 0; u <= 1; u += 0.125) {
            for (double v = 0; v <= 1; v += 0.125) {
                Corner p = h.map(u, v);
                assertClose(new Corner(u, v), h.unmap(p.x(), p.y()));
            }
        }
    }

    @Test
    void parallelogramHasNoPerspective() {
        CardQuad flat = new CardQuad(new Corner(10, 10), new Corner(110, 30),
                new Corner(70, 170), new Corner(-30, 150), 1, false);
        Homography h = Homography.fromUnitSquare(flat);
        assertClose(new Corner(40, 90), h.map(0.5, 0.5));
    }

    @Test
    void warpRestoresAPatternDrawnThroughTheHomography() {
        Homography h = Homography.fromUnitSquare(TILTED);
        int width = 500;
        int height = 600;
        int[] source = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Corner uv = h.unmap(x + 0.5, y + 0.5);
                source[y * width + x] = pattern(uv.x(), uv.y());
            }
        }
        int outW = 126;
        int outH = 176;
        int[] flat = CardFinder.warp(source, width, height, TILTED, outW, outH);
        for (int y = 2; y < outH - 2; y++) {
            for (int x = 2; x < outW - 2; x++) {
                int expected = pattern((x + 0.5) / outW, (y + 0.5) / outH);
                int actual = flat[y * outW + x];
                assertEquals(0xFF, actual >>> 24);
                assertEquals(expected >> 16 & 255, actual >> 16 & 255, 8, "red at " + x + "," + y);
                assertEquals(expected >> 8 & 255, actual >> 8 & 255, 8, "green at " + x + "," + y);
            }
        }
    }

    @Test
    void warpLeavesPixelsOutsideTheSourceBlack() {
        CardQuad partlyOutside = new CardQuad(new Corner(-50, -50), new Corner(50, -50),
                new Corner(50, 50), new Corner(-50, 50), 1, false);
        int[] white = new int[100 * 100];
        Arrays.fill(white, 0xFFFFFFFF);
        int[] flat = CardFinder.warp(white, 100, 100, partlyOutside, 10, 10);
        assertEquals(0xFF000000, flat[0]);
        assertEquals(0xFFFFFFFF, flat[99]);
    }

    /** A smooth gradient, so that a position error shows up as a colour error. */
    private static int pattern(double u, double v) {
        int r = (int) (Math.max(0, Math.min(1, u)) * 255);
        int g = (int) (Math.max(0, Math.min(1, v)) * 255);
        return 0xFF000000 | r << 16 | g << 8;
    }

    private static void assertClose(Corner expected, Corner actual) {
        assertEquals(expected.x(), actual.x(), 1e-9);
        assertEquals(expected.y(), actual.y(), 1e-9);
    }
}
