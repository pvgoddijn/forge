package forge.gamemodes.companion;

import forge.StaticData;
import forge.card.CardRules;
import forge.card.ICardFace;
import org.apache.commons.text.similarity.LevenshteinDistance;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
