package forge.gamemodes.companion;

import forge.StaticData;
import forge.card.CardRules;
import forge.card.ICardFace;
import forge.item.PaperCard;
import org.apache.commons.text.similarity.LevenshteinDistance;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Turns typed text into ranked card names. It tolerates typos, and every face of a card is its own name. */
public class CardNameMatcher {
    public static final int MIN_QUERY_LENGTH = 2;
    private static final int MIN_FUZZY_QUERY_LENGTH = 4;

    public record Suggestion(String card, double confidence) { }

    private record Entry(String name, String normalized) { }

    private final List<Entry> entries = new ArrayList<>();

    public CardNameMatcher(Collection<String> names) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String name : names) {
            unique.putIfAbsent(normalize(name), name);
        }
        unique.forEach((normalized, name) -> entries.add(new Entry(name, normalized)));
    }

    /** The name of every face of every card in Forge's database: what a photo of a card can show. */
    public static List<String> cardFaceNames() {
        List<String> names = new ArrayList<>();
        for (ICardFace face : StaticData.instance().getCommonCards().getAllFaces()) {
            names.add(face.getName());
        }
        return names;
    }

    /** Every face of every card in Forge's database, plus every token. */
    public static CardNameMatcher fromStaticData() {
        List<String> names = cardFaceNames();
        for (CardRules token : StaticData.instance().getAllTokens().getRules().values()) {
            names.add(token.getName());
        }
        return new CardNameMatcher(names);
    }

    /** The best matches, best first, or nothing if the text has under {@value #MIN_QUERY_LENGTH} characters. */
    public List<Suggestion> match(String text, int limit) {
        String query = normalize(text);
        List<Suggestion> matches = new ArrayList<>();
        if (query.length() < MIN_QUERY_LENGTH) {
            return matches;
        }
        int maxEdits = query.length() < MIN_FUZZY_QUERY_LENGTH ? 0 : query.length() <= 5 ? 1 : query.length() <= 9 ? 2 : 3;
        LevenshteinDistance distance = new LevenshteinDistance(maxEdits);
        for (Entry entry : entries) {
            double score = score(query, entry.normalized(), maxEdits, distance);
            if (score > 0) {
                matches.add(new Suggestion(entry.name(), score));
            }
        }
        matches.sort(Comparator.comparingDouble(Suggestion::confidence).reversed()
                .thenComparingInt(s -> s.card().length())
                .thenComparing(Suggestion::card));
        return new ArrayList<>(matches.subList(0, Math.min(limit, matches.size())));
    }

    /**
     * The best matches that are cards passing {@code filter}, best first. A name that isn't in Forge's card database,
     * such as a token's, never passes. A null filter keeps every match, as {@link #match(String, int)} does.
     */
    public List<Suggestion> matchWhere(String text, int limit, Predicate<PaperCard> filter) {
        return matchWhere(text, limit, filter, Set.of());
    }

    /**
     * The best matches, with the {@code preferred} names, such as a player's deck, ranked above the rest (AC-12).
     * The rule: a name matching the text exactly comes first, as it was typed or read in full; then the preferred
     * matches, best first; then every other match, best first. Only names that pass the matcher's own cut-off are
     * matches at all, so a preferred name never comes up for text it doesn't match, and the preferred names never
     * hide a match outside them. A name of a split card ("Fire // Ice") prefers each of its faces.
     */
    public List<Suggestion> match(String text, int limit, Set<String> preferred) {
        return matchWhere(text, limit, null, preferred);
    }

    /** {@link #match(String, int, Set)}, keeping only cards passing {@code filter}, as {@link #matchWhere} does. */
    public List<Suggestion> matchWhere(String text, int limit, Predicate<PaperCard> filter, Set<String> preferred) {
        List<Suggestion> all = match(text, Integer.MAX_VALUE);
        if (!preferred.isEmpty()) {
            all = preferredFirst(all, preferred);
        }
        List<Suggestion> kept = new ArrayList<>();
        for (Suggestion suggestion : all) {
            if (kept.size() >= limit) {
                break;
            }
            if (passes(suggestion.card(), filter)) {
                kept.add(suggestion);
            }
        }
        return kept;
    }

    private static List<Suggestion> preferredFirst(List<Suggestion> matches, Set<String> preferred) {
        Set<String> wanted = normalizedFaces(preferred);
        List<Suggestion> exact = new ArrayList<>();
        List<Suggestion> first = new ArrayList<>();
        List<Suggestion> rest = new ArrayList<>();
        for (Suggestion suggestion : matches) {
            if (suggestion.confidence() == 1) {
                exact.add(suggestion);
            } else if (wanted.contains(normalize(suggestion.card()))) {
                first.add(suggestion);
            } else {
                rest.add(suggestion);
            }
        }
        exact.addAll(first);
        exact.addAll(rest);
        return exact;
    }

    /**
     * After a photo is read (AC-12): the reader's names and the matches for the text it read, in one list without
     * repeats. An exact match for the text read comes first; then the {@code preferred} names, the reader's before the
     * matcher's; then the others, the reader's before the matcher's. So a deck card the reader ranked low, or missed,
     * still comes up near the top, and nothing either of them found is dropped.
     *
     * @param readerNames the reader's names, best first
     * @param matches     {@link #match} for the text read
     * @param preferred   the names to rank first, such as the target player's deck; empty for none
     */
    public static List<String> mergeReading(List<String> readerNames, List<Suggestion> matches,
                                            Set<String> preferred) {
        Set<String> wanted = normalizedFaces(preferred);
        Set<String> exact = new LinkedHashSet<>();
        List<String> matched = new ArrayList<>();
        for (Suggestion suggestion : matches) {
            if (suggestion.confidence() == 1) {
                exact.add(suggestion.card());
            } else {
                matched.add(suggestion.card());
            }
        }
        Set<String> merged = new LinkedHashSet<>(exact);
        for (boolean deckPass : new boolean[] {true, false}) {
            for (List<String> source : List.of(readerNames, matched)) {
                for (String name : source) {
                    if (wanted.contains(normalize(name)) == deckPass) {
                        merged.add(name);
                    }
                }
            }
        }
        return new ArrayList<>(merged);
    }

    /** Each name normalized, and each face of a split card's name ("Fire // Ice") as well. */
    private static Set<String> normalizedFaces(Set<String> names) {
        Set<String> faces = new HashSet<>();
        for (String name : names) {
            faces.add(normalize(name));
            if (name.contains("//")) {
                for (String face : name.split("//")) {
                    faces.add(normalize(face));
                }
            }
        }
        return faces;
    }

    /** Whether {@code name} is a card in Forge's database that passes {@code filter}; always, for a null filter. */
    public static boolean passes(String name, Predicate<PaperCard> filter) {
        if (filter == null) {
            return true;
        }
        PaperCard card = StaticData.instance().getCommonCards().getUniqueByName(name);
        return card != null && filter.test(card);
    }

    /** Exact, then start of the name, start of a later word, anywhere in the name, and last the typo-tolerant forms. */
    private static double score(String query, String name, int maxEdits, LevenshteinDistance distance) {
        if (name.equals(query)) {
            return 1;
        }
        double coverage = (double) query.length() / name.length();
        if (name.startsWith(query)) {
            return 0.9 + 0.09 * coverage;
        }
        int found = name.indexOf(query);
        if (found > 0) {
            return (name.charAt(found - 1) == ' ' ? 0.8 : 0.7) + 0.09 * coverage;
        }
        if (maxEdits == 0) {
            return 0;
        }
        int whole = Math.abs(name.length() - query.length()) > maxEdits ? -1 : distance.apply(query, name);
        if (whole >= 0) {
            return 0.65 * (1 - (double) whole / Math.max(query.length(), name.length()));
        }
        if (name.length() > query.length()) { // a typo in the first part of a name still being typed
            int partial = distance.apply(query, name.substring(0, query.length()));
            // one letter longer as well, so a dropped letter costs one edit, not one per letter after it
            int longer = distance.apply(query, name.substring(0, query.length() + 1));
            if (longer >= 0 && (partial < 0 || longer < partial)) {
                partial = longer;
            }
            if (partial >= 0) {
                return 0.55 * (1 - (double) partial / query.length());
            }
        }
        // a typo in one word of the name, as in "Bolr" for Lightning Bolt
        int wordStart = name.indexOf(' ') + 1;
        while (wordStart > 0) {
            int wordEnd = name.indexOf(' ', wordStart);
            String word = wordEnd < 0 ? name.substring(wordStart) : name.substring(wordStart, wordEnd);
            if (Math.abs(word.length() - query.length()) <= maxEdits) {
                int edits = distance.apply(query, word);
                if (edits >= 0) {
                    return 0.45 * (1 - (double) edits / query.length());
                }
            }
            wordStart = wordEnd + 1;
        }
        return 0;
    }

    private static String normalize(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                result.append(c);
            } else if (Character.getType(c) != Character.NON_SPACING_MARK && c != '\'' && c != '’'
                    && result.length() > 0 && result.charAt(result.length() - 1) != ' ') {
                result.append(' ');
            }
        }
        int end = result.length();
        return end > 0 && result.charAt(end - 1) == ' ' ? result.substring(0, end - 1) : result.toString();
    }
}
