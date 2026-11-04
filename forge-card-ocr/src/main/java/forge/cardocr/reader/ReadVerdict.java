package forge.cardocr.reader;

import java.util.*;

/** Whether a read found anything worth showing as a suggestion. */
public final class ReadVerdict {

    /**
     * Guess from 33 labelled phone captures read only inside the card: right answers scored 0.14 or more, while wrong answers
     * and scenes with no card stayed below 0.10. Too few captures to treat it as measured.
     */
    static final double MIN_TOP_SCORE = 0.10;

    private ReadVerdict() {
    }

    public static boolean unreadable(List<ReadResult.Match> matches) {
        return topScore(matches) < MIN_TOP_SCORE;
    }

    /** The best match's score, or negative infinity with no match. */
    public static double topScore(List<ReadResult.Match> matches) {
        return matches.isEmpty() ? Double.NEGATIVE_INFINITY : matches.get(0).score();
    }
}
