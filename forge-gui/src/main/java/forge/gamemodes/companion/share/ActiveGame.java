package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An unfinished game, as Companion's "Load game" lists it, and whether it may be continued as this phone's own. A
 * game followed from another phone (it has a {@link ShareState}) never is: continuing loads its state, which writes
 * to a copy that must stay the host's byte for byte. It is offered "Stop following" ({@link #stopFollowing}) or "Join
 * again" instead, with the code from its {@code share.json} when that reads. Listing writes nothing to a game's folder.
 *
 * @param game     the unfinished game
 * @param followed whether it's followed from another phone, so never continued here
 * @param code     the code to join its host again; empty for a game of this phone's own, or when its
 *                 {@code share.json} doesn't read (the code is then typed)
 */
public record ActiveGame(CompanionStore.Unfinished game, boolean followed, Optional<JoinCode> code) {
    /**
     * The store's unfinished games, own and followed, most recently played first ({@link CompanionStore#listUnfinished},
     * whose {@link CompanionStore#unreadableFiles} it leaves for telling the operator).
     */
    public static List<ActiveGame> list(CompanionStore store) {
        List<ActiveGame> games = new ArrayList<>();
        for (CompanionStore.Unfinished game : store.listUnfinished()) {
            games.add(of(store, game));
        }
        return games;
    }

    static ActiveGame of(CompanionStore store, CompanionStore.Unfinished game) {
        if (!ShareState.isFollowed(store, game.id())) {
            // never shared, or shared from here (hosting): this device's own
            return new ActiveGame(game, false, Optional.empty());
        }
        Optional<JoinCode> code;
        try {
            code = ShareState.read(store, game.id()).map(ShareState::code);
        } catch (RuntimeException e) {
            // damaged, or not an address: still followed, and the code is asked for
            System.err.println("Can't read the share state of companion game " + game.id() + ": " + e);
            code = Optional.empty();
        }
        return new ActiveGame(game, true, code);
    }

    /** The game's id: its folder in the store. */
    public String id() {
        return game.id();
    }

    /**
     * Ends a game of this phone's own: it's then one of the past games, read-only. Not for a followed game, whose
     * ending is {@link #stopFollowing}.
     *
     * @throws IllegalStateException for a followed game
     */
    public void end(CompanionStore store) {
        if (followed) {
            throw new IllegalStateException("A followed game is stopped following, not ended: " + game.id());
        }
        store.end(game.id());
    }

    /** "Stop following": the copy is ended here, so it's one of this phone's past games, read-only. */
    public void stopFollowing(CompanionStore store) {
        store.end(game.id());
    }
}
