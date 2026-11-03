package forge.cardocr.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class CardShapeTest {

    /** @param length the long side, @param ratio long over short, @param degrees the turn from the screen's vertical */
    private static CardQuad quad(double length, double ratio, double degrees) {
        double c = Math.cos(Math.toRadians(degrees));
        double s = Math.sin(Math.toRadians(degrees));
        double l = length / 2;
        double w = length / ratio / 2;
        return new CardQuad(point(-l, -w, c, s), point(-l, w, c, s), point(l, w, c, s), point(l, -w, c, s), 1, true);
    }

    private static Corner point(double along, double across, double c, double s) {
        return new Corner(320 + along * c - across * s, 240 + along * s + across * c);
    }

    @Test
    void acceptsAnUprightCardEitherWayUpAndTurnedUpTo45Degrees() {
        assertTrue(CardShape.accepted(quad(240, 88.0 / 63, 0)));
        assertTrue(CardShape.accepted(quad(240, 88.0 / 63, 180)));
        assertTrue(CardShape.accepted(quad(240, 88.0 / 63, 44.9)));
        assertTrue(CardShape.accepted(quad(240, 88.0 / 63, -44.9)));
    }

    @Test
    void rejectsACardTurnedPast45Degrees() {
        assertFalse(CardShape.accepted(quad(240, 88.0 / 63, 45.1)));
        assertFalse(CardShape.accepted(quad(240, 88.0 / 63, 90)));
    }

    /** Measured from Combat Celebrant's sideways outline on the phone: 199 by 139. */
    @Test
    void rejectsAnArtBoxOutline() {
        assertFalse(CardShape.accepted(quad(199, 199.0 / 139, 90)));
    }

    @Test
    void rejectsTheWrongProportions() {
        assertTrue(CardShape.accepted(quad(240, 1.27, 0)), "the lowest real outline");
        assertTrue(CardShape.accepted(quad(240, 1.47, 0)), "the highest real outline");
        assertFalse(CardShape.accepted(quad(240, 1.2, 0)));
        assertFalse(CardShape.accepted(quad(240, 1.6, 0)));
        assertFalse(CardShape.accepted(quad(240, 1, 0)));
    }

    @Test
    void filtersAListKeepingItsOrder() {
        CardQuad a = quad(240, 88.0 / 63, 0);
        CardQuad b = quad(200, 88.0 / 63, 90);
        CardQuad c = quad(180, 88.0 / 63, 10);
        assertEquals(List.of(a, c), CardShape.acceptedOnly(List.of(a, b, c)));
    }
}
