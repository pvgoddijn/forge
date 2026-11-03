package forge.gamemodes.companion;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
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
 * <li>Only the active seat is ever asked for priority: in a main phase, while something is on the stack, and in the
 * combat steps of a combat the engine runs. Every other priority passes, so a turn runs its steps and their triggers
 * without anyone tapping through them.</li>
 * <li>The operator's queued actions run here, on the game loop thread (see {@link CompanionTable}).</li>
 * <li>Nobody mulligans or is held to one land a turn: the table did those already. Nobody attacks through the engine
 * either, unless the operator turned combat on for the game or for this turn, or a creature must attack.</li>
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
        boolean mainPhase = ph.getPhase() != null && ph.getPhase().isMain();
        if (stackEmpty && !mainPhase && !isCombatStop(ph)) {
            return null;
        }
        if (stackEmpty && mainPhase) {
            // only main phases are saved: a game state can't hold a combat in progress
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

    /**
     * Main phases are where the operator logs what was played, and the combat stops where tricks are logged, so a
     * phase stop turned off doesn't skip them.
     */
    @Override
    public boolean isUiSetToSkipPhase(PlayerView turnPlayer, PhaseType phase) {
        if (phase.isMain() || isCombatStop(getGame().getPhaseHandler())) {
            return false;
        }
        return super.isUiSetToSkipPhase(turnPlayer, phase);
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
        if (table.engineCombat(getGame().getPhaseHandler().getTurn())) {
            // The operator chose to run this combat through the engine, so End Turn doesn't skip the declaration
            // the way it would in a match: it stops here and asks.
            getYieldController().applyInterrupt();
            // the operator declares for this seat; each defending seat is then asked for blocks in the same way,
            // and damage, lifelink and combat triggers resolve by the rules (spec AC-41)
            super.declareAttackers(attackingPlayer, combat);
            return;
        }
        if (!CombatUtil.validateAttackers(combat)) {
            // A creature must attack (goad, "attacks each combat if able"). Declaring nobody isn't legal, and the
            // engine would ask again forever, so the operator declares through Forge's prompt this once.
            super.declareAttackers(attackingPlayer, combat);
        }
        // otherwise combat happens at the table, and the operator records its outcome (spec Q21)
    }

    /**
     * The combat steps where the active seat stops while the engine runs this turn's combat: after attackers, after
     * blockers, and between first-strike and regular damage, so a trick or flash blocker can be logged in time.
     */
    private boolean isCombatStop(PhaseHandler ph) {
        PhaseType phase = ph.getPhase();
        if (phase == null || !ph.inCombat() || !table.engineCombat(ph.getTurn())) {
            return false;
        }
        return phase == PhaseType.COMBAT_DECLARE_ATTACKERS || phase == PhaseType.COMBAT_DECLARE_BLOCKERS
                || phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE;
    }

    @Override
    public boolean canPlayUnlimitedLands() {
        return true;
    }
}
