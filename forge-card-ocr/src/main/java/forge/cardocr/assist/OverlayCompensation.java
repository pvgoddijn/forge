package forge.cardocr.assist;

import forge.cardocr.finder.*;

/**
 * Applies gyroscope motion to a shown outline only when the motion means something. In run3.log, with
 * under 2 pixels predicted, compensating left about the same error as not (2.8 to 2.7, 2.6 to 2.3 px),
 * while from 4 pixels it clearly helped (6.3 to 2.6, 17.4 to 3.2 px). It fades in, so it can't cause a step.
 */
public final class OverlayCompensation {

    static final double NONE_BELOW_PX = 2;
    static final double FULL_FROM_PX = 4;

    private OverlayCompensation() {
    }

    public static CardQuad apply(CardQuad quad, ImageMotion motion) {
        if (motion == ImageMotion.NONE) {
            return quad;
        }
        CardQuad moved = motion.moved(quad);
        double shift = Quads.centre(moved).distanceTo(Quads.centre(quad));
        double share = Math.max(0, Math.min(1, (shift - NONE_BELOW_PX) / (FULL_FROM_PX - NONE_BELOW_PX)));
        return share == 0 ? quad : Quads.between(quad, moved, share);
    }
}
