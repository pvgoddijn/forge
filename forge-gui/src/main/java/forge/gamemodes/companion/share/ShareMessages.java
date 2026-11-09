package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionStore.Verbatim;
import forge.gamemodes.net.event.NetEvent;
import forge.util.LogSafe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Companion's data messages beside the view stream (plan 3.3): a joining phone's {@link Hello}, the host's
 * {@link Welcome}, the joined phone's {@link Want}, the view {@link Epoch}, and the shared game's data, as its
 * {@link ShareJournal} has it: {@link Step}, {@link Entries}, {@link Gap}, {@link Setup} and {@link End}. They are plain classes in a {@code forge.} package, so
 * the wire's class filter takes them, with fixed {@code serialVersionUID}s, as both ends must agree on them. They carry
 * only strings, numbers and arrays of them: not records, whose serialization isn't dependable on Android, so a setup
 * travels as the JSON its {@code setup.json} has ({@link CompanionStore#toJson}).
 */
public final class ShareMessages {
    private ShareMessages() {
    }

    /** Joined phone to host, after net play's {@code LoginEvent}: the share token from the code. */
    public static final class Hello implements NetEvent {
        private static final long serialVersionUID = 1L;

        /** {@link #have} of a phone that holds no entry of the game. Seqs start at 1. */
        public static final long NOTHING = 0;

        private final int token;
        /** The game this phone holds a copy of, or null on a first join: {@link #have} counts only for it. */
        private final String game;
        private final long have;

        public Hello(int token, String game, long have) {
            this.token = token;
            this.game = game;
            this.have = have;
        }

        public int token() {
            return token;
        }

        public String game() {
            return game;
        }

        /** The seq of the last entry of {@link #game} this phone holds, or {@link #NOTHING}: catch-up starts after it. */
        public long have() {
            return have;
        }

        @Override
        public String toString() {
            // not the token: the log mustn't hand out the way in
            return "Companion hello" + (game == null ? "" : " for " + LogSafe.forLog(game)) + ", have " + have;
        }
    }

    /** Host to joined phone: the token was right, the view follows. */
    public static final class Welcome implements NetEvent {
        private static final long serialVersionUID = 1L;

        private final String game;
        private final String setup;
        private final long latestSeq;
        private final int latestStep;
        /** The step index, as two arrays of the same length: step numbers, ascending, and their sha256. */
        private final int[] steps;
        private final String[] hashes;

        /**
         * @param latestSeq  the seq of the host's latest entry (0 for none) when the phone's catch-up was taken: the
         *                   catch-up runs up to it, and live entries follow it
         * @param latestStep the host's latest step then, or {@link CompanionStore#NO_STEP}
         * @param stepIndex  every step the host held then, with its sha256 ({@link CompanionStore#stepHashes})
         */
        public Welcome(String game, CompanionSetup setup, long latestSeq, int latestStep,
                Map<Integer, String> stepIndex) {
            this.game = game;
            this.setup = CompanionStore.toJson(setup);
            this.latestSeq = latestSeq;
            this.latestStep = latestStep;
            Map<Integer, String> sorted = new TreeMap<>(stepIndex);
            steps = new int[sorted.size()];
            hashes = new String[sorted.size()];
            int i = 0;
            for (Map.Entry<Integer, String> step : sorted.entrySet()) {
                steps[i] = step.getKey();
                hashes[i] = step.getValue();
                i++;
            }
        }

        /** The shared game's id: its folder in the host's Companion store. */
        public String game() {
            return game;
        }

        /** The game's setup, as the host has it now. */
        public CompanionSetup setup() {
            return CompanionStore.fromJson(setup);
        }

        public long latestSeq() {
            return latestSeq;
        }

        /** The host's latest step when the catch-up was taken, or {@link CompanionStore#NO_STEP}. */
        public int latestStep() {
            return latestStep;
        }

        /**
         * The steps the host held when the catch-up was taken, each with its sha256, lowest first: the phone wants
         * those it lacks or holds with another hash.
         *
         * @throws IllegalArgumentException when the two arrays a peer sent don't match
         */
        public SortedMap<Integer, String> stepIndex() {
            if (steps == null || hashes == null || steps.length != hashes.length) {
                throw new IllegalArgumentException("A step index that doesn't read");
            }
            SortedMap<Integer, String> index = new TreeMap<>();
            for (int i = 0; i < steps.length; i++) {
                index.put(steps[i], hashes[i]);
            }
            return index;
        }

        @Override
        public String toString() {
            return "Companion welcome to " + LogSafe.forLog(game) + ", latest seq " + latestSeq + ", latest step "
                    + latestStep + ", " + (steps == null ? 0 : steps.length) + " steps";
        }
    }

    /** Joined phone to host, after {@link Welcome}: the steps it lacks or holds with another hash. */
    public static final class Want implements NetEvent {
        private static final long serialVersionUID = 1L;

        private final int[] steps;

        public Want(int... steps) {
            this.steps = steps.clone();
        }

        /** The step numbers wanted; empty for none. */
        public int[] steps() {
            return steps == null ? new int[0] : steps.clone();
        }

        @Override
        public String toString() {
            return "Companion want steps " + Arrays.toString(steps);
        }
    }

    /**
     * Host to joined phone: the text of step {@code n} exactly as the host saved it, and its sha256, to write with
     * {@link CompanionStore#writeStep} once the text is checked against it.
     */
    public static final class Step implements NetEvent {
        private static final long serialVersionUID = 1L;

        private final int n;
        private final String sha256;
        private final String text;

        /** Step {@code n} with {@code text}, hashed here ({@link CompanionStore#sha256}). */
        public Step(int n, String text) {
            this.n = n;
            this.text = text;
            this.sha256 = CompanionStore.sha256(text);
        }

        public int n() {
            return n;
        }

        public String sha256() {
            return sha256;
        }

        public String text() {
            return text;
        }

        /** Whether {@link #text} has the {@link #sha256} the host gave it: false for one damaged on the way. */
        public boolean intact() {
            return text != null && sha256 != null && sha256.equals(CompanionStore.sha256(text));
        }

        @Override
        public String toString() {
            return "Companion step " + n + " (" + (text == null ? 0 : text.length()) + " chars)";
        }
    }

    /**
     * Host to joined phone, right after each full view the host sent it (a fresh attach, an undo or resume, a
     * checksum resync): how many it has been sent, so the phone knows which board its card ids belong to (plan 3.2).
     */
    public static final class Epoch implements NetEvent {
        private static final long serialVersionUID = 1L;

        private final int n;

        public Epoch(int n) {
            this.n = n;
        }

        public int n() {
            return n;
        }

        @Override
        public String toString() {
            return "Companion view epoch " + n;
        }
    }

    /**
     * Host to joined phone: log lines exactly as the host wrote them, without their newlines, with consecutive seqs,
     * to append with {@link CompanionStore#appendVerbatim}.
     */
    public static final class Entries implements NetEvent {
        private static final long serialVersionUID = 1L;

        private final long firstSeq;
        private final String[] lines;

        /** @param items entries (no gaps), at least one, with consecutive seqs */
        public Entries(List<Verbatim> items) {
            if (items.isEmpty()) {
                throw new IllegalArgumentException("No entries");
            }
            firstSeq = items.get(0).seq();
            lines = new String[items.size()];
            for (int i = 0; i < lines.length; i++) {
                Verbatim item = items.get(i);
                if (item.isGap() || item.seq() != firstSeq + i) {
                    throw new IllegalArgumentException("Not the entry " + (firstSeq + i) + ": " + item.seq());
                }
                lines[i] = item.line();
            }
        }

        /** The entries, each with its seq. Throws IllegalArgumentException for a line with a line break in it. */
        public List<Verbatim> items() {
            List<Verbatim> items = new ArrayList<>(lines.length);
            for (int i = 0; i < lines.length; i++) {
                items.add(Verbatim.entry(firstSeq + i, lines[i]));
            }
            return items;
        }

        @Override
        public String toString() {
            return "Companion entries " + firstSeq + " to " + (firstSeq + lines.length - 1);
        }
    }

    /** Host to joined phone: a seq the host used up without writing an entry. The phone writes a tombstone for it. */
    public static final class Gap implements NetEvent {
        private static final long serialVersionUID = 1L;

        private final long seq;

        public Gap(long seq) {
            this.seq = seq;
        }

        public long seq() {
            return seq;
        }

        @Override
        public String toString() {
            return "Companion gap " + seq;
        }
    }

    /** Host to joined phone: the game's setup changed. */
    public static final class Setup implements NetEvent {
        private static final long serialVersionUID = 1L;

        private final String setup;

        public Setup(CompanionSetup setup) {
            this.setup = CompanionStore.toJson(setup);
        }

        public CompanionSetup setup() {
            return CompanionStore.fromJson(setup);
        }

        @Override
        public String toString() {
            return "Companion setup";
        }
    }

    /** Host to joined phone: the host ended the game. Nothing of it follows. */
    public static final class End implements NetEvent {
        private static final long serialVersionUID = 1L;

        @Override
        public String toString() {
            return "Companion end";
        }
    }
}
