package forge.cardocr.reader;

/**
 * Measures of how good the best title match is, used to decide whether to read more.
 *
 * @param score the match's score. It grows with the name's length, so short names look weak on it
 * @param logPerChar log probability of the match's alignment per name character, unaffected by length
 * @param margin how far the score is ahead of the runner-up's
 * @param exact whether the plain reading, normalised, is exactly this name
 */
public record TitleStrength(double score, double logPerChar, double margin, boolean exact) {

    static final double WEAK_LOG_PER_CHAR = -2;
    static final double WEAK_MARGIN = 0.1;

    /** With no title reading at all. */
    public static final TitleStrength NONE = new TitleStrength(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, false);

    /**
     * Whether reading more is worthwhile. The score itself can't say: it runs from about 0.3 for a
     * correct short name to 1.3 for a long one. See the README for how these were chosen.
     */
    public boolean weak() {
        return logPerChar < WEAK_LOG_PER_CHAR || margin < WEAK_MARGIN;
    }
}
