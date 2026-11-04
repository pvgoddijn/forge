package forge.cardocr.reader;

import java.text.Normalizer;
import java.util.*;

/**
 * Every card name, sorted by its normalised form so that names sharing a prefix sit together.
 *
 * <p>Normalising keeps only a to z and 0 to 9, because the title read is matched without regard to
 * case, accents or punctuation. Names that normalise alike count as one card. So do none shorter
 * than 2 characters, as they would match almost anything.
 */
public final class CardNames {

    private final String[] names;
    private final String[] normalised;
    private final Map<String, Integer> byNormalised = new HashMap<>();

    private CardNames(String[] names, String[] normalised) {
        this.names = names;
        this.normalised = normalised;
        for (int i = 0; i < normalised.length; i++) {
            byNormalised.put(normalised[i], i);
        }
    }

    public static CardNames of(Collection<String> rawNames) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String name : rawNames) {
            String key = normalise(name);
            if (key.length() >= 2) {
                sorted.putIfAbsent(key, name);
            }
        }
        return new CardNames(sorted.values().toArray(new String[0]), sorted.keySet().toArray(new String[0]));
    }

    public static String normalise(String text) {
        boolean plain = true;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length() && plain; i++) {
            char c = text.charAt(i);
            if (c >= 128) {
                plain = false;
            } else if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) (c + 32));
            }
        }
        if (plain) {
            return out.toString();
        }
        String stripped = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public int size() {
        return names.length;
    }

    public String name(int card) {
        return names[card];
    }

    public String normalised(int card) {
        return normalised[card];
    }

    /** The card with this normalised form, or -1. */
    public int indexOf(String normalisedName) {
        return byNormalised.getOrDefault(normalisedName, -1);
    }
}
