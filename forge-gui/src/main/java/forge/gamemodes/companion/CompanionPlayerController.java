package forge.gamemodes.companion;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
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
        PhaseHandler ph = game.getPhaseHandler();
        if (table.runPending(game)) {
            // The active seat is asked next, whoever's priority this was, so the operator sees what the action put
            // on the stack: a spell's caster would get priority anyway, but triggers would otherwise resolve unseen.
            ph.setPriority(ph.getPlayerTurn());
            return Collections.emptyList(); // the loop checks state-based actions, then asks again
        }
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
        List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
        if (chosen == null && table.hasPending()) {
            return Collections.emptyList(); // an action came in instead of a pass: run it, don't move on
        }
        return chosen;
    }

    /**
     * Only the operator's own yields (End Turn, an auto-yielded ability) may skip the stops the table needs.
     * "Auto-pass when no actions" would skip every main phase, as unknown cards have no actions, and the turns would
     * run on with nobody asked.
     */
    @Override
    public boolean mayAutoPass() {
        return !isMacroActive() && getYieldController().shouldAutoYield();
    }

    /** Main phases are where the operator logs what was played, so a phase stop turned off doesn't skip them. */
    @Override
    public boolean isUiSetToSkipPhase(PlayerView turnPlayer, PhaseType phase) {
        return !phase.isMain() && super.isUiSetToSkipPhase(turnPlayer, phase);
    }

    @Override
    public boolean answersForEveryLocalSeat() {
        return true;
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
