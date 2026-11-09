package forge.gamemodes.companion;

import forge.game.Game;
import forge.game.GameState;
import forge.game.player.Player;

import java.util.List;

/**
 * Forge's game-state text, plus what Companion needs on load: the placeholders, which Forge's loader can't make, are
 * put back (see {@link PlaceholderText}), and every player gets the table effect back, as a game state doesn't save
 * effects.
 */
public class CompanionGameState extends GameState {
    private PlaceholderText placeholders;

    public static CompanionGameState of(List<String> lines) {
        CompanionGameState state = new CompanionGameState();
        state.placeholders = PlaceholderText.extract(lines);
        state.parse(state.placeholders.lines());
        return state;
    }

    /** The current state of {@code game} as text. Call on the game thread. */
    public static String capture(Game game) {
        CompanionGameState state = new CompanionGameState();
        state.initFromGame(game);
        return state.toString();
    }

    /** Applies this state right away. Call on the game thread, before the main loop or between priority prompts. */
    public void applyNow(Game game) {
        applyGameOnThread(game);
    }

    @Override
    protected void applyGameOnThread(Game game) {
        super.applyGameOnThread(game);
        if (placeholders != null) {
            placeholders.putBack(game);
        }
        for (Player player : game.getPlayers()) {
            CompanionCards.addTableEffect(game, player);
        }
    }
}
