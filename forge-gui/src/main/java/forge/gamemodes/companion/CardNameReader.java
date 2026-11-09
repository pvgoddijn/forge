package forge.gamemodes.companion;

import java.util.List;

/**
 * Reads a card's printed name from a photo, on the device and offline (AC-7). The implementation is in
 * {@code forge-card-ocr}, which only the Android and desktop dev backends depend on, so the native OCR code stays out
 * of {@code forge-gui-mobile} and the iOS build. A backend hands its reader over through {@link CardCamera#reader()}.
 */
public interface CardNameReader {
    /**
     * What a photo said.
     *
     * @param textRead the text read from the card's title, perhaps partial; empty if none was read
     * @param cards the best matching card names, best first, at most {@value #MAX_SUGGESTIONS} (the OCR reader keeps
     *     3: the best and 2 alternatives); names of Forge card
     *     faces, so a double-faced card can come from either face (AC-13)
     * @param confident whether the best match can be offered as the answer (AC-8). If not, the operator gets the
     *     name search with {@code textRead} filled in (AC-11)
     * @param bestScore the reader's score for the first of {@code cards}, 0 if there is none. Higher is surer; the OCR
     *     reader's scores run from below 0 to about 2, and {@code confident} is its own cut-off on this score. Auto mode
     *     sets its threshold on it (slice 7)
     * @param secondScore the reader's score for the second of {@code cards}, or {@link Double#NaN} if there is none
     */
    record Reading(String textRead, List<String> cards, boolean confident, double bestScore, double secondScore) {
        public static final Reading NOTHING = new Reading("", List.of(), false, 0, Double.NaN);

        public Reading {
            cards = List.copyOf(cards);
        }
    }

    /** The best card and up to 3 alternatives (AC-8). */
    int MAX_SUGGESTIONS = 4;

    /**
     * Reads the card in {@code photo}. Slow, and loads the models the first time: call it off the UI thread. A
     * temporary photo is deleted afterwards, whatever happens.
     */
    Reading read(CardPhoto photo);
}
