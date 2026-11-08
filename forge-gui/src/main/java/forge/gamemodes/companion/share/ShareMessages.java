package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionStore.Verbatim;
import forge.gamemodes.net.event.NetEvent;
import forge.util.LogSafe;

import java.util.ArrayList;
import java.util.List;

/**
 * Companion's data messages beside the view stream (plan 3.3): a joining phone's {@link Hello}, the host's
 * {@link Welcome}, the view {@link Epoch}, and the shared game's data, as its {@link ShareJournal} has it:
 * {@link Entries}, {@link Gap}, {@link Setup} and {@link End}. They are plain classes in a {@code forge.} package, so
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

        /**
         * @param latestSeq the seq of the host's latest entry (0 for none) when the phone's catch-up was taken: the
         *                  catch-up runs up to it, and live entries follow it
         */
        public Welcome(String game, CompanionSetup setup, long latestSeq) {
            this.game = game;
            this.setup = CompanionStore.toJson(setup);
            this.latestSeq = latestSeq;
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

        @Override
        public String toString() {
            return "Companion welcome to " + LogSafe.forLog(game) + ", latest seq " + latestSeq;
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
