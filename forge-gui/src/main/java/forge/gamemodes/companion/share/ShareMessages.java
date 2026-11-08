package forge.gamemodes.companion.share;

import forge.gamemodes.net.event.NetEvent;
import forge.util.LogSafe;

/**
 * Companion's data messages beside the view stream (plan 3.3). Slice 0 has three: a joining phone's {@link Hello},
 * the host's {@link Welcome}, and the view {@link Epoch}. They are plain classes in a {@code forge.} package, so the
 * wire's class filter takes them, with fixed {@code serialVersionUID}s, as both ends must agree on them.
 */
public final class ShareMessages {
    private ShareMessages() {
    }

    /** Joined phone to host, after net play's {@code LoginEvent}: the share token from the code. */
    public static final class Hello implements NetEvent {
        private static final long serialVersionUID = 1L;

        private final int token;
        /** The game this phone followed before, or null on a first join. Unused until slice 04. */
        private final String game;

        public Hello(int token, String game) {
            this.token = token;
            this.game = game;
        }

        public int token() {
            return token;
        }

        public String game() {
            return game;
        }

        @Override
        public String toString() {
            // not the token: the log mustn't hand out the way in
            return "Companion hello" + (game == null ? "" : " for " + LogSafe.forLog(game));
        }
    }

    /** Host to joined phone: the token was right, the view follows. */
    public static final class Welcome implements NetEvent {
        private static final long serialVersionUID = 1L;

        private final String game;

        public Welcome(String game) {
            this.game = game;
        }

        /** The shared game's id: its folder in the host's Companion store. */
        public String game() {
            return game;
        }

        @Override
        public String toString() {
            return "Companion welcome to " + LogSafe.forLog(game);
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
}
