package forge.gamemodes.companion;

import java.util.Optional;

/**
 * Whether a photo's reading is sure enough to add its card without asking (auto mode, AC-9).
 * <p>
 * The card it adds is always the reader's own first card, {@code reading.cards().get(0)}. The photo dialog shows
 * {@link CardNameMatcher#mergeReading}'s list, which puts an exact match for the text read first and moves the active
 * deck's cards up, so its entries may be cards the reader ranked lower or didn't suggest at all, and its first may not
 * be the reader's first. That's fine as a suggestion to tap, but never something to add untouched: pass the reader's
 * {@link CardNameReader.Reading} here, not the merged list, and add only the card this returns.
 * <p>
 * <b>Threshold: unmeasured, needs the owner's photo set.</b> {@link #THRESHOLD} and {@link #MIN_LEAD} are conservative
 * placeholders, not values shown to keep wrong auto-adds at 1 in 100. Reading the labelled photos there are with the
 * shipped models and the phone budget (2026-11, top score {@code s1}, second {@code s2}):
 * <ul>
 * <li>30 real photos (the OCR spike's, which tuned the pipeline) and 61 phone captures (about 12 cards, many repeats),
 * read from the file with no outline: 73 confident, 3 of them wrong, all near ties ({@code s1 - s2} at most 0.02,
 * {@code s1} at most 0.19). At 0.50 with a lead of 0.30: 51 auto-adds, all right, of 70 right confident reads. 0 wrong
 * in 51 only bounds the rate below about 6 in 100.</li>
 * <li>The same 60 captures read inside the camera's outline: the same 2 near-tie wrongs.</li>
 * <li>312 synthetic photos, each with neighbouring cards and read with no outline: at 0.50 with a lead of 0.30, 232
 * auto-adds and 4 wrong (1.7 %); even at 1.00 with a lead of 0.50, 2 wrong in 98. The wrongs scoring above 1 are
 * neighbours' titles read clearly, which the camera's outline keeps out on the phone but a file on the dev build
 * doesn't.</li>
 * </ul>
 * So the lead check catches every wrong seen on real photos, and no score alone reaches 1 in 100 on the synthetic
 * set. Set the values from about 100 or more labelled phone captures of different cards before relying on them.
 */
public final class AutoAddPolicy {
    /** The best match's reader score must be at least this. Unmeasured placeholder, see above. */
    public static final double THRESHOLD = 0.50;
    /** And at least this far ahead of the second's. Unmeasured placeholder, see above. */
    public static final double MIN_LEAD = 0.30;

    private AutoAddPolicy() {
    }

    /**
     * @param reading what the reader said, as it said it, not re-ordered
     * @param threshold the lowest {@link CardNameReader.Reading#bestScore()} to add at
     * @return the reader's first card if the reading is confident, its score is at or above {@code threshold}, and it
     *     leads the second card's score by at least {@link #MIN_LEAD} (when there is a second score); otherwise empty,
     *     and the operator confirms as in confirm mode
     */
    public static Optional<String> cardToAutoAdd(CardNameReader.Reading reading, double threshold) {
        if (!reading.confident() || reading.cards().isEmpty() || !(reading.bestScore() >= threshold)) {
            return Optional.empty();
        }
        double second = reading.secondScore();
        if (!Double.isNaN(second) && reading.bestScore() - second < MIN_LEAD) {
            return Optional.empty();
        }
        return Optional.of(reading.cards().get(0));
    }
}
