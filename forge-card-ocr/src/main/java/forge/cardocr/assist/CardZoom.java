package forge.cardocr.assist;

import forge.cardocr.Dim;
import forge.cardocr.finder.*;

/**
 * The camera crop for a photo of one card. This phone's cropping type is CENTER_ONLY, so the crop stays
 * centred on the sensor and has to reach the card from there: a card off to one side gains less.
 */
public final class CardZoom {

    /** Guess: room around the card, as the hand moves between the last frame and the exposure. 1.15 cut off one card's title in 10. */
    static final double MARGIN = 1.25;

    private CardZoom() {
    }

    /** @return a centred crop with the sensor's proportions holding the card and margin, or null if that's the whole sensor */
    public static ActiveRect region(CardQuad quad, Dim analysis, ActiveRect analysisCrop, Dim activeArray) {
        double midX = activeArray.width() / 2.0;
        double midY = activeArray.height() / 2.0;
        double aspect = (double) activeArray.width() / activeArray.height();
        double halfWidth = 0;
        for (Corner corner : quad.corners()) {
            Corner active = SensorGeometry.streamToActive(corner, analysis, analysisCrop);
            halfWidth = Math.max(halfWidth, Math.abs(active.x() - midX));
            halfWidth = Math.max(halfWidth, Math.abs(active.y() - midY) * aspect);
        }
        halfWidth *= MARGIN;
        if (halfWidth >= midX) {
            return null;
        }
        double halfHeight = halfWidth / aspect;
        return new ActiveRect(midX - halfWidth, midY - halfHeight, 2 * halfWidth, 2 * halfHeight);
    }
}
