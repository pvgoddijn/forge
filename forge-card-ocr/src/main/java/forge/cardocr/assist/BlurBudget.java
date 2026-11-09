package forge.cardocr.assist;

/**
 * How fast the phone may turn during a still, from the blur that causes rather than a guess at what a
 * steady hand is. Blur in still pixels is angular speed x exposure x focal length in pixels.
 *
 * <p>A card filling the frame has a title cap height of about 50 still pixels, with strokes about 6
 * pixels wide, so {@link #BUDGET_PX} smears a letter by about one stroke. That's the reasoning, not a
 * measurement of OCR. Optical stabilisation should remove part of this blur, and no credit is taken for it.
 */
public final class BlurBudget {

    /** Focal length in pixels of the 4096-wide still on the measured phone (5.56 mm on an 8.192 mm sensor is 2780). */
    static final double FOCAL_PX = 2780;
    static final double BUDGET_PX = 6;
    /** Assumed until the camera reports an exposure: 1/60 s, which gives a limit of 0.13 rad/s. */
    static final long DEFAULT_EXPOSURE_NS = 16_666_667;
    /**
     * Dim rooms expose for longer, but a limit under a hand's usual 0.04 to 0.1 rad/s would never be met. So at
     * 1/30 s this accepts about 9 pixels, and relies on stabilisation and on a retake when the title can't be read.
     */
    static final double MIN_LIMIT_RAD_S = 0.10;
    /** Beyond this the phone is being moved, however short the exposure. */
    static final double MAX_LIMIT_RAD_S = 0.25;

    private BlurBudget() {
    }

    /** @param exposureNs the camera's latest exposure, or null if not known */
    public static double limitRadS(Long exposureNs) {
        long exposure = exposureNs == null || exposureNs <= 0 ? DEFAULT_EXPOSURE_NS : exposureNs;
        return Math.max(MIN_LIMIT_RAD_S, Math.min(MAX_LIMIT_RAD_S, BUDGET_PX / (exposure / 1e9 * FOCAL_PX)));
    }

    public static double blurPx(double speedRadS, long exposureNs) {
        return speedRadS * (exposureNs / 1e9) * FOCAL_PX;
    }
}
