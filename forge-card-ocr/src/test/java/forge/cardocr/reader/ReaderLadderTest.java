package forge.cardocr.reader;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** When the reader does more work, with made-up model output so nothing needs a model. */
class ReaderLadderTest {

    private static final List<String> DICT = List.of("p", "l", "a", "i", "n", "s", "w", "m");
    private static final CardNames NAMES = CardNames.of(List.of("Plains", "Swamp", "Island"));
    private static final CardQuad OUTLINE = new CardQuad(new Corner(10, 10), new Corner(110, 10), new Corner(110, 150),
            new Corner(10, 150), 1, true);

    /** Spells the text clearly, or gives every class the same odds when there's none. */
    private static final class FakeModels implements TextModels {
        final String text;
        int recognitions;
        int detections;

        FakeModels(String text) {
            this.text = text;
        }

        @Override
        public float[][] recognise(float[] input, int height, int width, Threads threads) {
            recognitions++;
            int classes = DICT.size() + 2;
            float[][] out = new float[20][classes];
            for (int t = 0; t < out.length; t++) {
                if (text == null) {
                    Arrays.fill(out[t], 1f / classes);
                    continue;
                }
                // Each character, then a blank.
                int position = t / 2;
                int symbol = t % 2 == 0 && position < text.length() ? DICT.indexOf(text.substring(position, position + 1)) + 1 : 0;
                out[t][symbol] = 1;
            }
            return out;
        }

        @Override
        public float[] detect(float[] input, int height, int width, Threads threads) {
            detections++;
            return new float[height * width];
        }
    }

    private static ReadResult read(FakeModels models, ReadBudget budget, CardQuad tracked) {
        CardReader reader = new CardReader(models, NAMES, new NameTrie(NAMES), new CharClasses(DICT));
        return reader.read(new ArgbImage(new int[160 * 200], 160, 200), budget, tracked);
    }

    private static final ReadBudget TRUSTING = ReadBudget.C_TWO_ONE.trustingTracked("t");

    @Test
    void aClearTitleFromTheTrackedOutlineNeedsNothingElse() {
        FakeModels models = new FakeModels("plains");
        ReadResult result = read(models, TRUSTING, OUTLINE);
        assertTrue(result.usedTracked());
        assertFalse(result.finderRan());
        assertFalse(result.wholeUsed());
        assertEquals(2, models.recognitions);
        assertEquals(0, models.detections);
        assertEquals("Plains", result.matches().get(0).name());
        assertEquals("plains", result.textRead());
    }

    @Test
    void aWeakTitleEscalatesToTheFinderAndTheWholeImage() {
        FakeModels models = new FakeModels(null);
        ReadResult result = read(models, TRUSTING, OUTLINE);
        assertTrue(result.finderRan());
        assertTrue(result.wholeUsed());
        assertEquals(2, models.detections, "the flattened card's text, then the whole image's");
        assertNotNull(result.card(), "the tracked outline is still shown");
    }

    private static ReadResult readCardOnly(FakeModels models, CardQuad tracked) {
        CardReader reader = new CardReader(models, NAMES, new NameTrie(NAMES), new CharClasses(DICT));
        return reader.readCardOnly(new ArgbImage(new int[160 * 200], 160, 200), TRUSTING, tracked);
    }

    @Test
    void cardOnlyKeepsTheTrackedOutlineWhenTheFinderSeesNothingThere() {
        FakeModels models = new FakeModels(null);
        ReadResult result = readCardOnly(models, OUTLINE);
        assertTrue(result.finderRan());
        assertTrue(result.wholeUsed());
        assertEquals(1, models.detections);
        assertEquals(OUTLINE, result.card());
    }

    @Test
    void cardOnlyNeedsNoSecondPassForAClearTitle() {
        FakeModels models = new FakeModels("plains");
        ReadResult result = readCardOnly(models, OUTLINE);
        assertFalse(result.wholeUsed());
        assertEquals(0, models.detections);
    }

    @Test
    void cardOnlyWithNoOutlineAndNoCardFoundReadsNothing() {
        FakeModels models = new FakeModels(null);
        ReadResult result = readCardOnly(models, null);
        assertTrue(result.finderRan());
        assertFalse(result.wholeUsed());
        assertEquals(0, models.detections);
        assertTrue(ReadVerdict.unreadable(result.matches()));
    }

    @Test
    void withNoTrackedOutlineTheFinderRunsFirst() {
        ReadResult result = read(new FakeModels("plains"), TRUSTING, null);
        assertFalse(result.usedTracked());
        assertTrue(result.finderRan());
    }

    @Test
    void aBudgetNotTrustingTheOutlineIgnoresIt() {
        ReadResult result = read(new FakeModels("plains"), ReadBudget.C_TWO_ONE, OUTLINE);
        assertFalse(result.usedTracked());
        assertTrue(result.finderRan());
    }

    @Test
    void weaknessComesFromTheAlignmentOrTheMargin() {
        assertFalse(new TitleStrength(.3, -.5, .3, true).weak(), "a short name's low score isn't weak");
        assertTrue(new TitleStrength(1.2, -3, .3, false).weak());
        assertTrue(new TitleStrength(1.2, -.5, .01, false).weak());
        assertTrue(TitleStrength.NONE.weak());
    }
}
