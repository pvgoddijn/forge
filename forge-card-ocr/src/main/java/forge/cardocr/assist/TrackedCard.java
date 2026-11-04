package forge.cardocr.assist;

import forge.cardocr.finder.*;

/**
 * The outline the tracker currently believes is the card.
 *
 * @param id stays the same while the same outline is followed
 * @param quad the smoothed outline, for showing and for decisions
 * @param firstSeenMs when this outline first appeared
 * @param steadySinceMs when it last moved beyond the steadiness tolerance, after motion compensation
 * @param seenNow false while the tracker is bridging a gap, when the frame may not show the card
 * @param seenQuad this frame's own fit of the outline, for measuring this frame's pixels. When the
 *     frame only offered another outline of the card, this is the held outline. Null in a gap
 * @param outlineRevision changes when the outline was reshaped to another outline of the same card,
 *     which isn't the card moving
 */
public record TrackedCard(int id, CardQuad quad, long firstSeenMs, long steadySinceMs, boolean seenNow, CardQuad seenQuad, int outlineRevision) {

    public TrackedCard(int id, CardQuad quad, long firstSeenMs, long steadySinceMs, boolean seenNow) {
        this(id, quad, firstSeenMs, steadySinceMs, seenNow, seenNow ? quad : null, 0);
    }

    public long steadyForMs(long nowMs) {
        return nowMs - steadySinceMs;
    }
}
