package forge.gamemodes.companion;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
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
    public void theMatcherNeverSuggestsThePlaceholder() {
        CardNameMatcher matcher = CardNameMatcher.fromStaticData();
        assertTrue(matcher.match(CompanionCards.PLACEHOLDER_NAME, 5).stream()
                .noneMatch(s -> s.card().equals(CompanionCards.PLACEHOLDER_NAME)));
    }
}
