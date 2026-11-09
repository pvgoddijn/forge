package forge.cardocr;

import java.util.*;

/** Chooses camera stream sizes from what the device offers. */
public final class StreamSizes {

    /** Sizes whose aspect ratios differ by less than this count as the same ratio. */
    private static final double ASPECT_TOLERANCE = 0.01;

    private StreamSizes() {
    }

    /** The largest size by area: the full-resolution still size. */
    public static Dim largest(List<Dim> sizes) {
        return Collections.max(sizes, Comparator.comparingLong(Dim::area));
    }

    /**
     * Picks a stream size that fits inside {@code bound} and shares the aspect ratio of
     * {@code target}, so the stream shows the same field of view as the still photo. Without an
     * exact ratio it takes the closest one. When nothing fits the bound it takes the smallest size.
     */
    public static Dim select(List<Dim> sizes, Dim target, Dim bound) {
        List<Dim> fitting = new ArrayList<>();
        for (Dim size : sizes) {
            if (size.longSide() <= bound.longSide() && size.shortSide() <= bound.shortSide()) {
                fitting.add(size);
            }
        }
        if (fitting.isEmpty()) {
            return Collections.min(sizes, Comparator.comparingLong(Dim::area));
        }

        double closest = Double.MAX_VALUE;
        for (Dim size : fitting) {
            closest = Math.min(closest, Math.abs(size.aspect() - target.aspect()));
        }
        Dim best = null;
        for (Dim size : fitting) {
            boolean sameRatio = Math.abs(size.aspect() - target.aspect()) <= closest + ASPECT_TOLERANCE;
            if (sameRatio && (best == null || size.area() > best.area())) {
                best = size;
            }
        }
        return best;
    }
}
