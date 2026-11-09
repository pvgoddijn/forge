package forge.cardocr.finder;

import java.util.*;

/**
 * What Assisted mode accepts as a card: upright on the portrait screen, turned at most 45 degrees, with a
 * card's proportions. A card's art box has nearly the same proportions turned sideways, and this is what
 * rules it out. The price is that a card lying sideways on the table has to be photographed turned.
 *
 * <p>Coordinates are the camera's: analysis frames and stored photos are landscape, so the screen's
 * vertical is their x axis. Either way up counts, as outlines can't tell.
 */
public final class CardShape {

    /** A card is 88 by 63 mm. */
    static final double CARD_RATIO = 88.0 / 63.0;
    /** 28 real outlines from the phone ran from 1.27 to 1.47, the phone's tilt shortening or lengthening them. */
    static final double RATIO_TOLERANCE = 0.10;
    static final double MAX_TURN_DEGREES = 45;

    private CardShape() {
    }

    public static boolean accepted(CardQuad q) {
        return turnDegrees(q) <= MAX_TURN_DEGREES && Math.abs(ratio(q) / CARD_RATIO - 1) <= RATIO_TOLERANCE;
    }

    public static List<CardQuad> acceptedOnly(List<CardQuad> quads) {
        List<CardQuad> out = new ArrayList<>();
        for (CardQuad quad : quads) {
            if (accepted(quad)) {
                out.add(quad);
            }
        }
        return out;
    }

    /** Long side over short side, each the mean of its two opposite sides, which evens out perspective. */
    static double ratio(CardQuad q) {
        double longSide = (q.c0().distanceTo(q.c3()) + q.c1().distanceTo(q.c2())) / 2;
        double shortSide = (q.c0().distanceTo(q.c1()) + q.c3().distanceTo(q.c2())) / 2;
        return longSide / shortSide;
    }

    /** How far the long side is turned from the screen's vertical, from 0 to 90. */
    static double turnDegrees(CardQuad q) {
        double dx = (q.c3().x() - q.c0().x() + q.c2().x() - q.c1().x()) / 2;
        double dy = (q.c3().y() - q.c0().y() + q.c2().y() - q.c1().y()) / 2;
        return Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));
    }
}
