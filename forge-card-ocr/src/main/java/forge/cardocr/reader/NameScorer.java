package forge.cardocr.reader;

import java.util.*;

/**
 * Scores card names against one reading by finding each name's best CTC alignment, over a trie so
 * that names sharing a prefix share the work.
 *
 * <p>A name's score is {@code (log probability of its best alignment + beta * length) / frames}.
 * The length bonus stops short names always winning. Only names that could enter the current top
 * list matter, so a subtree is skipped once an upper limit on all its names falls below the list's
 * floor. The result's top list is exactly what scoring every name would give.
 *
 * <p>Holds working buffers, so use one instance per thread.
 */
public final class NameScorer {

    private static final float IMPOSSIBLE = -1e9f;

    private final NameTrie trie;
    private final float[][] charRow;
    private final float[][] blankRow;
    private float[] gainWithBonus = new float[0];
    private float[] gainPlain = new float[0];
    private long visited;

    public NameScorer(NameTrie trie) {
        this.trie = trie;
        charRow = new float[trie.maxDepth() + 1][];
        blankRow = new float[trie.maxDepth() + 1][];
    }

    /** Trie nodes evaluated since construction, to show what the pruning saved. */
    public long visitedNodes() {
        return visited;
    }

    /**
     * @param m log probabilities per frame and class, from {@link CharClasses#reduce}
     * @param beta the length bonus
     * @param penalty subtracted from every score here
     * @param origin recorded with each score offered
     */
    public void score(float[][] m, float beta, float penalty, int origin, BestMatches into) {
        int frames = m.length;
        if (frames == 0) {
            return;
        }
        prepare(frames);
        computeGains(m, frames, beta);

        float[] rootBlank = blankRow[0];
        float[] rootChar = charRow[0];
        float blankRun = 0;
        for (int t = 0; t < frames; t++) {
            blankRun += m[t][CharClasses.BLANKISH];
            rootBlank[t] = blankRun;
            rootChar[t] = IMPOSSIBLE;
        }

        int nodes = trie.nodeCount();
        int[] symbols = new int[trie.maxDepth() + 1];
        symbols[0] = -1;
        int node = 0;
        while (node < nodes) {
            visited++;
            int depth = trie.depth(node);
            int symbol = trie.symbol(node);
            symbols[depth] = symbol;
            float best = fillRows(m, frames, depth, symbol, symbols[depth - 1], beta, trie.deepest(node));
            int card = trie.card(node);
            float floorRaw = (into.floor() + penalty) * frames;
            if (card >= 0) {
                float end = Math.max(charRow[depth][frames - 1], blankRow[depth][frames - 1]) + beta * depth;
                into.offer(card, end / frames - penalty, origin);
                floorRaw = (into.floor() + penalty) * frames;
            }
            node = best <= floorRaw ? trie.subtreeEnd(node) : node + 1;
        }
    }

    /**
     * Fills this node's rows and returns an upper limit on the raw score of any name in its subtree.
     * After each frame, a further frame gains at most its best class, plus the bonus if it starts a
     * new character, and only as many new characters remain as the deepest name allows.
     */
    private float fillRows(float[][] m, int frames, int depth, int symbol, int previous, float beta, int deepest) {
        float[] c = charRow[depth];
        float[] b = blankRow[depth];
        float[] parentChar = charRow[depth - 1];
        float[] parentBlank = blankRow[depth - 1];
        boolean canFollowChar = symbol != previous;
        float bonusLeft = beta * (deepest - depth);
        float base = beta * depth;
        float limit = Float.NEGATIVE_INFINITY;

        float prevChar = IMPOSSIBLE;
        float prevBlank = IMPOSSIBLE;
        for (int t = 0; t < frames; t++) {
            float from;
            if (t == 0) {
                from = depth == 1 ? 0 : IMPOSSIBLE;
            } else {
                from = Math.max(prevChar, parentBlank[t - 1]);
                if (canFollowChar && parentChar[t - 1] > from) {
                    from = parentChar[t - 1];
                }
            }
            float[] frame = m[t];
            float charHere = frame[symbol] + from;
            float blankHere = frame[CharClasses.BLANKISH] + Math.max(prevBlank, prevChar);
            c[t] = charHere;
            b[t] = blankHere;
            prevChar = charHere;
            prevBlank = blankHere;

            float reached = Math.max(charHere, blankHere);
            float rest = Math.min(gainWithBonus[t + 1], gainPlain[t + 1] + bonusLeft);
            if (reached + rest > limit) {
                limit = reached + rest;
            }
        }
        return limit + base;
    }

    private void prepare(int frames) {
        if (gainWithBonus.length <= frames) {
            gainWithBonus = new float[frames + 1];
            gainPlain = new float[frames + 1];
        }
        for (int d = 0; d < charRow.length; d++) {
            if (charRow[d] == null || charRow[d].length < frames) {
                charRow[d] = new float[frames];
                blankRow[d] = new float[frames];
            }
        }
    }

    /** gain[t] limits what frames t onwards can add, with and without counting length bonuses. */
    private void computeGains(float[][] m, int frames, float beta) {
        gainWithBonus[frames] = 0;
        gainPlain[frames] = 0;
        for (int t = frames - 1; t >= 0; t--) {
            float top = Float.NEGATIVE_INFINITY;
            float[] frame = m[t];
            for (int i = 0; i < CharClasses.BLANKISH; i++) {
                if (frame[i] > top) {
                    top = frame[i];
                }
            }
            float blank = frame[CharClasses.BLANKISH];
            gainWithBonus[t] = gainWithBonus[t + 1] + Math.max(blank, top + beta);
            gainPlain[t] = gainPlain[t + 1] + Math.max(blank, top);
        }
    }
}
