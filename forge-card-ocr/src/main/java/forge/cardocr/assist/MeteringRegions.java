package forge.cardocr.assist;

import forge.cardocr.Dim;
import forge.cardocr.finder.*;

/** Turns a card outline in an analysis frame into a metering rectangle for AF and AE. */
public final class MeteringRegions {

    /** Meter the middle of the card, so a tilted card's rectangle takes in less background. */
    static final double CARD_SHARE = 0.8;

    /** A rectangle in whole active-array pixels. */
    public record Region(int x, int y, int width, int height) {
    }

    private MeteringRegions() {
    }

    /**
     * @param crop the current SCALER_CROP_REGION. Regions outside it may be ignored, so the result
     *     is clamped to it
     * @return the region, or null if the card lies outside the crop
     */
    public static Region forCard(CardQuad quad, Dim analysis, ActiveRect crop, Dim activeArray) {
        Corner centre = Quads.centre(quad);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Corner corner : quad.corners()) {
            Corner shrunk = new Corner(centre.x() + (corner.x() - centre.x()) * CARD_SHARE,
                    centre.y() + (corner.y() - centre.y()) * CARD_SHARE);
            Corner active = SensorGeometry.streamToActive(shrunk, analysis, crop);
            minX = Math.min(minX, active.x());
            minY = Math.min(minY, active.y());
            maxX = Math.max(maxX, active.x());
            maxY = Math.max(maxY, active.y());
        }
        // The last valid coordinate is size - 1.
        double limitRight = Math.min(crop.left() + crop.width(), activeArray.width()) - 1;
        double limitBottom = Math.min(crop.top() + crop.height(), activeArray.height()) - 1;
        int left = (int) Math.ceil(Math.max(minX, crop.left()));
        int top = (int) Math.ceil(Math.max(minY, crop.top()));
        int right = (int) Math.floor(Math.min(maxX, limitRight));
        int bottom = (int) Math.floor(Math.min(maxY, limitBottom));
        if (right <= left || bottom <= top) {
            return null;
        }
        return new Region(left, top, right - left, bottom - top);
    }
}
