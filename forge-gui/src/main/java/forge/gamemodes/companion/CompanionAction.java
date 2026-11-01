package forge.gamemodes.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One entry in a tracked game's append-only log. The game's state is always computed by replaying these.
 * {@code seq} is the action's position in the log.
 */
public sealed interface CompanionAction {
    int seq();
    long timestamp();

    record PlayerSetup(String name, boolean me) { }

    /** Always the first action. Seat order is the order of {@code players}. */
    record GameStarted(int seq, long timestamp, List<PlayerSetup> players) implements CompanionAction {
        public GameStarted {
            players = Collections.unmodifiableList(new ArrayList<>(players));
        }
    }

    /**
     * A card, token or emblem newly logged for a player. The card's instance ID is this action's {@code seq}, unless
     * it was already known in that player's hand. {@code from} is null if it comes from outside the tracked zones,
     * such as a library or a token being created.
     */
    record CardAdded(int seq, long timestamp, int player, String cardName, CompanionZone from, CompanionZone to)
            implements CompanionAction { }

    /** A tracked card moved to any zone of any player. */
    record CardMoved(int seq, long timestamp, int instanceId, int toPlayer, CompanionZone to)
            implements CompanionAction { }

    /** A change to a hidden hand count, such as a draw or a discard. The count never goes below 0. */
    record HandCountChanged(int seq, long timestamp, int player, int delta) implements CompanionAction { }

    /** Marks the game as over, so it no longer resumes. */
    record GameEnded(int seq, long timestamp) implements CompanionAction { }
}
