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
     */
    record Reading(String textRead, List<String> cards, boolean confident) {
        public static final Reading NOTHING = new Reading("", List.of(), false);

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
