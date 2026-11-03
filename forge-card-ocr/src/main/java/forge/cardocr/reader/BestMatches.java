package forge.cardocr.reader;

import java.util.*;

/**
 * The best score seen per card, keeping the top few at hand. Scores from every reading of a photo
 * merge here by taking each card's maximum.
 */
public final class BestMatches {

    /** Where a score came from: an index given by the caller. */
    public record Entry(int card, float score, int origin) {
    }

    private final float[] best;
    private final int[] origin;
    private final int[] top;
    private int filled;

    public BestMatches(int cards, int keep) {
        best = new float[cards];
        origin = new int[cards];
        Arrays.fill(best, Float.NEGATIVE_INFINITY);
        top = new int[keep];
    }

    /**
     * A score at or below this can't change the top list, so scoring may skip it. Negative infinity
     * until the list is full.
     */
    public float floor() {
        return filled < top.length ? Float.NEGATIVE_INFINITY : best[top[filled - 1]];
    }

    public void offer(int card, float score, int from) {
        if (score <= best[card]) {
            return;
        }
        best[card] = score;
        origin[card] = from;
        int at = -1;
        for (int i = 0; i < filled; i++) {
            if (top[i] == card) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            if (filled < top.length) {
                at = filled++;
            } else if (score > best[top[filled - 1]]) {
                at = filled - 1;
            } else {
                return;
            }
            top[at] = card;
        }
        while (at > 0 && best[top[at - 1]] < score) {
            int swap = top[at - 1];
            top[at - 1] = top[at];
            top[at] = swap;
            at--;
        }
    }

    /** Best first. */
    public List<Entry> top() {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < filled; i++) {
            out.add(new Entry(top[i], best[top[i]], origin[top[i]]));
        }
        return out;
    }
}
