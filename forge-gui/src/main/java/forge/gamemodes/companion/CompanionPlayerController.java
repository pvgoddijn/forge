package forge.gamemodes.companion;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.player.PlayerControllerHuman;

import java.util.Collections;
import java.util.List;

/**
 * A seat at a tracked table. The operator answers for every seat, so each one is a human controller on the same
 * screen. What makes it a tracker rather than a match:
 * <ul>
 * <li>Only the active seat is ever asked for priority, and only in a main phase or while something is on the stack.
 * Every other priority passes, so a turn runs its steps and their triggers without anyone tapping through them.</li>
 * <li>The operator's queued actions run here, on the game loop thread (see {@link CompanionTable}).</li>
 * <li>Nobody mulligans, attacks through the engine or is held to one land a turn: the table did those already.</li>
 * </ul>
 */
public class CompanionPlayerController extends PlayerControllerHuman {
    private final CompanionTable table;

    public CompanionPlayerController(Game game, Player player, LobbyPlayer lobbyPlayer, CompanionTable table) {
        super(game, player, lobbyPlayer);
        this.table = table;
        table.register(this);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        Game game = getGame();
        if (table.runPending(game)) {
            return Collections.emptyList(); // the loop checks state-based actions, then asks again
        }
        PhaseHandler ph = game.getPhaseHandler();
        boolean stackEmpty = game.getStack().isEmpty();
        if (ph.getPlayerTurn() != getPlayer()) {
            return null; // responses for other seats come with a later slice
        }
        if (stackEmpty && (ph.getPhase() == null || !ph.getPhase().isMain())) {
            return null;
        }
        if (stackEmpty) {
            table.save(game);
        }
        return super.chooseSpellAbilityToPlay();
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return getPlayer(); // no coin toss: the game state sets who plays first, from the seat order
    }

    @Override
    public boolean mulliganKeepHand(Player mulliganingPlayer, int cardsToReturn) {
        return true;
    }

    @Override
    public void declareAttackers(Player attackingPlayer, Combat combat) {
        // spec Q21: combat happens at the table, and the operator records its outcome
    }

    @Override
    public boolean canPlayUnlimitedLands() {
        return true;
    }
}
