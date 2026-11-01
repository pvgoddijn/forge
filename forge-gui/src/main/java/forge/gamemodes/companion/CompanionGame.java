package forge.gamemodes.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A tracked game: its log, and the state replaying it gives. An action reaches {@code onAction} before it counts,
 * so an action that can't be saved never shows up as applied.
 */
public class CompanionGame {
    private final String id;
    private final List<CompanionAction> log;
    private final Consumer<CompanionAction> onAction;
    private CompanionState state;

    /** Takes over a saved log. {@code log} is replayed, and must hold at least {@code GameStarted}. */
    CompanionGame(String id, List<CompanionAction> log, Consumer<CompanionAction> onAction) {
        this.id = id;
        this.log = new ArrayList<>(log);
        this.onAction = onAction;
        this.state = CompanionState.replay(this.log);
    }

    /** Starts a game. {@code GameStarted} is the first action to reach {@code onAction}. */
    static CompanionGame start(String id, List<CompanionAction.PlayerSetup> players, Consumer<CompanionAction> onAction) {
        CompanionAction started = new CompanionAction.GameStarted(0, System.currentTimeMillis(), players);
        List<CompanionAction> log = Collections.singletonList(started);
        CompanionState.replay(log); // refuse invalid players before anything is saved
        onAction.accept(started);
        return new CompanionGame(id, log, onAction);
    }

    public String id() {
        return id;
    }

    public CompanionState state() {
        return state;
    }

    public List<CompanionAction> log() {
        return Collections.unmodifiableList(log);
    }

    /** Logs a card for a player. {@code from} is null if it comes from outside the tracked zones. */
    public void addCard(int player, String cardName, CompanionZone from, CompanionZone to) {
        apply(new CompanionAction.CardAdded(log.size(), System.currentTimeMillis(), player, cardName, from, to));
    }

    public void moveCard(int instanceId, int toPlayer, CompanionZone to) {
        apply(new CompanionAction.CardMoved(log.size(), System.currentTimeMillis(), instanceId, toPlayer, to));
    }

    public void changeHandCount(int player, int delta) {
        apply(new CompanionAction.HandCountChanged(log.size(), System.currentTimeMillis(), player, delta));
    }

    /**
     * Saves and applies an action.
     *
     * @throws IllegalArgumentException if the action isn't valid here. Nothing is saved or changed.
     */
    public void apply(CompanionAction action) {
        List<CompanionAction> candidate = new ArrayList<>(log);
        candidate.add(action);
        CompanionState next = CompanionState.replay(candidate);
        onAction.accept(action);
        log.add(action);
        state = next;
    }
}
