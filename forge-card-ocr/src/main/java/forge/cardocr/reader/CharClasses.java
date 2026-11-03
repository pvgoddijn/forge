package forge.cardocr.reader;

import java.util.*;

/**
 * Turns the recogniser's per-frame output into what name matching needs. Its classes collapse to 26
 * letters, 10 digits and one "blankish" class holding blank, space and punctuation, which is why
 * matching ignores case and punctuation.
 */
public final class CharClasses {

    /** Letters then digits, then {@link #BLANKISH}. */
    public static final int COUNT = 37;
    public static final int BLANKISH = 36;
    private static final float LOG_FLOOR = 1e-5f;

    private final List<String> labels;
    private final int[] classOf;

    /** @param dictionary the model's characters, one per line, as in its dictionary file */
    public CharClasses(List<String> dictionary) {
        labels = new ArrayList<>(dictionary);
        labels.add(0, "");
        labels.add(" ");
        classOf = new int[labels.size()];
        for (int i = 0; i < classOf.length; i++) {
            String n = CardNames.normalise(labels.get(i));
            classOf[i] = n.length() == 1 ? symbolOf(n.charAt(0)) : BLANKISH;
        }
    }

    static int symbolOf(char normalised) {
        return Character.isDigit(normalised) ? 26 + normalised - '0' : normalised - 'a';
    }

    /** Log probabilities per frame and class. */
    public float[][] reduce(float[][] raw) {
        float[][] out = new float[raw.length][COUNT];
        float[] sum = new float[COUNT];
        for (int t = 0; t < raw.length; t++) {
            Arrays.fill(sum, 0);
            float[] frame = raw[t];
            for (int i = 0; i < frame.length && i < classOf.length; i++) {
                sum[classOf[i]] += frame[i];
            }
            for (int i = 0; i < COUNT; i++) {
                out[t][i] = (float) Math.log(sum[i] + LOG_FLOOR);
            }
        }
        return out;
    }

    /** The plain reading: the top class per frame, with repeats and blanks removed. */
    public String greedy(float[][] raw) {
        StringBuilder text = new StringBuilder();
        int last = 0;
        for (float[] frame : raw) {
            int top = topClass(frame);
            if (top != 0 && top != last && top < labels.size()) {
                text.append(labels.get(top));
            }
            last = top;
        }
        return text.toString();
    }

    /** How sure the reading is: its mean character probability, marked down below 12 characters. */
    public static double confidence(float[][] raw) {
        double sum = 0;
        int chars = 0;
        int last = 0;
        for (float[] frame : raw) {
            int top = topClass(frame);
            if (top != 0) {
                sum += frame[top];
                if (top != last) {
                    chars++;
                }
            }
            last = top;
        }
        return chars < 2 ? 0 : sum / chars * Math.sqrt(Math.min(chars, 12) / 12.0);
    }

    private static int topClass(float[] frame) {
        int top = 0;
        for (int i = 1; i < frame.length; i++) {
            if (frame[i] > frame[top]) {
                top = i;
            }
        }
        return top;
    }
}
