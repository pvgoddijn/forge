package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.Dim;
import forge.cardocr.finder.*;
import org.junit.jupiter.api.*;

class CardZoomTest {

    private static final Dim ANALYSIS = new Dim(640, 480);
    private static final Dim ACTIVE = new Dim(4096, 3072);
    private static final ActiveRect FULL = new ActiveRect(0, 0, 4096, 3072);

    private static CardQuad box(double left, double top, double right, double bottom) {
        return new CardQuad(new Corner(left, top), new Corner(left, bottom), new Corner(right, bottom), new Corner(right, top), 1, true);
    }

    @Test
    void aCentredCardGetsATightCentredCropWithTheSensorsShape() {
        ActiveRect r = CardZoom.region(box(220, 170, 420, 310), ANALYSIS, FULL, ACTIVE);
        assertEquals(2048, r.left() + r.width() / 2, 1e-6);
        assertEquals(1536, r.top() + r.height() / 2, 1e-6);
        assertEquals(4.0 / 3, r.width() / r.height(), 1e-6);
        assertEquals(1600, r.width(), 1e-6, "100 analysis pixels each way is 640 active pixels, plus the margin");
    }

    @Test
    void anOffCentreCardStillFitsInside() {
        CardQuad card = box(350, 200, 520, 320);
        ActiveRect r = CardZoom.region(card, ANALYSIS, FULL, ACTIVE);
        assertEquals(2048, r.left() + r.width() / 2, 1e-6);
        for (Corner c : card.corners()) {
            Corner a = SensorGeometry.streamToActive(c, ANALYSIS, FULL);
            assertTrue(a.x() > r.left() && a.x() < r.left() + r.width());
            assertTrue(a.y() > r.top() && a.y() < r.top() + r.height());
        }
    }

    @Test
    void aCardAtTheEdgeGetsNoCrop() {
        assertNull(CardZoom.region(box(10, 100, 200, 300), ANALYSIS, FULL, ACTIVE));
    }

    @Test
    void mapsAnOutlineIntoACroppedPhoto() {
        ActiveRect still = new ActiveRect(1024, 768, 2048, 1536);
        CardQuad stored = StillMapper.toStoredCropped(box(160, 120, 480, 360), ANALYSIS, FULL, still, 0, new Dim(1920, 1440));
        assertEquals(0, stored.c0().x(), 1e-6);
        assertEquals(0, stored.c0().y(), 1e-6);
        assertEquals(1920, stored.c2().x(), 1e-6);
        assertEquals(1440, stored.c2().y(), 1e-6);
    }
}
