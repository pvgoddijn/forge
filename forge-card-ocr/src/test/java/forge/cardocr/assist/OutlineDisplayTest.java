package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.util.*;
import org.junit.jupiter.api.*;

class OutlineDisplayTest {

    private static CardQuad at(double x, double y, double evidence) {
        return new CardQuad(new Corner(x, y), new Corner(x + 200, y), new Corner(x + 200, y + 280), new Corner(x, y + 280), 1, true, evidence);
    }

    private final OutlineDisplay display = new OutlineDisplay();

    private OutlineDisplay.Shown frame(long now, CardQuad... candidates) {
        return display.update(List.of(candidates), null, ImageMotion.NONE, now);
    }

    @Test
    void showsAPlausibleCandidateOnItsFirstFrameThinly() {
        OutlineDisplay.Shown shown = frame(0, at(100, 100, 0.8));
        assertNotNull(shown);
        assertFalse(shown.confirmed());
    }

    @Test
    void doesNotShowAnImplausibleCandidate() {
        assertNull(frame(0, at(100, 100, 0.1)));
    }

    @Test
    void staysThroughShortGapsAndGoesAfterHalfASecond() {
        frame(0, at(100, 100, 0.8));
        assertNotNull(frame(100));
        assertNotNull(frame(OutlineDisplay.HIDE_AFTER_MS - 1));
        assertNull(frame(OutlineDisplay.HIDE_AFTER_MS));
    }

    @Test
    void becomesSolidWithoutMovingWhenConfirmed() {
        CardQuad quad = at(100, 100, 0.8);
        frame(0, quad);
        OutlineDisplay.Shown shown = display.update(List.of(quad), new TrackedCard(1, quad, 0, 0, true), ImageMotion.NONE, 30);
        assertTrue(shown.confirmed());
        assertEquals(100, shown.quad().c0().x(), 1e-9);
    }

    @Test
    void filtersFitNoiseButFollowsRealMovement() {
        frame(0, at(100, 100, 0.8));
        OutlineDisplay.Shown noisy = frame(33, at(103, 100, 0.8));
        assertTrue(noisy.quad().c0().x() < 101, "noise moved it to " + noisy.quad().c0().x());
        OutlineDisplay.Shown moved = frame(66, at(130, 100, 0.8));
        assertEquals(130, moved.quad().c0().x(), 1e-9);
    }

    @Test
    void anotherOutlineReplacesItOnlyOnceItHasGoneUnsupported() {
        frame(0, at(100, 100, 0.8));
        assertEquals(100, frame(100, at(400, 100, 0.8)).quad().c0().x(), 1e-9);
        assertEquals(400, frame(OutlineDisplay.SWITCH_AFTER_MS, at(400, 100, 0.8)).quad().c0().x(), 1e-9);
    }
}
