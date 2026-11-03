package forge.cardocr.assist;

import forge.cardocr.finder.*;
import java.util.*;

/**
 * Whether an outline is one the person can see. The preview fills a tall screen by cropping, so the camera
 * sees far more than the screen shows: on the test phone only the middle 60% of the frame's short side.
 */
public final class OnScreen {

    /** Guess: how far past the screen's edge a corner may be, as a share of that side, for a fit that's slightly out. */
    static final double MARGIN = 0.03;

    private OnScreen() {
    }

    /** Whether the crosshair, at the screen's middle, is on the outline. Aiming picks the card. @param corners in view pixels, in order around it */
    public static boolean underCrosshair(List<Corner> corners, double viewWidth, double viewHeight) {
        double x = viewWidth / 2;
        double y = viewHeight / 2;
        boolean left = false;
        boolean right = false;
        for (int i = 0; i < corners.size(); i++) {
            Corner a = corners.get(i);
            Corner b = corners.get((i + 1) % corners.size());
            double side = (b.x() - a.x()) * (y - a.y()) - (b.y() - a.y()) * (x - a.x());
            left |= side > 0;
            right |= side < 0;
        }
        return !(left && right);
    }

    /** @param corners in view pixels */
    public static boolean contains(List<Corner> corners, double viewWidth, double viewHeight) {
        for (Corner corner : corners) {
            if (corner.x() < -MARGIN * viewWidth || corner.x() > viewWidth * (1 + MARGIN)
                    || corner.y() < -MARGIN * viewHeight || corner.y() > viewHeight * (1 + MARGIN)) {
                return false;
            }
        }
        return true;
    }
}
