package forge.gamemodes.companion;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The card names in a pasted decklist ({@link CompanionSetup.Seat#deckList()}), for ranking a player's deck first in
 * the card search (AC-12).
 * <p>
 * A small parser of its own: Forge's {@code DeckRecognizer} needs the whole card database loaded even to be built, and
 * {@code DeckImportController} needs the GUI and {@code FModel}. A name it gets wrong only ranks nothing, as names are
 * matched against the card database by {@link CardNameMatcher}, never added from here.
 * <p>
 * It reads lines such as "1 Sol Ring", "1x Sol Ring (CMR) 123", "Sol Ring", Forge's "1 Sol Ring|CMR|1", "CM: Krenko"
 * and "SB: 1 Shock"; section headers such as "Commander", "Deck", "[Main]" or "Sideboard (2)"; blank lines, and
 * comments starting with "#" or "//". The commander and main deck count; a sideboard or maybeboard doesn't, as those
 * cards aren't played. Forge's {@code .dck} metadata ("Name=...") is skipped.
 */
public final class DeckListNames {
    private static final Set<String> PLAYED_SECTIONS = Set.of("commander", "commanders", "deck", "main", "mainboard",
            "main deck", "companion", "planes", "schemes", "conspiracy", "attractions", "contraptions");
    private static final Set<String> UNPLAYED_SECTIONS = Set.of("sideboard", "side", "maybeboard", "maybe",
            "considering", "tokens", "metadata");
    private static final Pattern HEADER = Pattern.compile("^\\[?([A-Za-z ]+?)]?\\s*:?\\s*(\\(\\d+\\))?\\s*:?$");
    private static final Pattern PREFIX = Pattern.compile("^(SB|CM|MB)\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT = Pattern.compile("^\\d+\\s*x?\\s+", Pattern.CASE_INSENSITIVE);
    // "(CMR) 123", "[CMR]", "(CMR) 123 *F*" and the like at the end of the line
    private static final Pattern SET_AND_NUMBER = Pattern.compile("\\s+[(\\[][A-Za-z0-9_]{2,7}[)\\]].*$");
    private static final Pattern FOIL = Pattern.compile("\\s+\\*[A-Za-z]+\\*$");
    private static final Pattern SINGLE_SLASH = Pattern.compile("\\s+/\\s+");

    private DeckListNames() {
    }

    /** The names of the cards played from {@code deckList}, in the order listed, without repeats; empty for none. */
    public static Set<String> cardNames(String deckList) {
        Set<String> names = new LinkedHashSet<>();
        if (deckList == null) {
            return names;
        }
        boolean played = true;
        for (String raw : deckList.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.contains("=")) {
                continue;
            }
            Boolean section = section(line);
            if (section != null) {
                played = section;
                continue;
            }
            Matcher prefix = PREFIX.matcher(line);
            boolean linePlayed = played;
            if (prefix.find()) {
                linePlayed = !prefix.group(1).equalsIgnoreCase("SB");
                line = line.substring(prefix.end());
            }
            String name = cardName(line);
            if (linePlayed && !name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** True for a played section's header, false for one that isn't played, null for a line that isn't a header. */
    private static Boolean section(String line) {
        Matcher header = HEADER.matcher(line);
        if (!header.matches()) {
            return null;
        }
        String name = header.group(1).trim().toLowerCase(Locale.ROOT);
        if (PLAYED_SECTIONS.contains(name)) {
            return true;
        }
        return UNPLAYED_SECTIONS.contains(name) ? false : null;
    }

    private static String cardName(String line) {
        String name = COUNT.matcher(line).replaceFirst("");
        int bar = name.indexOf('|');
        if (bar >= 0) {
            name = name.substring(0, bar);
        }
        name = FOIL.matcher(name).replaceFirst("");
        name = SET_AND_NUMBER.matcher(name).replaceFirst("");
        if (!name.contains("//")) {
            name = SINGLE_SLASH.matcher(name).replaceFirst(" // ");
        }
        return name.trim();
    }
}
