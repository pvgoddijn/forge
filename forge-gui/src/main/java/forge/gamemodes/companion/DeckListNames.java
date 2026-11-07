package forge.gamemodes.companion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The card names in a pasted decklist ({@link CompanionSetup.Seat#deckList()}): all of them, for ranking a player's
 * deck first in the card search (AC-12), and the commanders, for the commander step (AC-3).
 * <p>
 * A small parser of its own: Forge's {@code DeckRecognizer} needs the whole card database loaded even to be built, and
 * {@code DeckImportController} needs the GUI and {@code FModel}. A name it gets wrong only ranks nothing, as names are
 * matched against the card database by {@link CardNameMatcher}, never added from here; a commander's name is looked
 * up in the database before it is used.
 * <p>
 * It reads lines such as "1 Sol Ring", "1x Sol Ring (CMR) 123", "Sol Ring", Forge's "1 Sol Ring|CMR|1", "CM: Krenko"
 * and "SB: 1 Shock"; Archidekt's categories at the end of a line, such as "[Commander{top}]" or
 * "[Maybeboard{noDeck}]"; section headers such as "Commander", "Deck", "[Main]", "Sideboard (2)" or "// Commander";
 * blank lines, and comments starting with "#" or "//". The commander and main deck count; a sideboard or maybeboard
 * doesn't, as those cards aren't played. Forge's {@code .dck} metadata ("Name=...") and a deck's name line, as in
 * Forge's text export ("Deck: Goblins"), are skipped.
 */
public final class DeckListNames {
    private static final Set<String> COMMANDER_SECTIONS = Set.of("commander", "commanders");
    private static final Set<String> PLAYED_SECTIONS = Set.of("deck", "main", "mainboard", "main deck", "companion",
            "planes", "schemes", "conspiracy", "attractions", "contraptions");
    private static final Set<String> UNPLAYED_SECTIONS = Set.of("sideboard", "side", "maybeboard", "maybe",
            "considering", "tokens", "metadata");
    private static final Pattern HEADER = Pattern.compile("^\\[?([A-Za-z ]+?)]?\\s*:?\\s*(\\(\\d+\\))?\\s*:?$");
    /** A deck's name, as Forge's text export starts with ("Deck: Goblins"); not a header, as it has a name. */
    private static final Pattern NAME_LINE = Pattern.compile("^(?i:deck|name)\\s*:\\s*\\D.*$");
    private static final Pattern PREFIX = Pattern.compile("^(SB|CM|MB)\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT = Pattern.compile("^(\\d+)\\s*x?\\s+", Pattern.CASE_INSENSITIVE);
    // Archidekt's categories at the end of the line: "[Commander{top}]", "[Ramp,Draw]", "[Maybeboard{noDeck}]"
    private static final Pattern CATEGORIES = Pattern.compile("\\s+\\[([^\\]]*\\{[^\\]]*|[A-Za-z][^\\]]*,[^\\]]*|"
            + "Commander|Sideboard|Maybeboard)]\\s*(\\^[^^]*\\^)?\\s*$", Pattern.CASE_INSENSITIVE);
    // "(CMR) 123", "[CMR]", "(CMR) 123 *F*" and the like at the end of the line
    private static final Pattern SET_AND_NUMBER = Pattern.compile("\\s+[(\\[][A-Za-z0-9_]{2,7}[)\\]].*$");
    private static final Pattern FOIL = Pattern.compile("\\s+\\*[A-Za-z]+\\*$");
    private static final Pattern SINGLE_SLASH = Pattern.compile("\\s+/\\s+");

    private enum Part { COMMANDER, PLAYED, UNPLAYED }

    private record Entry(String name, int count, Part part) {
    }

    private DeckListNames() {
    }

    /** The names of the cards played from {@code deckList}, in the order listed, without repeats; empty for none. */
    public static Set<String> cardNames(String deckList) {
        Set<String> names = new LinkedHashSet<>();
        for (Entry entry : entries(deckList)) {
            if (entry.part() != Part.UNPLAYED) {
                names.add(entry.name());
            }
        }
        return names;
    }

    /**
     * The commanders' names in {@code deckList}, from its Commander section, "CM:" lines or Archidekt's Commander
     * category, in the order listed, without repeats; empty for none. Not checked against the card database.
     */
    public static List<String> commanderNames(String deckList) {
        Set<String> names = new LinkedHashSet<>();
        for (Entry entry : entries(deckList)) {
            if (entry.part() == Part.COMMANDER) {
                names.add(entry.name());
            }
        }
        return new ArrayList<>(names);
    }

    /** How many cards are played from {@code deckList}, commanders included, counting each line's number. */
    public static int cardCount(String deckList) {
        int count = 0;
        for (Entry entry : entries(deckList)) {
            if (entry.part() != Part.UNPLAYED) {
                count += entry.count();
            }
        }
        return count;
    }

    private static List<Entry> entries(String deckList) {
        List<Entry> entries = new ArrayList<>();
        if (deckList == null) {
            return entries;
        }
        Part part = Part.PLAYED;
        for (String raw : deckList.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("//")) {
                // "// Commander" is a header in some exports; any other such line is a comment
                Part section = section(line.substring(2).trim());
                if (section != null) {
                    part = section;
                }
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.contains("=") || NAME_LINE.matcher(line).matches()) {
                continue;
            }
            Part section = section(line);
            if (section != null) {
                part = section;
                continue;
            }
            Part linePart = part;
            Matcher prefix = PREFIX.matcher(line);
            if (prefix.find()) {
                String kind = prefix.group(1).toUpperCase(Locale.ROOT);
                linePart = kind.equals("SB") ? Part.UNPLAYED : kind.equals("CM") ? Part.COMMANDER : Part.PLAYED;
                line = line.substring(prefix.end());
            }
            Matcher categories = CATEGORIES.matcher(line);
            if (categories.find()) {
                linePart = categoryPart(categories.group(1), linePart);
                line = line.substring(0, categories.start());
            }
            int count = 1;
            Matcher number = COUNT.matcher(line);
            if (number.find()) {
                count = Integer.parseInt(number.group(1));
                line = line.substring(number.end());
            }
            String name = cardName(line);
            if (!name.isEmpty()) {
                entries.add(new Entry(name, count, linePart));
            }
        }
        return entries;
    }

    /** The part of the deck a header names, or null for a line that isn't a header. */
    private static Part section(String line) {
        Matcher header = HEADER.matcher(line);
        if (!header.matches()) {
            return null;
        }
        String name = header.group(1).trim().toLowerCase(Locale.ROOT);
        if (COMMANDER_SECTIONS.contains(name)) {
            return Part.COMMANDER;
        }
        if (PLAYED_SECTIONS.contains(name)) {
            return Part.PLAYED;
        }
        return UNPLAYED_SECTIONS.contains(name) ? Part.UNPLAYED : null;
    }

    /** The part of the deck Archidekt's categories put a card in: "Commander", or "{noDeck}" for one not played. */
    private static Part categoryPart(String categories, Part otherwise) {
        String lower = categories.toLowerCase(Locale.ROOT);
        for (String category : lower.split(",")) {
            String name = category.replaceAll("\\{.*", "").trim();
            if (COMMANDER_SECTIONS.contains(name)) {
                return Part.COMMANDER;
            }
        }
        if (lower.contains("{nodeck}") || lower.startsWith("sideboard") || lower.startsWith("maybeboard")) {
            return Part.UNPLAYED;
        }
        return otherwise;
    }

    private static String cardName(String line) {
        String name = line;
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
