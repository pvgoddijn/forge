package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.share.CompanionClient.Link;

/**
 * What the screens do to carry on with an unfinished game, apart from libGDX (slice 04, owner's decision 2026-11-09):
 * Companion never goes into a game by itself on opening; an unfinished game is carried on from Load game, which
 * {@link #pick}s how (Load game's dialog, and the setup screen's Continue, follow it). A followed game rejoins its host
 * without a code, and the phone shows N6 meanwhile (the last saved board as text, and the history, with
 * {@link #statusKey}) until the host's board arrives; then the match screen, with the reconnect notice over it while
 * the host can't be reached ({@link #showsReconnectNotice}).
 */
public final class CompanionResume {
    private CompanionResume() {
    }

    /** What Continue does with a game picked in Load game. */
    public enum Pick {
        /** A game of this device's own, never shared (or no longer): opened as before (v1). */
        CONTINUE,
        /** A game this device shares: opened as before, then shared again on the same port and token. */
        CONTINUE_SHARING,
        /** A followed game: rejoins its host with the code from its {@code share.json}, nothing to type. */
        REJOIN,
        /** A followed game whose {@code share.json} doesn't read: the code is typed, as when joining. */
        JOIN_WITH_CODE
    }

    /** How Load game carries on with {@code game}. Reads its {@code share.json}; writes nothing. */
    public static Pick pick(CompanionStore store, ActiveGame game) {
        if (!game.followed()) {
            return ShareState.hosted(store, game.id()).isPresent() ? Pick.CONTINUE_SHARING : Pick.CONTINUE;
        }
        return game.code().isPresent() ? Pick.REJOIN : Pick.JOIN_WITH_CODE;
    }

    /** Whether the match screen shows the reconnect notice: the board is on screen and the host can't be reached. */
    public static boolean showsReconnectNotice(Link link, boolean boardArrived) {
        return boardArrived && link == Link.RECONNECTING;
    }

    /** The status line's message key for {@code link}, on the N6 screen. Never one that gives up while it tries. */
    public static String statusKey(Link link) {
        switch (link) {
            case CONNECTING:
                return "lblCompanionRejoinConnecting";
            case CONNECTED:
                return "lblCompanionWaitingForGame";
            case RECONNECTING:
                return "lblCompanionHostAway";
            case ENDED:
                return "lblCompanionHostEnded";
            default:
                return "lblCompanionRejoinStopped";
        }
    }
}
