package forge.cardocr.assist;

import forge.cardocr.finder.*;
import java.util.*;

/**
 * Decides whether a frame gets a full search or only a refit around outlines already known. Without
 * seeds, after a refit lost the main outline, or when it's time to look for other cards, it's a search.
 */
public final class LocalTracking {

    /** With a card held, how long between looks for anything else. */
    static final long HELD_SEARCH_PERIOD_MS = 500;
    /** Without one, sooner, because a card is only confirmed after a second search agrees. */
    static final long CANDIDATE_SEARCH_PERIOD_MS = 250;
    /** A fit this close to its seed, as a share of the long side, is that outline. */
    private static final double SAME_OUTLINE = 0.07;

    private long lastSearchEndMs = -1;
    private boolean lost;

    public boolean useLocal(List<CardQuad> seeds, boolean holding, long nowMs) {
        return !seeds.isEmpty() && !lost && lastSearchEndMs >= 0
                && nowMs - lastSearchEndMs < (holding ? HELD_SEARCH_PERIOD_MS : CANDIDATE_SEARCH_PERIOD_MS);
    }

    public void onResult(FindResult found, List<CardQuad> seeds, long nowMs) {
        if (!found.local()) {
            lastSearchEndMs = nowMs + Math.round(found.elapsedMillis());
            lost = false;
            return;
        }
        CardQuad main = seeds.get(0);
        lost = found.candidates().stream().noneMatch(c -> Quads.distance(c, main) <= SAME_OUTLINE * Quads.longSide(main));
    }
}
