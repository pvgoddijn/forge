package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionStore;

import java.util.Optional;

/**
 * The game Companion opens with, and whether it may be resumed as this phone's own. A game followed from another
 * phone (it has a {@link ShareState}) never is: resuming loads its state, which writes to a copy that must stay the
 * host's byte for byte. It is offered "Stop following" ({@link #stopFollowing}) or "Join again" instead, with the
 * code from its {@code share.json} when that reads. Finding it writes nothing to the game's folder.
 *
 * @param saved    the active game
 * @param followed whether it's followed from another phone, so never resumed here
 * @param code     the code to join its host again; empty for a game of this phone's own, or when its
 *                 {@code share.json} doesn't read (the code is then typed)
 */
public record ActiveGame(CompanionStore.Saved saved, boolean followed, Optional<JoinCode> code) {
    /** The store's active game ({@link CompanionStore#loadActive}), if any. */
    public static Optional<ActiveGame> find(CompanionStore store) {
        return store.loadActive().map(saved -> of(store, saved));
    }

    static ActiveGame of(CompanionStore store, CompanionStore.Saved saved) {
        if (!ShareState.exists(store, saved.id())) {
            return new ActiveGame(saved, false, Optional.empty());
        }
        Optional<JoinCode> code;
        try {
            code = ShareState.read(store, saved.id()).map(ShareState::code);
        } catch (RuntimeException e) {
            // damaged, or not an address: still followed, and the code is asked for
            System.err.println("Can't read the share state of companion game " + saved.id() + ": " + e);
            code = Optional.empty();
        }
        return new ActiveGame(saved, true, code);
    }

    /** "Stop following": the copy is ended here, so it's one of this phone's past games, read-only. */
    public void stopFollowing(CompanionStore store) {
        store.end(saved.id());
    }
}
