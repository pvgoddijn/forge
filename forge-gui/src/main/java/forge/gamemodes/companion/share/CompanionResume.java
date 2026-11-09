package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.share.CompanionClient.Link;

/**
 * What the screens do to carry on with an unfinished game, apart from libGDX (slice 04, owner's decision 2026-11-09):
 * Companion never goes into a game by itself on opening; an unfinished game is carried on from Load game, which
 * {@link #pick}s how. A followed game rejoins its host without a code, and {@link #shown} says what the phone shows
 * meanwhile: N6 (the last saved board as text, and the history) until the host's board arrives.
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

    /** What a phone rejoining a followed game shows. */
    public enum Shown {
        /** N6: the last saved board as text plus the history, with "Stop following"; it keeps trying. */
        LAST_SAVED,
        /** The match screen with the host's board; while it reconnects, with the reconnect notice over it. */
        BOARD,
        /** The host ended the game: it's one of the past games. */
        ENDED,
        /** Over without the game's end (left, or let go by the host): nothing is tried any more. */
        STOPPED
    }

    /**
     * What to show for {@code link}, once the host's board did or didn't arrive on this follow. A board that arrived
     * stays on screen while the link is lost (the notice says so), as the phone reconnects to the same game.
     */
    public static Shown shown(Link link, boolean boardArrived) {
        switch (link) {
            case ENDED:
                return Shown.ENDED;
            case CLOSED:
                return Shown.STOPPED;
            default:
                return boardArrived ? Shown.BOARD : Shown.LAST_SAVED;
        }
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
