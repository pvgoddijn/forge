package forge.gamemodes.companion;

import forge.game.Game;
import forge.game.GameState;
import forge.game.player.Player;
import forge.game.zone.MagicStack;

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

    /**
     * Applies this state right away. Call on the game thread, before the main loop or between priority prompts. A
     * save is made with an empty stack, so anything on the stack now is from after it and goes (see
     * {@link #clearStack}). A combat in progress is ended by Forge's own apply, which sets the phase.
     */
    public void applyNow(Game game) {
        clearStack(game);
        applyGameOnThread(game);
        for (Player player : game.getPlayers()) {
            clearLifeThisTurn(player);
        }
        // Forge sets the turn number without telling the view, which would show the old turn until the next one began
        game.updateTurnForView();
    }

    /**
     * Empties the stack: its spells and abilities, the triggers waiting to go on it, and the cards in the stack zone.
     * Forge's apply only resets the players' zones, so a spell cast after the save would stay on the stack, pointing
     * at a card no zone has any more, and resolve after the state is back.
     */
    private static void clearStack(Game game) {
        MagicStack stack = game.getStack();
        stack.clearSimultaneousStack();
        stack.clearFrozen();
        stack.clear();
        game.getStackZone().removeAllCards(true);
        game.updateStackForView();
    }

    /**
     * Forge sets each player's life from a state by a gain or a loss, which counts toward this turn's tallies: a
     * table starting at 40 would have gained 20 already, and "the first time you gain life each turn" wouldn't fire.
     * A save doesn't hold the tallies, so zero is the honest value. On a resume mid-turn that loses what was really
     * gained or lost earlier in that turn.
     */
    private static void clearLifeThisTurn(Player player) {
        player.setLifeGainedThisTurn(0);
        player.setLifeGainedTimesThisTurn(0);
        player.setLifeLostThisTurn(0);
        player.addLifeGainedByTeamThisTurn(-player.getLifeGainedByTeamThisTurn());
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
