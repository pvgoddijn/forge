package forge.gamemodes.companion;

import forge.localinstance.properties.AbstractPreferences;
import forge.localinstance.properties.ForgeConstants;

/** Companion Mode's own settings, in {@code companion.preferences} next to Forge's preferences. */
public class CompanionPreferences extends AbstractPreferences<CompanionPreferences.CPref> {
    public static final String FILE = ForgeConstants.USER_PREFS_DIR + "companion.preferences";

    private static CompanionPreferences instance;

    /** Preference identifiers, and their default values. */
    public enum CPref implements AbstractPreferences.IPref {
        AUTO_ADD("false"), // add a confidently read photo at once, with an undo prompt (AC-9, AC-10)
        KEEP_PHOTOS("false"); // hidden: keep each photo, labelled with the card picked, for the OCR benchmark (slice 8)

        private final String strDefaultVal;

        CPref(final String s0) {
            this.strDefaultVal = s0;
        }

        @Override
        public String getDefault() {
            return this.strDefaultVal;
        }
    }

    private CompanionPreferences() {
        super(FILE, CPref.class);
    }

    /** The settings, read from their file the first time they're asked for. */
    public static synchronized CompanionPreferences get() {
        if (instance == null) {
            instance = new CompanionPreferences();
        }
        return instance;
    }

    @Override
    protected CPref[] getEnumValues() {
        return CPref.values();
    }

    @Override
    protected CPref valueOf(String name) {
        try {
            return CPref.valueOf(name);
        }
        catch (Exception e) {
            return null;
        }
    }
}
