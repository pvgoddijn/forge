package forge.gamemodes.companion;

import forge.game.Game;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.item.PaperCard;

import java.util.List;

/**
 * Forge's game-state text, plus what Companion needs on load: placeholders resolve to the placeholder card, and every
 * player gets the table effect back, as a game state doesn't save effects.
 */
public class CompanionGameState extends GameState {
    public static CompanionGameState of(List<String> lines) {
        CompanionGameState state = new CompanionGameState();
        state.parse(lines);
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
    protected PaperCard findPaperCard(String name, String setCode, int artID) {
        if (CompanionCards.PLACEHOLDER_NAME.equals(name)) {
            return CompanionCards.placeholder();
        }
        return super.findPaperCard(name, setCode, artID);
    }

    @Override
    protected void applyGameOnThread(Game game) {
        super.applyGameOnThread(game);
        for (Card card : game.getCardsInGame()) {
            if (CompanionCards.isPlaceholder(card)) {
                CompanionCards.showAsCardBack(card);
            }
        }
        for (Player player : game.getPlayers()) {
            CompanionCards.addTableEffect(game, player);
        }
    }
}
