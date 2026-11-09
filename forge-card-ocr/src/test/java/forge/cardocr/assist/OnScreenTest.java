package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.util.*;
import org.junit.jupiter.api.*;

class OnScreenTest {

    private static List<Corner> box(double left, double top, double right, double bottom) {
        return List.of(new Corner(left, top), new Corner(right, top), new Corner(right, bottom), new Corner(left, bottom));
    }

    @Test
    void acceptsAnOutlineOnTheScreen() {
        assertTrue(OnScreen.contains(box(100, 100, 1100, 2500), 1200, 2670));
    }

    @Test
    void onlyAnOutlineUnderTheCrosshairCounts() {
        assertTrue(OnScreen.underCrosshair(box(200, 1000, 1000, 1700), 1200, 2670));
        assertTrue(OnScreen.underCrosshair(List.of(new Corner(1000, 1000), new Corner(1000, 1700), new Corner(200, 1700), new Corner(200, 1000)), 1200, 2670), "either way round");
        assertFalse(OnScreen.underCrosshair(box(200, 1500, 1000, 2200), 1200, 2670), "below it");
        assertFalse(OnScreen.underCrosshair(box(700, 1000, 1100, 1700), 1200, 2670), "beside it");
    }

    @Test
    void allowsASlightOverrun() {
        assertTrue(OnScreen.contains(box(-30, 100, 1100, 2500), 1200, 2670));
    }

    /** As with the Combat Celebrant the camera chose with more than half of it off the side of the screen. */
    @Test
    void rejectsAnOutlineHalfOffTheScreen() {
        assertFalse(OnScreen.contains(box(700, 800, 1700, 2200), 1200, 2670));
        assertFalse(OnScreen.contains(box(-500, 800, 500, 2200), 1200, 2670));
        assertFalse(OnScreen.contains(box(100, 2000, 1100, 3400), 1200, 2670));
    }
}
