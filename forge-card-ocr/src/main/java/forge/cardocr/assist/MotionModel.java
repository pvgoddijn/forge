package forge.cardocr.assist;

/**
 * The gyroscope history and the current mapping into the analysis frame. All times are nanoseconds
 * on the elapsedRealtimeNanos clock.
 */
public final class MotionModel {

    /** Angular speed is averaged this long, so one noisy sample doesn't decide. */
    static final long SPEED_WINDOW_NS = 100_000_000L;
    static final long PEAK_WINDOW_NS = 500_000_000L;
    /** With no sample for this long the gyroscope counts as stopped, and outlines take over. */
    static final long STALE_NS = 250_000_000L;
    /** A longer interval between frames means a stall. Its rotation isn't worth trusting. */
    private static final long MAX_INTERVAL_NS = 1_000_000_000L;
    /** The overlay stops moving this long after its result, for when results have stopped coming. */
    static final long MAX_OVERLAY_AGE_NS = 600_000_000L;
    /** Guess: how old the shown preview is, when its own timestamp can't be used. */
    static final long PREVIEW_LAG_NS = 40_000_000L;

    private final GyroHistory history = new GyroHistory();
    private volatile GyroMapping mapping;

    public GyroHistory history() {
        return history;
    }

    /** @param latest null if the camera doesn't give what's needed, which leaves steadiness working */
    public void setMapping(GyroMapping latest) {
        mapping = latest;
    }

    public GyroMapping mapping() {
        return mapping;
    }

    /** @return null while the gyroscope is absent or has stopped */
    public FrameMotion forFrame(long previousFrameNs, long frameNs, long nowNs) {
        long latest = history.latestNs();
        if (latest < 0 || nowNs - latest > STALE_NS) {
            return null;
        }
        return new FrameMotion(between(previousFrameNs, frameNs), history.meanSpeed(latest, SPEED_WINDOW_NS),
                history.peakSpeed(latest, PEAK_WINDOW_NS));
    }

    /** The image motion over an interval. No motion if it can't be known. */
    public ImageMotion between(long fromNs, long toNs) {
        GyroMapping current = mapping;
        if (current == null || fromNs <= 0 || toNs <= fromNs || toNs - fromNs > MAX_INTERVAL_NS) {
            return ImageMotion.NONE;
        }
        GyroHistory.Rotation rotation = history.integrate(fromNs, toNs);
        return rotation == null ? ImageMotion.NONE : current.motion(rotation);
    }

    /**
     * Moves an outline from its finder frame to the preview frame on screen.
     *
     * @param previewNs the shown preview's time, or 0 if its clock doesn't match the gyroscope's
     */
    public ImageMotion forOverlay(long frameNs, long previewNs, long nowNs) {
        boolean previewUsable = previewNs > nowNs - PEAK_WINDOW_NS && previewNs <= nowNs;
        long target = previewUsable ? previewNs : nowNs - PREVIEW_LAG_NS;
        return between(frameNs, Math.min(target, frameNs + MAX_OVERLAY_AGE_NS));
    }

    /** Angular speed around a moment, with the peak before it. Both NaN if not known. */
    public PhotoMotion photoMotion(long atNs) {
        return new PhotoMotion(history.meanSpeed(atNs + SPEED_WINDOW_NS / 2, SPEED_WINDOW_NS),
                history.peakSpeed(atNs, PEAK_WINDOW_NS));
    }

    /** Angular speeds in rad/s at the moment of a photo. */
    public record PhotoMotion(double speedRadS, double peakBeforeRadS) {
    }
}
