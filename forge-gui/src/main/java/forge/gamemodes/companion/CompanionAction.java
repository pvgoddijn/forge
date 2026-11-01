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

    /** A card, token or emblem put on a player's battlefield. The card's instance ID is this action's {@code seq}. */
    record CardAdded(int seq, long timestamp, int player, String cardName) implements CompanionAction { }

    /** Marks the game as over, so it no longer resumes. */
    record GameEnded(int seq, long timestamp) implements CompanionAction { }
}
