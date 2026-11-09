package forge.cardocr.finder;

import java.util.*;

/**
 * What one find call produced.
 *
 * @param candidates best first, at most {@link FinderOptions#maxCandidates()}, possibly empty
 * @param searchNanos time spent looking for rough rectangles, including downsampling for it
 * @param refineNanos time spent fitting edges to the rough rectangles
 * @param elapsedNanos the whole call, measured on the calling thread
 * @param local true if this only refitted known outlines, so an outline missing from it says nothing
 */
public record FindResult(
        List<CardQuad> candidates, long searchNanos, long refineNanos, long elapsedNanos, boolean local) {

    public FindResult(List<CardQuad> candidates, long searchNanos, long refineNanos, long elapsedNanos) {
        this(candidates, searchNanos, refineNanos, elapsedNanos, false);
    }


    public double elapsedMillis() {
        return elapsedNanos / 1e6;
    }
}
