package forge.cardocr.reader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

/** Name scoring, with made-up frames so nothing needs a model. */
class ScorerTest {

    private static final float LOW = (float) Math.log(1e-5);

    private static float[][] frames(String pattern) {
        float[][] m = new float[pattern.length()][CharClasses.COUNT];
        for (int t = 0; t < m.length; t++) {
            Arrays.fill(m[t], LOW);
            char c = pattern.charAt(t);
            m[t][c == '_' ? CharClasses.BLANKISH : CharClasses.symbolOf(c)] = 0;
        }
        return m;
    }

    private static List<String> top(CardNames names, float[][] m, float beta) {
        BestMatches best = new BestMatches(names.size(), 3);
        new NameScorer(new NameTrie(names)).score(m, beta, 0, 0, best);
        List<String> out = new ArrayList<>();
        for (BestMatches.Entry e : best.top()) {
            out.add(names.name(e.card()));
        }
        return out;
    }

    /** The plain CTC recurrence for one name, without a trie or pruning. */
    private static float bruteScore(String name, float[][] m, float beta) {
        int frames = m.length;
        float[] b = new float[frames];
        float[] c = new float[frames];
        float run = 0;
        for (int t = 0; t < frames; t++) {
            run += m[t][CharClasses.BLANKISH];
            b[t] = run;
            c[t] = -1e9f;
        }
        int previous = -1;
        for (int i = 0; i < name.length(); i++) {
            int ch = CharClasses.symbolOf(name.charAt(i));
            float[] nc = new float[frames];
            float[] nb = new float[frames];
            for (int t = 0; t < frames; t++) {
                float pc = t > 0 ? nc[t - 1] : -1e9f;
                float pb = t > 0 ? b[t - 1] : (previous < 0 ? 0 : -1e9f);
                float pp = t > 0 && ch != previous ? c[t - 1] : -1e9f;
                nc[t] = m[t][ch] + Math.max(pc, Math.max(pb, pp));
                float a = t > 0 ? nb[t - 1] : -1e9f;
                float d = t > 0 ? nc[t - 1] : -1e9f;
                nb[t] = m[t][CharClasses.BLANKISH] + Math.max(a, d);
            }
            c = nc;
            b = nb;
            previous = ch;
        }
        return (Math.max(c[frames - 1], b[frames - 1]) + beta * name.length()) / frames;
    }

    @Test
    void trieKeepsEveryNameAndSharesPrefixes() {
        CardNames names = CardNames.of(List.of("Plains", "Plain", "Plague Rats", "Island"));
        NameTrie trie = new NameTrie(names);
        // island + pla + in + s + gue rats
        assertEquals(6 + 3 + 2 + 1 + 7, trie.nodeCount());
        Set<Integer> seen = new HashSet<>();
        for (int n = 0; n < trie.nodeCount(); n++) {
            if (trie.card(n) >= 0) {
                assertEquals(names.normalised(trie.card(n)).length(), trie.depth(n));
                seen.add(trie.card(n));
            }
        }
        assertEquals(4, seen.size());
    }

    @Test
    void normalisingIgnoresCaseAccentsAndPunctuation() {
        assertEquals("limdulsvault", CardNames.normalise("Lim-Dûl's Vault"));
        assertEquals("aothedawnsky", CardNames.normalise("Ao, the Dawn Sky"));
        assertEquals("fireice", CardNames.normalise("Fire // Ice"));
    }

    @Test
    void namesNormalisedAlikeCountOnceAndTooShortOnesAreDropped() {
        CardNames names = CardNames.of(List.of("Plains", "PLAINS!", "X"));
        assertEquals(1, names.size());
    }

    @Test
    void clearReadingFindsItsName() {
        CardNames names = CardNames.of(List.of("Plains", "Island", "Swamp", "Mountain", "Forest"));
        assertEquals("Plains", top(names, frames("_pp_lainn_s_"), 3f).get(0));
    }

    @Test
    void repeatedLetterNeedsASeparatingBlank() {
        CardNames names = CardNames.of(List.of("ab", "aab"));
        assertEquals("ab", top(names, frames("aab__"), 0f).get(0));
        assertEquals("aab", top(names, frames("a_ab_"), 0f).get(0));
    }

