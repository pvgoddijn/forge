package forge.gamemodes.companion;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * The operator's actions on a real game, from a Companion starting state. The players are AI-controlled here only so
 * the engine can run headless; nothing in these cards asks for a choice.
 */
public class TableEditorTest extends AITest {

    private Game startTable() {
        Game game = initAndCreateThreePlayerGame();
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false), new CompanionSetup.Seat("Cy", false)));
        CompanionGameState.of(setup.initialState()).applyNow(game);
        return game;
    }

    /** Resolves the stack, as the table does when every seat passes. */
    private static void resolveAll(Game game) {
        for (int i = 0; i < 50; i++) {
            game.getAction().checkStateEffects(true);
            game.getStack().addAllTriggeredAbilitiesToStack();
            if (game.getStack().isEmpty()) {
                return;
            }
            game.getStack().resolveStack();
        }
        throw new AssertionError("The stack never emptied");
    }

    @Test
    public void startsWithHiddenHandsAndLibraries() {
        Game game = startTable();
        for (Player p : game.getPlayers()) {
            assertEquals(CompanionCards.hiddenInHand(p), 7);
            assertEquals(p.getCardsIn(ZoneType.Library).size(), CompanionSetup.DEFAULT_LIBRARY);
            assertEquals(p.getLife(), 40);
        }
    }

    @Test
    public void castFromHandResolvesItsEnterTriggerForAPlayerWhoseTurnItIsNot() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        Player caster = game.getNextPlayerAfter(active);

        TableEditor.Result result = TableEditor.addCard(caster, "Gray Merchant of Asphodel", true, TableEditor.Mode.CAST);
        assertTrue(result.done(), result.message());
        resolveAll(game);

        assertEquals(CompanionCards.hiddenInHand(caster), 6);
        assertEquals(caster.getLife(), 40 + 2 * 2);
        for (Player p : game.getPlayers()) {
            if (p != caster) {
                assertEquals(p.getLife(), 38, p.getName());
            }
        }
    }

    @Test
    public void enterTriggerDrawsAnUnknownCard() {
        Game game = startTable();
        Player p = game.getPhaseHandler().getPlayerTurn();
        assertTrue(TableEditor.addCard(p, "Elvish Visionary", true, TableEditor.Mode.CAST).done());
        resolveAll(game);
        assertEquals(CompanionCards.hiddenInHand(p), 7, "one card left the hand, one was drawn");
        assertEquals(p.getCardsIn(ZoneType.Library).size(), CompanionSetup.DEFAULT_LIBRARY - 1);
    }

    @Test
    public void putOntoBattlefieldStillHasItsEnterEffect() {
        Game game = startTable();
        Player p = game.getPhaseHandler().getPlayerTurn();
        assertTrue(TableEditor.addCard(p, "Gray Merchant of Asphodel", false, TableEditor.Mode.PUT_ONTO_BATTLEFIELD).done());
        resolveAll(game);
        assertEquals(CompanionCards.hiddenInHand(p), 7, "it didn't come from the hand");
        assertEquals(p.getLife(), 44);
    }

    @Test
    public void aKnownCardLeavesTheHandInsteadOfAnUnknownOne() {
        Game game = startTable();
        Player p = game.getPhaseHandler().getPlayerTurn();
        addCardToZone("Sol Ring", p, ZoneType.Hand);
        assertTrue(TableEditor.addCard(p, "Sol Ring", true, TableEditor.Mode.CAST).done());
        resolveAll(game);
        assertEquals(CompanionCards.hiddenInHand(p), 7);
        assertEquals(countCardsWithName(game, "Sol Ring", ZoneType.Hand), 0);
        assertEquals(countCardsWithName(game, "Sol Ring"), 1);
    }

    @Test
    public void saveAndLoadKeepTheTable() {
        Game game = startTable();
        Player p = game.getPhaseHandler().getPlayerTurn();
        TableEditor.addCard(p, "Gray Merchant of Asphodel", true, TableEditor.Mode.CAST);
        resolveAll(game);
        String saved = CompanionGameState.capture(game);

        Game loaded = initAndCreateThreePlayerGame();
        CompanionGameState.of(List.of(saved.split("\n"))).applyNow(loaded);
        Player q = loaded.getPlayers().get(game.getPlayers().indexOf(p));
        assertEquals(CompanionCards.hiddenInHand(q), 6);
        assertEquals(q.getLife(), 44);
        assertEquals(countCardsWithName(loaded, "Gray Merchant of Asphodel"), 1);
        assertEquals(q.getCardsIn(ZoneType.Library).size(), CompanionSetup.DEFAULT_LIBRARY);
        assertEquals(q.getCardsIn(ZoneType.Command).size(), 1, "the table effect, and only one");
    }

    @Test
    public void nobodyLosesAtZeroLife() {
        Game game = startTable();
        Player p = game.getPlayers().get(1);
        p.setLife(0, null);
        game.getAction().checkStateEffects(true);
        assertTrue(!p.hasLost() && !game.isGameOver());
    }

    @Test
    public void nobodyLosesDrawingFromAnEmptyLibrary() {
        Game game = startTable();
        Player p = game.getPlayers().get(1);
        for (forge.game.card.Card c : p.getCardsIn(ZoneType.Library)) {
            game.getAction().ceaseToExist(c, true);
        }
        p.drawCards(1);
        game.getAction().checkStateEffects(true);
        assertTrue(!p.hasLost() && !game.isGameOver());
    }

    @Test
    public void aDrawnUnknownCardStillShowsAsACardBack() {
        Game game = startTable();
        Player p = game.getPlayers().get(0);
        p.drawCards(1); // the card is rebuilt from its printing as it moves
        String back = forge.ImageKeys.getTokenKey(forge.ImageKeys.HIDDEN_CARD);
        for (forge.game.card.Card c : p.getCardsIn(ZoneType.Hand)) {
            assertEquals(c.getCurrentState().getImageKey(), back);
        }
    }

    @Test
    public void theCombatOptionIsSavedWithTheGame() {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false)));
        assertFalse(setup.engineCombat(), "off unless the operator turns it on");
        assertTrue(CompanionStore.fromJson(CompanionStore.toJson(setup.withEngineCombat(true))).engineCombat());
        com.google.gson.JsonObject older = com.google.gson.JsonParser.parseString(
                CompanionStore.toJson(setup.withEngineCombat(true))).getAsJsonObject();
        older.remove("combat");
        assertFalse(CompanionStore.fromJson(older.toString()).engineCombat(),
                "a game saved before the option has combat off");
    }

    @Test
    public void combatForOneTurnEndsWithThatTurn() {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false)));
        CompanionTable table = new CompanionTable("t", setup, new CompanionStore(new java.io.File("unused")));
        table.setEngineCombatForTurn(3, true);
        assertTrue(table.engineCombat(3));
        assertFalse(table.engineCombat(4), "the next turn is back to the game's setting");
        table.setEngineCombatForTurn(3, false);
        assertFalse(table.engineCombat(3));
        assertFalse(table.engineCombatThisTurn(), "no game running yet is no turn, not turn 0");
    }

    /**
     * With combat at the table, the controller declares nobody, unless that isn't legal: then the engine would ask
     * again forever, so the operator is prompted instead. This is the check that tells the two apart.
     */
    @Test
    public void declaringNobodyIsIllegalOnlyWhenACreatureMustAttack() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        assertTrue(TableEditor.addCard(active, "Grizzly Bears", true, TableEditor.Mode.PUT_ONTO_BATTLEFIELD).done());
        resolveAll(game);
        for (forge.game.card.Card c : active.getCreaturesInPlay()) {
            c.setSickness(false);
        }
        assertTrue(forge.game.combat.CombatUtil.validateAttackers(startCombat(game, active)), "nobody has to attack");

        assertTrue(TableEditor.addCard(active, "Juggernaut", true, TableEditor.Mode.PUT_ONTO_BATTLEFIELD).done());
        resolveAll(game);
        for (forge.game.card.Card c : active.getCreaturesInPlay()) {
            c.setSickness(false);
        }
        assertFalse(forge.game.combat.CombatUtil.validateAttackers(startCombat(game, active)),
                "Juggernaut attacks each combat if able");
    }

    /** A combat as the declare-attackers step has it: the game's own, which attack requirements read. */
    private static forge.game.combat.Combat startCombat(Game game, Player attacker) {
        forge.game.combat.Combat combat = new forge.game.combat.Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        return combat;
    }

    @Test
    public void theMatcherNeverSuggestsThePlaceholder() {
        CardNameMatcher matcher = CardNameMatcher.fromStaticData();
        assertTrue(matcher.match(CompanionCards.PLACEHOLDER_NAME, 5).stream()
                .noneMatch(s -> s.card().equals(CompanionCards.PLACEHOLDER_NAME)));
    }
}