    /** Pruning must not change the result, so the top list is compared with scoring every name. */
    @Test
    void prunedTopListEqualsScoringEveryName() {
        Random random = new Random(7);
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            StringBuilder name = new StringBuilder();
            int length = 2 + random.nextInt(22);
            for (int j = 0; j < length; j++) {
                name.append((char) ('a' + random.nextInt(i % 2 == 0 ? 6 : 26)));
            }
            raw.add(name.toString());
        }
        CardNames names = CardNames.of(raw);
        NameTrie trie = new NameTrie(names);
        for (int round = 0; round < 60; round++) {
            int frames = 8 + random.nextInt(50);
            float[][] m = new float[frames][CharClasses.COUNT];
            for (float[] frame : m) {
                float[] p = new float[CharClasses.COUNT];
                float sum = 0;
                for (int i = 0; i < p.length; i++) {
                    p[i] = (float) Math.pow(random.nextFloat(), 8);
                    sum += p[i];
                }
                for (int i = 0; i < p.length; i++) {
                    frame[i] = (float) Math.log(p[i] / sum + 1e-5f);
                }
            }
            float beta = round % 3 == 0 ? 0f : round % 3 == 1 ? 2f : 3f;
            BestMatches pruned = new BestMatches(names.size(), 3);
            new NameScorer(trie).score(m, beta, 0.25f, 0, pruned);

            float[] brute = new float[names.size()];
            Integer[] order = new Integer[brute.length];
            for (int c = 0; c < brute.length; c++) {
                brute[c] = bruteScore(names.normalised(c), m, beta) - 0.25f;
                order[c] = c;
            }
            Arrays.sort(order, (a, b) -> Float.compare(brute[b], brute[a]));
            List<BestMatches.Entry> got = pruned.top();
            assertEquals(3, got.size());
            for (int i = 0; i < 3; i++) {
                assertEquals(brute[order[i]], got.get(i).score(), 1e-3f, "round " + round + " rank " + i);
            }
        }
    }

    @Test
    void pruningSkipsPartOfATrie() {
        List<String> raw = new ArrayList<>();
        Random random = new Random(3);
        for (int i = 0; i < 5000; i++) {
            StringBuilder name = new StringBuilder();
            for (int j = 0; j < 14; j++) {
                name.append((char) ('a' + random.nextInt(26)));
            }
            raw.add(name.toString());
        }
        raw.add("plains");
        CardNames names = CardNames.of(raw);
        NameTrie trie = new NameTrie(names);
        NameScorer scorer = new NameScorer(trie);
        BestMatches best = new BestMatches(names.size(), 3);
        scorer.score(frames("__p_l_a_i_n_s____"), 3f, 0, 0, best);
        assertEquals("plains", names.name(best.top().get(0).card()));
        assertTrue(scorer.visitedNodes() < trie.nodeCount(),
                "visited " + scorer.visitedNodes() + " of " + trie.nodeCount());
    }

    @Test
    void mergeKeepsEachCardsBestAndItsOrigin() {
        BestMatches best = new BestMatches(5, 3);
        assertEquals(Float.NEGATIVE_INFINITY, best.floor());
        best.offer(0, 1f, 10);
        best.offer(1, 2f, 11);
        best.offer(2, 3f, 12);
        assertEquals(1f, best.floor());
        best.offer(3, 0.5f, 13);
        best.offer(0, 4f, 14);
        best.offer(4, 2.5f, 15);
        best.offer(0, 3.5f, 16);
        List<BestMatches.Entry> top = best.top();
        assertEquals(List.of(0, 2, 4), top.stream().map(BestMatches.Entry::card).toList());
        assertEquals(14, top.get(0).origin());
        assertEquals(2.5f, best.floor());
    }

    @Test
    void reduceCollapsesCaseAndPunctuationAndGreedyDropsRepeats() {
        CharClasses classes = new CharClasses(List.of("a", "A", "b", "!"));
        // Labels: blank, a, A, b, !, space.
        float[][] raw = {{0, .5f, .5f, 0, 0, 0}, {0, 0, 1, 0, 0, 0}, {1, 0, 0, 0, 0, 0}, {0, 0, 0, 1, 0, 0}, {0, 0, 0, 0, 1, 0}};
        float[][] m = classes.reduce(raw);
        assertEquals(Math.log(1 + 1e-5f), m[0][0], 1e-3);
        assertEquals(Math.log(1 + 1e-5f), m[4][CharClasses.BLANKISH], 1e-3);
        assertEquals("aAb!", classes.greedy(new float[][] {{0, 1, 0, 0, 0, 0}, {0, 0, 1, 0, 0, 0}, {0, 0, 1, 0, 0, 0}, {1, 0, 0, 0, 0, 0},
                {0, 0, 0, 1, 0, 0}, {0, 0, 0, 0, 1, 0}}));
    }

    @Test
    void confidenceIsZeroBelowTwoCharacters() {
        assertEquals(0, CharClasses.confidence(new float[][] {{0, 1}, {1, 0}}));
        assertTrue(CharClasses.confidence(new float[][] {{0, 1, 0}, {0, 0, 1}}) > 0);
    }
}
