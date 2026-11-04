package forge.gamemodes.companion;

import forge.StaticData;
import forge.ai.AITest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
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
    public void startingLifeComesFromSetup() {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false), new CompanionSetup.Seat("Cy", false)))
                .withLife(20);
        Game game = initAndCreateThreePlayerGame();
        CompanionGameState.of(setup.initialState()).applyNow(game);
        for (Player p : game.getPlayers()) {
            assertEquals(p.getLife(), 20, p.getName());
        }
        assertEquals(CompanionStore.fromJson(CompanionStore.toJson(setup)).life(), 20);
    }

    @Test
    public void startingLifeBelowOneIsRejected() {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false)));
        assertThrows(IllegalArgumentException.class, () -> setup.withLife(0));
        assertEquals(setup.withLife(1).life(), 1, "1 is the lowest a game can start at");
    }

    @Test
    public void setLifeRaisesAndLowersIt() {
        Game game = startTable();
        Player bo = game.getPlayers().get(1);
        TableEditor.Result up = TableEditor.setLife(bo, 47, false);
        assertTrue(up.done(), up.message());
        assertEquals(bo.getLife(), 47);
        TableEditor.Result down = TableEditor.setLife(bo, 12, false);
        assertTrue(down.done(), down.message());
        assertEquals(bo.getLife(), 12);
        assertEquals(down.message(), bo.getName() + "'s life is set to 12");
    }

    /** At the table, life at 0 or below doesn't end a player's game: the table effect stops it, as for a tracker. */
    @Test
    public void setLifeBelowZeroLeavesThePlayerInTheGame() {
        Game game = startTable();
        Player bo = game.getPlayers().get(1);
        assertTrue(TableEditor.setLife(bo, -3, false).done());
        resolveAll(game);
        assertEquals(bo.getLife(), -3);
        assertTrue(!bo.hasLost() && !game.isGameOver());
    }

    /** Decision D2: a correction isn't a life gain, so it doesn't put another counter on a Pridemate. */
    @Test
    public void setLifeDoesNotTriggerOnLifeGained() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Card pridemate = addCardToZone("Ajani's Pridemate", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertTrue(TableEditor.lifeChangeCouldTrigger(ann, 45), "Pridemate's trigger is live, so the test means something");
        assertTrue(TableEditor.setLife(ann, 45, false).done());
        resolveAll(game);
        assertEquals(ann.getLife(), 45);
        assertEquals(pridemate.getCounters(forge.game.card.CounterEnumType.P1P1), 0);
        assertFalse(game.getTriggerHandler().isTriggerSuppressed(forge.game.trigger.TriggerType.LifeGained),
                "the suppression ends with the call");

        ann.gainLife(1, null, null);
        resolveAll(game);
        assertEquals(pridemate.getCounters(forge.game.card.CounterEnumType.P1P1), 1, "a real gain still triggers");
    }

    /** A correction isn't a loss this turn: spectacle and "an opponent lost life this turn" don't see it. */
    @Test
    public void aDownwardCorrectionIsNotLifeLostThisTurn() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        addCardToZone("Exquisite Blood", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertTrue(TableEditor.lifeChangeCouldTrigger(bo, 35), "Exquisite Blood's trigger is live");

        assertTrue(TableEditor.setLife(bo, 35, false).done());
        resolveAll(game);
        assertEquals(bo.getLife(), 35);
        assertEquals(ann.getLife(), 40, "Exquisite Blood doesn't see a correction");
        assertEquals(bo.getLifeLostThisTurn(), 0);
        assertEquals(ann.getOpponentLostLifeThisTurn(), 0);

        bo.loseLife(2, false, false, null);
        assertEquals(bo.getLifeLostThisTurn(), 2, "a real loss still counts");
    }

    /**
     * Forge sets life from a state by a gain or a loss. The table's start (20 to 40 here) mustn't count as one this
     * turn, or "the first time you gain life each turn" wouldn't fire on turn 1 or after a resume.
     */
    @Test
    public void startingAGameLeavesNoLifeGainedOrLostThisTurn() {
        Game game = startTable();
        assertEquals(game.getPlayers().get(0).getLife(), 40, "the state did change the life");
        for (Player p : game.getPlayers()) {
            assertEquals(p.getLifeGainedThisTurn(), 0, p.getName());
            assertEquals(p.getLifeGainedTimesThisTurn(), 0, p.getName());
            assertEquals(p.getLifeLostThisTurn(), 0, p.getName());
            assertEquals(p.getLifeGainedByTeamThisTurn(), 0, p.getName());
        }
    }

    /** A correction isn't a gain this turn: the first real gain is still the first time. */
    @Test
    public void anUpwardCorrectionLeavesTheFirstGainOfTheTurn() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        addCardToZone("Vanguard Seraph", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertTrue(TableEditor.lifeChangeCouldTrigger(ann, 45));

        assertTrue(TableEditor.setLife(ann, 45, false).done());
        resolveAll(game);
        assertEquals(ann.getLife(), 45);
        assertEquals(ann.getLifeGainedThisTurn(), 0);
        assertEquals(ann.getLifeGainedTimesThisTurn(), 0);
        assertTrue(TableEditor.lifeChangeCouldTrigger(ann, 50), "the next real gain is still the first this turn");

        assertTrue(TableEditor.setLife(ann, 50, true).done());
        assertEquals(ann.getLifeGainedThisTurn(), 5, "a real gain counts");
        assertEquals(ann.getLifeGainedTimesThisTurn(), 1);
        assertFalse(TableEditor.lifeChangeCouldTrigger(ann, 55), "after a real gain, the next isn't the first");
    }

    /** With every trigger suppressed by someone else, a correction leaves that suppression as it was. */
    @Test
    public void aCorrectionLeavesAllTriggersSuppressed() {
        Game game = startTable();
        game.getTriggerHandler().setSuppressAllTriggers(true);
        try {
            TableEditor.setLife(game.getPlayers().get(1), 30, false);
            game.getTriggerHandler().setSuppressAllTriggers(false);
            assertFalse(game.getTriggerHandler().isTriggerSuppressed(forge.game.trigger.TriggerType.LifeLost));
            assertFalse(game.getTriggerHandler().isTriggerSuppressed(forge.game.trigger.TriggerType.LifeGained));
        } finally {
            game.getTriggerHandler().setSuppressAllTriggers(false);
        }
    }

    @Test
    public void setLifeLeavesASuppressionItDidNotSet() {
        Game game = startTable();
        game.getTriggerHandler().suppressMode(forge.game.trigger.TriggerType.LifeLost);
        TableEditor.setLife(game.getPlayers().get(1), 30, false);
        assertTrue(game.getTriggerHandler().isTriggerSuppressed(forge.game.trigger.TriggerType.LifeLost));
        assertFalse(game.getTriggerHandler().isTriggerSuppressed(forge.game.trigger.TriggerType.LifeGained));
    }

    /** Decision D2: asked to, the change is a real gain, so Pridemate sees it. */
    @Test
    public void setLifeWithTriggersIsARealGain() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Card pridemate = addCardToZone("Ajani's Pridemate", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);

        assertTrue(TableEditor.lifeChangeCouldTrigger(ann, 45));
        assertTrue(TableEditor.setLife(ann, 45, true).done());
        resolveAll(game);
        assertEquals(ann.getLife(), 45);
        assertEquals(pridemate.getCounters(forge.game.card.CounterEnumType.P1P1), 1);
    }

    @Test
    public void noLifeTriggerOnTheTableMeansNoQuestion() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        addCardToZone("Runeclaw Bear", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertFalse(TableEditor.lifeChangeCouldTrigger(ann, 45));
        assertFalse(TableEditor.lifeChangeCouldTrigger(ann, 35));
        assertFalse(TableEditor.lifeChangeCouldTrigger(ann, 40), "no change, nothing to trigger");
    }

    @Test
    public void aGainTriggerDoesNotAskAboutALossOrAnotherPlayersGain() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        addCardToZone("Ajani's Pridemate", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertFalse(TableEditor.lifeChangeCouldTrigger(ann, 35), "Pridemate only sees gains");
        assertFalse(TableEditor.lifeChangeCouldTrigger(bo, 45), "Pridemate only sees its controller's gains");
    }

    @Test
    public void aLossTriggerAsksAboutAnOpponentsLoss() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        addCardToZone("Exquisite Blood", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertTrue(TableEditor.lifeChangeCouldTrigger(bo, 35));
        assertFalse(TableEditor.lifeChangeCouldTrigger(bo, 45), "a gain isn't a loss");
        assertFalse(TableEditor.lifeChangeCouldTrigger(ann, 35), "Ann isn't her own opponent");

        assertTrue(TableEditor.setLife(bo, 35, true).done());
        resolveAll(game);
        assertEquals(ann.getLife(), 45, "Exquisite Blood gains Ann what Bo lost");
    }

    @Test
    public void sanguineBondAsksAboutItsControllersGain() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        addCardToZone("Sanguine Bond", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertTrue(TableEditor.lifeChangeCouldTrigger(ann, 41));
        assertFalse(TableEditor.lifeChangeCouldTrigger(game.getPlayers().get(1), 41));
    }

    /** "One or more opponents each lose exactly 1 life": the trigger's own amount check decides. */
    @Test
    public void aLossOfAllTriggerChecksTheAmount() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        Card obNix = addCardToZone("Ob Nixilis, Captive Kingpin", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertTrue(TableEditor.lifeChangeCouldTrigger(bo, 39));
        assertFalse(TableEditor.lifeChangeCouldTrigger(bo, 38));

        assertTrue(TableEditor.setLife(bo, 39, true).done());
        resolveAll(game);
        assertEquals(obNix.getCounters(forge.game.card.CounterEnumType.P1P1), 1, "a real loss fires LifeLostAll too");
    }

    @Test
    public void cantGainLifeMeansNoQuestion() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        addCardToZone("Ajani's Pridemate", bo, ZoneType.Battlefield);
        addCardToZone("Erebos, God of the Dead", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        assertFalse(TableEditor.lifeChangeCouldTrigger(bo, 45), "Bo can't gain life, so Pridemate can't trigger");
    }

    /** Decision D2: what the table's effects refuse is reported, not forced. */
    @Test
    public void setLifeReportsWhenAnEffectRefusesIt() {
        Game game = startTable();
        Player bo = game.getPlayers().get(1);
        addCardToZone("Platinum Emperion", bo, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);

        TableEditor.Result down = TableEditor.setLife(bo, 30, false);
        assertFalse(down.done(), down.message());
        assertEquals(bo.getLife(), 40);
        assertEquals(down.message(), bo.getName() + "'s life is 40, not 30: something on the table changes life gain or loss");
        assertFalse(TableEditor.setLife(bo, 50, false).done());
        assertEquals(bo.getLife(), 40);
    }

    @Test
    public void setLifeReportsCantGainLife() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        addCardToZone("Erebos, God of the Dead", ann, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);

        assertFalse(TableEditor.setLife(bo, 45, false).done(), "Ann's opponents can't gain life");
        assertEquals(bo.getLife(), 40);
        assertTrue(TableEditor.setLife(bo, 35, false).done(), "losing life is still allowed");
        assertTrue(TableEditor.setLife(ann, 45, false).done(), "Ann herself can gain");
    }

    @Test
    public void setLifeToTheCurrentValueIsDone() {
        Game game = startTable();
        Player bo = game.getPlayers().get(1);
        TableEditor.Result same = TableEditor.setLife(bo, 40, false);
        assertTrue(same.done(), same.message());
        assertEquals(bo.getLife(), 40);
    }

    @Test
    public void aSetLifeSurvivesSaveAndLoad() {
        Game game = startTable();
        Player bo = game.getPlayers().get(1);
        TableEditor.setLife(bo, 17, false);
        String saved = CompanionGameState.capture(game);

        Game loaded = initAndCreateThreePlayerGame();
        CompanionGameState.of(List.of(saved.split("\n"))).applyNow(loaded);
        assertEquals(loaded.getPlayers().get(1).getLife(), 17);
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

    /** {@code game} saved and loaded into a new game of three players. */
    private Game saveAndLoad(Game game) {
        String saved = CompanionGameState.capture(game);
        Game loaded = initAndCreateThreePlayerGame();
        CompanionGameState.of(List.of(saved.split("\n"))).applyNow(loaded);
        return loaded;
    }

    private static List<String> names(Player p, ZoneType zone) {
        return p.getCardsIn(zone).stream().map(c -> c.getName()).toList();
    }

    @Test
    public void saveAndLoadKeepTheLibraryOrderWithItsUnknownCards() {
        Game game = startTable();
        Player p = game.getPlayers().get(1);
        var library = p.getZone(ZoneType.Library);
        library.add(createCard("Forest", p), 0);
        library.add(createCard("Sol Ring", p), 3);
        library.add(createCard("Island", p), 4);
        library.add(createCard("Swamp", p), library.size());
        List<String> before = names(p, ZoneType.Library);

        Player q = saveAndLoad(game).getPlayers().get(1);
        assertEquals(names(q, ZoneType.Library), before);
        String back = forge.ImageKeys.getTokenKey(forge.ImageKeys.HIDDEN_CARD);
        assertTrue(q.getCardsIn(ZoneType.Library).stream().filter(CompanionCards::isPlaceholder)
                .allMatch(c -> c.getCurrentState().getImageKey().equals(back) && c.getZone().is(ZoneType.Library, q)));
    }

    @Test
    public void aDiscardedUnknownCardSurvivesASaveInTheGraveyard() {
        Game game = startTable();
        Player p = game.getPlayers().get(0);
        game.getAction().moveTo(ZoneType.Graveyard, p.getCardsIn(ZoneType.Hand).get(0), null, null);
        addCardToZone("Forest", p, ZoneType.Graveyard);
        List<String> before = names(p, ZoneType.Graveyard);
        assertEquals(before.size(), 2);

        Player q = saveAndLoad(game).getPlayers().get(0);
        assertEquals(names(q, ZoneType.Graveyard), before);
        assertEquals(CompanionCards.hiddenInHand(q), 6);
    }

    @Test
    public void aRememberedUnknownCardLoadsWithoutANullReference() {
        Game game = startTable();
        Player p = game.getPlayers().get(0);
        var solRing = addCardToZone("Sol Ring", p, ZoneType.Battlefield);
        solRing.addRemembered(p.getCardsIn(ZoneType.Library).get(0));
        solRing.addImprintedCard(p.getCardsIn(ZoneType.Library).get(1));

        Game loaded = saveAndLoad(game);
        Player q = loaded.getPlayers().get(0);
        var loadedRing = findCardWithName(loaded, "Sol Ring");
        for (Object remembered : loadedRing.getRemembered()) {
            assertTrue(remembered != null);
        }
        assertTrue(!loadedRing.getImprintedCards().contains(null));
        assertEquals(q.getCardsIn(ZoneType.Library).size(), p.getCardsIn(ZoneType.Library).size());
    }

    @Test
    public void aManifestedUnknownCardStaysAFaceDownCreature() {
        Game game = startTable();
        Player p = game.getPlayers().get(0);
        var top = p.getCardsIn(ZoneType.Library).get(0);
        var manifest = new forge.game.spellability.SpellAbility.EmptySa(forge.game.ability.ApiType.Manifest, top);
        manifest.setCardState(top.getCurrentState()); // as a real manifest's ability has, for the face-down image
        top.manifest(p, manifest, null);
        p.getCardsIn(ZoneType.Battlefield).get(0).tap(null, null);

        Game loaded = saveAndLoad(game);
        Player q = loaded.getPlayers().get(0);
        assertEquals(q.getCardsIn(ZoneType.Battlefield).size(), 1);
        var manifested = q.getCardsIn(ZoneType.Battlefield).get(0);
        assertTrue(manifested.isFaceDown() && manifested.isManifested() && manifested.isCreature() && manifested.isTapped());
        assertEquals(manifested.getNetPower(), 2);
        assertEquals(q.getCardsIn(ZoneType.Library).size(), p.getCardsIn(ZoneType.Library).size());
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
        for (Card c : p.getCardsIn(ZoneType.Library)) {
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
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            assertEquals(c.getCurrentState().getImageKey(), back);
        }
    }

    @Test
    public void theCombatOptionIsSavedWithTheGame() {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false)));
        assertFalse(setup.engineCombat(), "off unless the operator turns it on");
        assertTrue(CompanionStore.fromJson(CompanionStore.toJson(setup.withEngineCombat(true))).engineCombat());
        JsonObject older = JsonParser.parseString(
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

    /** Puts {@code name} onto the battlefield for {@code player} through the table, as a creature logged this turn. */
    private static Card putIn(Game game, Player player, String name) {
        List<Card> before = new java.util.ArrayList<>(player.getCardsIn(ZoneType.Battlefield));
        assertTrue(TableEditor.addCard(player, name, false, TableEditor.Mode.PUT_ONTO_BATTLEFIELD).done());
        resolveAll(game);
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (!before.contains(c) && c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError(name + " isn't on the battlefield");
    }

    @Test
    public void aReadiedCreatureCanAttackAndAnUnpickedOneStaysSick() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        Card first = putIn(game, active, "Grizzly Bears");
        Card second = putIn(game, active, "Grizzly Bears");
        assertTrue(first.isSick());
        assertFalse(CombatUtil.canAttack(first), "it entered this turn");

        TableEditor.Result result = TableEditor.readyCreatures(game, List.of(first.getId()));
        assertTrue(result.done(), result.message());
        assertEquals(result.message(), "Ready to attack: Grizzly Bears");
        assertFalse(first.isSick());
        assertTrue(CombatUtil.canAttack(first));
        assertTrue(second.isSick(), "the operator didn't pick it");
        assertFalse(CombatUtil.canAttack(second));
    }

    @Test
    public void readyingSkipsAndNamesWhatIsntACreatureOnTheBattlefield() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        Card bears = putIn(game, active, "Grizzly Bears");
        Card ring = putIn(game, active, "Sol Ring");
        int gone = 999_999;

        TableEditor.Result result = TableEditor.readyCreatures(game, List.of(ring.getId(), bears.getId(), gone));
        assertTrue(result.done(), result.message());
        assertEquals(result.message(),
                "Ready to attack: Grizzly Bears (skipped, not a creature on the battlefield: Sol Ring, #999999)");
        assertFalse(bears.isSick());

        TableEditor.Result nothing = TableEditor.readyCreatures(game, List.of(ring.getId()));
        assertFalse(nothing.done(), nothing.message());
        assertTrue(nothing.message().startsWith("Nothing to ready"), nothing.message());
    }

    @Test
    public void aCreatureThatLeftTheBattlefieldIsSkipped() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        Card bears = putIn(game, active, "Grizzly Bears");
        Card moved = game.getAction().moveToGraveyard(bears, null);
        resolveAll(game);

        TableEditor.Result result = TableEditor.readyCreatures(game, List.of(moved.getId()));
        assertFalse(result.done(), result.message());
    }

    @Test
    public void aReadiedCreatureStaysReadyThroughSaveAndLoad() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        Card ready = putIn(game, active, "Grizzly Bears");
        putIn(game, active, "Llanowar Elves");
        TableEditor.readyCreatures(game, List.of(ready.getId()));
        String saved = CompanionGameState.capture(game);

        Game loaded = initAndCreateThreePlayerGame();
        CompanionGameState.of(List.of(saved.split("\n"))).applyNow(loaded);
        Player q = loaded.getPlayers().get(game.getPlayers().indexOf(active));
        assertEquals(loaded.getPhaseHandler().getPlayerTurn(), q, "still their turn, so sickness matters");
        Card bears = null;
        Card elves = null;
        for (Card c : q.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Grizzly Bears")) {
                bears = c;
            } else if (c.getName().equals("Llanowar Elves")) {
                elves = c;
            }
        }
        assertTrue(bears != null && elves != null);
        assertFalse(bears.isSick());
        assertTrue(CombatUtil.canAttack(bears));
        assertTrue(elves.isSick(), "an unreadied creature stays sick too");
    }

    @Test
    public void sickCreaturesListsOnlyTheSickOnes() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        Player other = game.getNextPlayerAfter(active);
        Card ready = putIn(game, active, "Grizzly Bears");
        Card sick = putIn(game, active, "Llanowar Elves");
        putIn(game, active, "Raging Goblin"); // haste
        putIn(game, active, "Sol Ring");
        Card othersBears = putIn(game, other, "Grizzly Bears");
        TableEditor.readyCreatures(game, List.of(ready.getId()));

        assertEquals(TableEditor.sickCreatures(active), List.of(sick));
        assertEquals(TableEditor.sickCreatures(other), List.of(othersBears));
    }

    /** What the game decides for the operator reaches the screen, and the game's log. */
    @Test
    public void aNoticeReachesTheOperatorAndTheLog() throws java.io.IOException {
        java.io.File dir = java.nio.file.Files.createTempDirectory("companion").toFile();
        assertTrue(new java.io.File(dir, "t").mkdir());
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false)));
        CompanionTable table = new CompanionTable("t", setup, new CompanionStore(dir));
        List<String> shown = new java.util.ArrayList<>();
        table.setOnNotice(shown::add);
        table.tell("A creature must attack");
        assertEquals(shown, List.of("A creature must attack"));
        java.io.File[] logs = new java.io.File(dir, "t").listFiles();
        assertTrue(logs != null && logs.length == 1);
        assertTrue(java.nio.file.Files.readString(logs[0].toPath()).contains("A creature must attack"));
    }

    /**
     * With combat at the table, the controller declares nobody, unless that isn't legal: then the engine would ask
     * again forever, so that combat runs through the engine for the turn instead. This checks Forge's rule that
     * tells the two apart, not the controller.
     */
    @Test
    public void forgeRejectsAnEmptyAttackOnlyWhenACreatureMustAttack() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        assertTrue(TableEditor.addCard(active, "Grizzly Bears", true, TableEditor.Mode.PUT_ONTO_BATTLEFIELD).done());
        resolveAll(game);
        for (Card c : active.getCreaturesInPlay()) {
            c.setSickness(false);
        }
        assertTrue(CombatUtil.validateAttackers(startCombat(game, active)), "nobody has to attack");

        assertTrue(TableEditor.addCard(active, "Juggernaut", true, TableEditor.Mode.PUT_ONTO_BATTLEFIELD).done());
        resolveAll(game);
        for (Card c : active.getCreaturesInPlay()) {
            c.setSickness(false);
        }
        assertFalse(CombatUtil.validateAttackers(startCombat(game, active)),
                "Juggernaut attacks each combat if able");
    }

    /** A combat as the declare-attackers step has it: the game's own, which attack requirements read. */
    private static Combat startCombat(Game game, Player attacker) {
        Combat combat = new Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        return combat;
    }

    /**
     * A tap on a life total opens the life dialog only at a priority prompt; at a target prompt it answers the prompt
     * (chunk 7). With no GUI to ask, a question from an op gets its default answer.
     */
    @Test
    public void atPriorityPromptOnlyWhileASeatWaitsForPriority() {
        Game game = startTable();
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false), new CompanionSetup.Seat("Cy", false)));
        CompanionTable table = new CompanionTable("t", setup, new CompanionStore(new java.io.File("unused")));
        assertFalse(table.atPriorityPrompt(), "no seats yet");
        Player active = game.getPhaseHandler().getPlayerTurn();
        CompanionPlayerController seat = new CompanionPlayerController(game, active,
                new forge.ai.LobbyPlayerAi("Ann", null), table);
        // without a screen: the input proxy would show each prompt on a GUI this test doesn't have
        seat.getInputQueue().deleteObservers();
        assertFalse(table.atPriorityPrompt(), "no prompt open");

        forge.gamemodes.match.input.InputPassPriority priority = new forge.gamemodes.match.input.InputPassPriority(seat);
        seat.getInputQueue().setInput(priority);
        assertTrue(table.atPriorityPrompt());

        forge.gamemodes.match.input.InputSyncronizedBase target = new forge.gamemodes.match.input.InputSyncronizedBase(seat) {
            @Override
            protected void showMessage() {
            }

            @Override
            public String getActivateAction(Card card) {
                return null;
            }
        };
        seat.getInputQueue().setInput(target);
        assertFalse(table.atPriorityPrompt(), "a target prompt on top of priority keeps the tap");
        seat.getInputQueue().removeInput(target);
        assertTrue(table.atPriorityPrompt());
        seat.getInputQueue().removeInput(priority);
        assertFalse(table.atPriorityPrompt());

        assertFalse(table.confirm(game, "Trigger life gain/loss effects?", false), "no GUI: the default answer");
        assertTrue(table.confirm(game, "Trigger life gain/loss effects?", true));
    }

    /** A table in {@code phase} of the first player's turn, set by a game state, as a resumed game gets it. */
    private Game startTableIn(PhaseType phase, String... extraLines) {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false), new CompanionSetup.Seat("Cy", false)));
        List<String> lines = new ArrayList<>();
        for (String line : setup.initialState()) {
            lines.add(line.startsWith("activephase=") ? "activephase=" + phase.name() : line);
        }
        lines.addAll(List.of(extraLines));
        Game game = initAndCreateThreePlayerGame();
        CompanionGameState.of(lines).applyNow(game);
        assertEquals(game.getPhaseHandler().getPhase(), phase);
        return game;
    }

    @DataProvider
    public Object[][] mainPhaseForASorcery() {
        return new Object[][] {
                {PhaseType.UNTAP, PhaseType.MAIN1},
                {PhaseType.UPKEEP, PhaseType.MAIN1},
                {PhaseType.DRAW, PhaseType.MAIN1},
                {PhaseType.MAIN1, null},
                {PhaseType.COMBAT_BEGIN, PhaseType.MAIN2},
                {PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.MAIN2},
                {PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.MAIN2},
                {PhaseType.COMBAT_DAMAGE, PhaseType.MAIN2},
                {PhaseType.COMBAT_END, PhaseType.MAIN2},
                {PhaseType.MAIN2, null},
                {PhaseType.END_OF_TURN, null},
                {PhaseType.CLEANUP, null},
        };
    }

    /** AC-42: before combat a sorcery waits for Main 1, from combat on for Main 2, and from the end step never. */
    @Test(dataProvider = "mainPhaseForASorcery")
    public void anActivePlayersSorceryWaitsForTheNextMainPhase(PhaseType phase, PhaseType expected) {
        Game game = startTableIn(phase);
        Player active = game.getPhaseHandler().getPlayerTurn();
        assertEquals(TableEditor.mainPhaseFor(game, active, "Divination", TableEditor.Mode.CAST), expected,
                "Divination in " + phase);
    }

    @Test
    public void aSorceryInAMainPhaseWithSomethingOnTheStackWaitsForThatMainPhase() {
        Game main1 = startTableIn(PhaseType.MAIN1, "p0putonstack=Divination");
        assertFalse(main1.getStack().isEmpty());
        assertEquals(TableEditor.mainPhaseFor(main1, main1.getPhaseHandler().getPlayerTurn(), "Divination",
                TableEditor.Mode.CAST), PhaseType.MAIN1);

        Game main2 = startTableIn(PhaseType.MAIN2, "p0putonstack=Divination");
        assertFalse(main2.getStack().isEmpty());
        assertEquals(TableEditor.mainPhaseFor(main2, main2.getPhaseHandler().getPlayerTurn(), "Divination",
                TableEditor.Mode.CAST), PhaseType.MAIN2);
    }

    @Test
    public void onlyTheActivePlayersCastSorceryWaits() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Player active = game.getPhaseHandler().getPlayerTurn();
        for (Player other : game.getPlayers()) {
            if (!other.equals(active)) {
                assertNull(TableEditor.mainPhaseFor(game, other, "Divination", TableEditor.Mode.CAST),
                        "not " + other.getName() + "'s turn: cast in place");
            }
        }
        assertNull(TableEditor.mainPhaseFor(game, active, "Grizzly Bears", TableEditor.Mode.CAST),
                "a creature spell doesn't wait (sorceries only, D5)");
        assertNull(TableEditor.mainPhaseFor(game, active, "Lightning Bolt", TableEditor.Mode.CAST), "an instant");
        assertNull(TableEditor.mainPhaseFor(game, active, "Divination", TableEditor.Mode.PUT_ONTO_BATTLEFIELD),
                "put in, not cast");
        assertNull(TableEditor.mainPhaseFor(game, active, "Not A Card Name", TableEditor.Mode.CAST),
                "a token or emblem name");
    }

    @Test
    public void waitsForMainPhaseOnlyForACastSorcery() {
        PaperCard divination = StaticData.instance().getCommonCards().getUniqueByName("Divination");
        PaperCard bears = StaticData.instance().getCommonCards().getUniqueByName("Grizzly Bears");
        assertTrue(TableEditor.waitsForMainPhase(divination, TableEditor.Mode.CAST));
        assertFalse(TableEditor.waitsForMainPhase(divination, TableEditor.Mode.PUT_ONTO_BATTLEFIELD));
        assertFalse(TableEditor.waitsForMainPhase(bears, TableEditor.Mode.CAST));
    }

    /** A table with a real store in a temp directory, collecting what it reports. */
    private static final class Recorded {
        final CompanionTable table;
        final CompanionStore store;
        final List<String> notices = new ArrayList<>();
        final List<String> results = new ArrayList<>();

        Recorded() {
            try {
                java.io.File dir = java.nio.file.Files.createTempDirectory("companion").toFile();
                assertTrue(new java.io.File(dir, "t").mkdir());
                CompanionSetup setup = CompanionSetup.withDefaults(List.of(new CompanionSetup.Seat("Ann", true),
                        new CompanionSetup.Seat("Bo", false), new CompanionSetup.Seat("Cy", false)));
                store = new CompanionStore(dir);
                table = new CompanionTable("t", setup, store);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            table.setOnNotice(notices::add);
            table.setOnResult(r -> results.add(r.message()));
        }

        /** A new table on {@code before}'s store, as a relaunch of the app makes. */
        Recorded(Recorded before) {
            store = before.store;
            table = new CompanionTable("t", before.table.setup(), store);
            table.setOnNotice(notices::add);
            table.setOnResult(r -> results.add(r.message()));
        }
    }

    private static CompanionTable.AddCard cast(Player player, String name) {
        return new CompanionTable.AddCard(player.getName(), name, false, TableEditor.Mode.CAST);
    }

    /** How many {@code name}s were cast: on the stack, or resolved into a graveyard. */
    private static int castCount(Game game, String name) {
        return (int) game.getCardsIn(List.of(ZoneType.Stack, ZoneType.Graveyard)).stream()
                .filter(c -> c.getName().equals(name)).count();
    }

    /** Moves the game to {@code phase} of the same turn, as the loop would reach it. */
    private static void moveTo(Game game, PhaseType phase) {
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        ph.devModeSet(phase, ph.getPlayerTurn(), ph.getTurn());
    }

    /** AC-42: an active player's sorcery in upkeep waits for Main 1, and runs there with an empty stack. */
    @Test
    public void aSorceryInUpkeepIsParkedUntilMain1() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Player active = game.getPhaseHandler().getPlayerTurn();
        Recorded rec = new Recorded();
        rec.table.submit(cast(active, "Divination"));

        assertTrue(rec.table.runPending(game), "the op was taken, to be parked");
        assertTrue(rec.table.isParked());
        assertFalse(rec.table.hasPending());
        assertEquals(castCount(game, "Divination"), 0, "not cast in upkeep");
        assertEquals(rec.notices, List.of("Divination waits for Main 1"));
        assertTrue(rec.results.isEmpty(), "nothing ran yet");
        assertFalse(rec.table.parkedReady(game), "still upkeep");
        moveTo(game, PhaseType.DRAW);
        assertFalse(rec.table.parkedReady(game), "the draw step");

        moveTo(game, PhaseType.MAIN1);
        assertTrue(rec.table.parkedReady(game), "Main 1 with an empty stack");
        rec.table.runParked(game);
        assertFalse(rec.table.isParked());
        assertFalse(rec.table.parkedReady(game), "nothing parked");
        assertEquals(castCount(game, "Divination"), 1);
        assertEquals(rec.results.size(), 1, rec.results.toString());
        assertEquals(rec.notices.size(), 1, "no notice when it ran where it waited: " + rec.notices);
    }

    @Test
    public void aParkedSorceryWaitsInItsMainPhaseUntilTheStackIsEmpty() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Player active = game.getPhaseHandler().getPlayerTurn();
        Recorded rec = new Recorded();
        rec.table.submit(cast(active, "Divination"));
        rec.table.runPending(game);
        moveTo(game, PhaseType.MAIN1);
        TableEditor.addCard(active, "Opt", false, TableEditor.Mode.CAST);
        assertFalse(game.getStack().isEmpty());
        assertFalse(rec.table.parkedReady(game), "something is on the stack");
        resolveAll(game);
        assertTrue(rec.table.parkedReady(game));
    }

    /** AC-42: from combat on, a sorcery waits for Main 2. */
    @Test
    public void aSorceryInCombatIsParkedUntilMain2() {
        Game game = startTableIn(PhaseType.COMBAT_DECLARE_ATTACKERS);
        Player active = game.getPhaseHandler().getPlayerTurn();
        Recorded rec = new Recorded();
        rec.table.submit(cast(active, "Divination"));
        rec.table.runPending(game);
        assertTrue(rec.table.isParked());
        assertEquals(rec.notices, List.of("Divination waits for Main 2"));
        moveTo(game, PhaseType.COMBAT_END);
        assertFalse(rec.table.parkedReady(game));

        moveTo(game, PhaseType.MAIN2);
        assertTrue(rec.table.parkedReady(game));
        rec.table.runParked(game);
        assertEquals(castCount(game, "Divination"), 1);
        assertFalse(rec.table.isParked());
    }

    /** D5, no going back: a parked op whose turn ends first is cast at once, with a notice. */
    @Test
    public void aParkedSorceryWhoseTurnEndsIsCastAtOnceWithANotice() {
        Game game = startTableIn(PhaseType.UPKEEP);
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        Player active = ph.getPlayerTurn();
        Recorded rec = new Recorded();
        rec.table.submit(cast(active, "Divination"));
        rec.table.runPending(game);

        Player next = game.getNextPlayerAfter(active);
        ph.devModeSet(PhaseType.UPKEEP, next, ph.getTurn() + 1);
        assertTrue(rec.table.parkedReady(game), "another turn");
        rec.table.runParked(game);
        assertFalse(rec.table.isParked());
        assertEquals(castCount(game, "Divination"), 1);
        assertEquals(rec.notices.get(rec.notices.size() - 1), "The turn ended before Main 1: Divination is cast now");
    }

    /** A Main 1 that was skipped: the sorcery waits for Main 2 instead of being cast in combat. */
    @Test
    public void aParkedSorceryWhoseMain1WasSkippedWaitsForMain2() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Player active = game.getPhaseHandler().getPlayerTurn();
        Recorded rec = new Recorded();
        rec.table.submit(cast(active, "Divination"));
        rec.table.runPending(game);

        moveTo(game, PhaseType.COMBAT_BEGIN);
        assertTrue(rec.table.parkedReady(game), "past Main 1");
        rec.table.runParked(game);
        assertTrue(rec.table.isParked(), "parked again");
        assertEquals(castCount(game, "Divination"), 0);
        assertEquals(rec.notices.get(rec.notices.size() - 1), "Divination waits for Main 2");
        assertFalse(rec.table.parkedReady(game));

        moveTo(game, PhaseType.END_OF_TURN);
        assertTrue(rec.table.parkedReady(game), "past Main 2 too");
        rec.table.runParked(game);
        assertFalse(rec.table.isParked());
        assertEquals(castCount(game, "Divination"), 1);
        assertEquals(rec.notices.get(rec.notices.size() - 1),
                "The main phases of this turn are over: Divination is cast now");
    }

    /**
     * D5, more than one op: a card added while one is parked waits behind it, even one that wouldn't wait by itself,
     * so the cards reach the game in the table's order.
     */
    @Test
    public void aCardAddedAfterAParkedOneRunsAfterIt() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Player active = game.getPhaseHandler().getPlayerTurn();
        Player other = game.getNextPlayerAfter(active);
        Recorded rec = new Recorded();
        rec.table.submit(cast(active, "Divination"));
        rec.table.runPending(game);
        rec.table.submit(cast(other, "Opt"));

        assertTrue(rec.table.runPending(game));
        assertEquals(castCount(game, "Opt"), 0, "waits behind the sorcery");
        assertTrue(rec.results.isEmpty());

        moveTo(game, PhaseType.MAIN1);
        assertTrue(rec.table.parkedReady(game));
        rec.table.runParked(game);
        assertEquals(castCount(game, "Divination"), 1);
        assertEquals(castCount(game, "Opt"), 1);
        assertEquals(rec.results, List.of(active.getName() + " casts Divination", other.getName() + " casts Opt"),
                "in submit order");
    }

    /**
     * Only cards wait behind a parked op: a life edit submitted while a sorcery is parked runs at once, and the
     * sorcery stays parked.
     */
    @Test
    public void aLifeEditWhileParkedRunsAtOnce() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Player active = game.getPhaseHandler().getPlayerTurn();
        Player other = game.getNextPlayerAfter(active);
        Recorded rec = new Recorded();
        rec.table.submit(cast(active, "Divination"));
        rec.table.runPending(game);
        assertTrue(rec.table.isParked());
        int life = other.getLife();
        rec.table.submit(g -> TableEditor.setLife(other, life - 3, false));

        assertTrue(rec.table.runPending(game));
        assertEquals(other.getLife(), life - 3, "made at once, while the sorcery waits");
        assertEquals(rec.results, List.of(other.getName() + "'s life is set to " + (life - 3)));
        assertEquals(rec.notices, List.of("Divination waits for Main 1"), "no waits-behind notice");
        assertTrue(rec.table.isParked(), "the sorcery still waits");
        assertEquals(castCount(game, "Divination"), 0);

        moveTo(game, PhaseType.MAIN1);
        rec.table.runParked(game);
        assertEquals(castCount(game, "Divination"), 1);
        assertEquals(other.getLife(), life - 3, "the life edit isn't made twice");
    }

    /**
     * Each card queued behind a parked op tells the operator why nothing happened yet, and runs later in order. A life
     * edit submitted between them doesn't wait.
     */
    @Test
    public void queuedOpBehindParkedOpIsNoticed() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Player active = game.getPhaseHandler().getPlayerTurn();
        Player other = game.getNextPlayerAfter(active);
        Recorded rec = new Recorded();
        rec.table.submit(new CompanionTable.AddCard(active.getName(), "Divination", false, TableEditor.Mode.CAST));
        rec.table.runPending(game);
        assertTrue(rec.table.isParked());
        assertEquals(rec.notices, List.of("Divination waits for Main 1"));

        int life = other.getLife();
        int battlefield = game.getCardsIn(ZoneType.Battlefield).size();
        rec.table.submit(g -> TableEditor.setLife(other, life - 3, false));
        rec.table.submit(new CompanionTable.AddCard(active.getName(), "Lightning Bolt", false, TableEditor.Mode.CAST));
        assertTrue(rec.table.runPending(game));
        assertEquals(rec.notices, List.of("Divination waits for Main 1", "Lightning Bolt waits behind Divination"));
        assertTrue(rec.table.isParked());
        assertEquals(other.getLife(), life - 3, "the life edit doesn't wait");
        assertEquals(game.getCardsIn(ZoneType.Battlefield).size(), battlefield);
        assertEquals(castCount(game, "Lightning Bolt"), 0);
        assertEquals(rec.results, List.of(other.getName() + "'s life is set to " + (life - 3)));

        moveTo(game, PhaseType.MAIN1);
        assertTrue(rec.table.parkedReady(game));
        rec.table.runParked(game);
        assertFalse(rec.table.isParked());
        assertEquals(rec.results, List.of(other.getName() + "'s life is set to " + (life - 3),
                active.getName() + " casts Divination", active.getName() + " casts Lightning Bolt"),
                "the cards in submit order");
    }

    /** A second sorcery behind the first finds it on the stack, so it waits again until that has resolved. */
    @Test
    public void aSecondParkedSorceryWaitsForTheFirstToResolve() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Player active = game.getPhaseHandler().getPlayerTurn();
        Recorded rec = new Recorded();
        rec.table.submit(cast(active, "Divination"));
        rec.table.submit(cast(active, "Divination"));
        rec.table.runPending(game);
        moveTo(game, PhaseType.MAIN1);
        rec.table.runParked(game);
        assertEquals(castCount(game, "Divination"), 1);
        assertTrue(rec.table.isParked(), "the second waits for the stack");
        assertFalse(rec.table.parkedReady(game));
        resolveAll(game);
        assertTrue(rec.table.parkedReady(game));
        rec.table.runParked(game);
        assertEquals(castCount(game, "Divination"), 2);
        assertFalse(rec.table.isParked());
    }

    @Test
    public void opsThatDontWaitRunAtOnce() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Player active = game.getPhaseHandler().getPlayerTurn();
        Player other = game.getNextPlayerAfter(active);
        Recorded rec = new Recorded();
        rec.table.submit(cast(active, "Opt"));
        rec.table.submit(cast(other, "Divination"));
        rec.table.submit(new CompanionTable.AddCard(active.getName(), "Divination", false,
                TableEditor.Mode.PUT_ONTO_BATTLEFIELD));
        rec.table.submit(g -> TableEditor.setLife(other, 30, false));

        assertTrue(rec.table.runPending(game));
        assertFalse(rec.table.isParked());
        assertEquals(castCount(game, "Opt"), 1, "an instant is cast in upkeep");
        assertEquals(castCount(game, "Divination"), 1, "another seat's sorcery is cast in place (the put one isn't cast)");
        assertEquals(other.getLife(), 30);
        assertEquals(rec.results.size(), 4, rec.results.toString());
        assertTrue(rec.notices.isEmpty(), rec.notices.toString());
    }

    @Test
    public void anAddCardForAPlayerWhoIsntThereIsReportedNotThrown() {
        Game game = startTableIn(PhaseType.UPKEEP);
        Recorded rec = new Recorded();
        CompanionTable.AddCard add = new CompanionTable.AddCard("Nobody", "Divination", false, TableEditor.Mode.CAST);
        assertNull(add.mainPhaseNeeded(game));
        rec.table.submit(add);
        rec.table.runPending(game);
        assertFalse(rec.table.isParked());
        assertEquals(rec.results, List.of("Nobody isn't in the game: Divination wasn't added"));
    }

    /** Ends a run of the real main loop where the table would show the operator a priority prompt. */
    private static final class OperatorAsked extends RuntimeException {
        OperatorAsked() {
            super(null, null, false, false);
        }
    }

    /**
     * A seat on the real game loop, without a screen. What it does is {@link CompanionPlayerController}'s own, except
     * where the active seat would show the operator a priority prompt (nothing parked or queued, and a main phase, a
     * non-empty stack or a combat with attackers): there the test's
     * {@code operator} answers instead, as a tap would. The attack and block declarations, which open their own
     * prompts, are recorded and answered by the test too.
     */
    private static final class LoopSeat extends CompanionPlayerController {
        final CompanionTable table;
        final java.util.function.Function<LoopSeat, List<forge.game.spellability.SpellAbility>> operator;
        final List<String> asked;
        Card attacker;
        /** Called at every priority ask of this seat, parked or not, before anything else. */
        java.util.function.Consumer<LoopSeat> onEveryAsk = seat -> { };

        LoopSeat(Game game, Player player, CompanionTable table, List<String> asked,
                java.util.function.Function<LoopSeat, List<forge.game.spellability.SpellAbility>> operator) {
            super(game, player, new forge.ai.LobbyPlayerAi(player.getName(), null), table);
            this.table = table;
            this.asked = asked;
            this.operator = operator;
            getInputQueue().deleteObservers(); // no GUI to show prompts on
        }

        @Override
        public List<forge.game.spellability.SpellAbility> chooseSpellAbilityToPlay() {
            onEveryAsk.accept(this);
            forge.game.phase.PhaseHandler ph = getGame().getPhaseHandler();
            boolean noStop = getGame().getStack().isEmpty() && !ph.getPhase().isMain()
                    && (ph.getCombat() == null || ph.getCombat().getAttackers().isEmpty());
            if (table.isParked() || table.hasPending() || getPlayer() != ph.getPlayerTurn() || noStop) {
                return super.chooseSpellAbilityToPlay(); // passes or runs ops, without a prompt
            }
            asked.add("priority " + ph.getPhase());
            return operator.apply(this);
        }

        /** The operator's tap: run what this seat does when the prompt ends without an answer. */
        List<forge.game.spellability.SpellAbility> asTheTableDoes() {
            return super.chooseSpellAbilityToPlay();
        }

        /** As the match screen answers it: one ability is played without asking (MatchController.getAbilityToPlay). */
        @Override
        public forge.game.spellability.SpellAbility getAbilityToPlay(Card hostCard,
                List<forge.game.spellability.SpellAbility> abilities, forge.util.ITriggerEvent triggerEvent) {
            assertEquals(abilities.size(), 1, "this test's cards have one ability each");
            return abilities.get(0);
        }

        @Override
        public void declareAttackers(Player attackingPlayer, Combat combat) {
            asked.add("attackers " + getPlayer().getName());
            assertTrue(table.engineCombat(getGame().getPhaseHandler().getTurn()), "the engine runs this combat");
            if (attacker != null) {
                combat.addAttacker(attacker, getGame().getNextPlayerAfter(getPlayer()));
            }
        }

        @Override
        public void declareBlockers(Player defender, Combat combat) {
            asked.add("blockers " + defender.getName());
        }
    }

    /** Puts a {@link LoopSeat} on every player of {@code game}. */
    private static List<LoopSeat> seatsFor(Game game, Recorded rec, List<String> asked,
            java.util.function.Function<LoopSeat, List<forge.game.spellability.SpellAbility>> operator) {
        List<LoopSeat> seats = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            LoopSeat seat = new LoopSeat(game, p, rec.table, asked, operator);
            p.dangerouslySetController(seat); // in place of the AI the test game was made with
            seats.add(seat);
        }
        return seats;
    }

    /** Runs the real main loop until the operator's prompt ends it; fails if it never does (the table spins). */
    private static void runLoopUntilTheOperatorIsAsked(Game game) {
        for (int step = 0; step < 500; step++) {
            try {
                game.getPhaseHandler().mainLoopStep();
            } catch (OperatorAsked stop) {
                return;
            }
            assertFalse(game.isGameOver());
        }
        throw new AssertionError("The loop never reached a prompt");
    }

    /** A table in {@code phase} of turn 2 (so the active player draws), with {@code extraLines} added. */
    private Game startTableInTurn2(PhaseType phase, String... extraLines) {
        Game game = startTableIn(phase, extraLines);
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        ph.devModeSet(phase, ph.getPlayerTurn(), false, 2);
        return game;
    }

    /**
     * AC-42 through the real main loop: a sorcery logged while an upkeep trigger waits on the stack is parked, every
     * seat passes, the trigger resolves, the draw happens, and the sorcery is cast in Main 1, where the operator is
     * asked again with it on the stack.
     */
    @Test
    public void onTheGameLoopAParkedSorceryIsCastInMain1AfterUpkeepAndDraw() {
        Game game = startTableInTurn2(PhaseType.UNTAP, "p0battlefield=Phyrexian Arena");
        Player active = game.getPhaseHandler().getPlayerTurn();
        int hand = active.getCardsIn(ZoneType.Hand).size();
        int life = active.getLife();
        Recorded rec = new Recorded();
        List<String> asked = new ArrayList<>();
        seatsFor(game, rec, asked, seat -> {
            if (!game.getStack().isEmpty() && game.getPhaseHandler().is(PhaseType.UPKEEP)) {
                // the operator logs Divination at the trigger's stop
                rec.table.submit(cast(active, "Divination"));
                return seat.asTheTableDoes();
            }
            throw new OperatorAsked();
        });

        runLoopUntilTheOperatorIsAsked(game);

        assertEquals(asked, List.of("priority UPKEEP", "priority MAIN1"), "asked at the trigger, then in Main 1 only: " + asked + rec.results + rec.notices);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertEquals(game.getStack().size(), 1);
        assertEquals(game.getStack().peekAbility().getHostCard().getName(), "Divination", "cast in Main 1");
        assertEquals(active.getLife(), life - 1, "the Arena's trigger resolved");
        assertEquals(active.getCardsIn(ZoneType.Hand).size(), hand + 2, "the Arena's draw and the turn's draw");
        assertEquals(rec.notices, List.of("Divination waits for Main 1"));
        assertFalse(rec.table.isParked());
    }

    /**
     * A life edit submitted while a sorcery is parked is made at the next stop the loop reaches, here in the draw
     * step, not after the sorcery in Main 1: only cards wait behind a parked op.
     */
    @Test
    public void onTheGameLoopALifeEditWhileParkedIsMadeAtOnce() {
        Game game = startTableInTurn2(PhaseType.UNTAP, "p0battlefield=Phyrexian Arena");
        Player active = game.getPhaseHandler().getPlayerTurn();
        Player other = game.getNextPlayerAfter(active);
        Recorded rec = new Recorded();
        List<String> asked = new ArrayList<>();
        List<PhaseType> lifeEditMadeIn = new ArrayList<>();
        List<LoopSeat> seats = seatsFor(game, rec, asked, seat -> {
            if (!game.getStack().isEmpty() && game.getPhaseHandler().is(PhaseType.UPKEEP)) {
                rec.table.submit(cast(active, "Divination"));
                return seat.asTheTableDoes();
            }
            throw new OperatorAsked();
        });
        boolean[] submitted = {false};
        for (LoopSeat s : seats) {
            s.onEveryAsk = seat -> {
                if (!submitted[0] && rec.table.isParked() && game.getPhaseHandler().is(PhaseType.DRAW)) {
                    submitted[0] = true; // the operator edits a life total while Divination waits
                    rec.table.submit(g -> {
                        lifeEditMadeIn.add(g.getPhaseHandler().getPhase());
                        return TableEditor.setLife(other, 30, false);
                    });
                }
            };
        }

        runLoopUntilTheOperatorIsAsked(game);

        assertTrue(submitted[0], "the loop passed the draw step while parked: " + asked);
        assertEquals(lifeEditMadeIn, List.of(PhaseType.DRAW), "made in the draw step, not held for Main 1");
        assertEquals(other.getLife(), 30);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertEquals(game.getStack().peekAbility().getHostCard().getName(), "Divination", "cast in Main 1");
        assertEquals(rec.results.get(0), other.getName() + "'s life is set to 30", rec.results.toString());
        assertFalse(rec.table.isParked());
    }

    /**
     * AC-42 with the engine running combat: a sorcery logged at the stop after attackers passes the rest of combat.
     * The defender is still asked for blocks, damage is dealt, and the sorcery is cast in Main 2.
     */
    @Test
    public void onTheGameLoopASorceryLoggedInEngineCombatIsCastInMain2AfterDamage() {
        Game game = startTableInTurn2(PhaseType.MAIN1, "p0battlefield=Grizzly Bears",
                "p1battlefield=Llanowar Elves", "p2battlefield=Llanowar Elves"); // so the defender can block
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        Player active = ph.getPlayerTurn();
        Recorded rec = new Recorded();
        rec.table.setEngineCombatForTurn(ph.getTurn(), true);
        List<String> asked = new ArrayList<>();
        List<LoopSeat> seats = seatsFor(game, rec, asked, seat -> {
            PhaseType phase = game.getPhaseHandler().getPhase();
            if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS) {
                rec.table.submit(cast(active, "Divination")); // logged at the stop after attackers
                return seat.asTheTableDoes();
            }
            if (castCount(game, "Divination") > 0) {
                throw new OperatorAsked();
            }
            return null; // OK: on to combat
        });
        seats.stream().filter(s -> s.getPlayer() == active).findFirst().orElseThrow().attacker =
                active.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Grizzly Bears"))
                        .findFirst().orElseThrow();

        runLoopUntilTheOperatorIsAsked(game);

        Player defender = game.getNextPlayerAfter(active);
        assertTrue(asked.contains("attackers " + active.getName()), asked.toString());
        assertTrue(asked.contains("blockers " + defender.getName()), "blocks are still asked: " + asked);
        assertEquals(asked.get(asked.size() - 1), "priority MAIN2", asked.toString());
        assertFalse(asked.contains("priority COMBAT_DECLARE_BLOCKERS"), "the trick stops are passed: " + asked);
        assertEquals(defender.getLife(), CompanionSetup.DEFAULT_LIFE - 2, "combat damage was dealt");
        assertEquals(ph.getPhase(), PhaseType.MAIN2);
        assertEquals(game.getStack().peekAbility().getHostCard().getName(), "Divination", "cast in Main 2");
        assertEquals(rec.notices, List.of("Divination waits for Main 2"));
    }

    @Test
    public void theMatcherNeverSuggestsThePlaceholder() {
        CardNameMatcher matcher = CardNameMatcher.fromStaticData();
        assertTrue(matcher.match(CompanionCards.PLACEHOLDER_NAME, 5).stream()
                .noneMatch(s -> s.card().equals(CompanionCards.PLACEHOLDER_NAME)));
    }
    /** AC-19: with no known copy, one of the unknown cards leaves the hand, and the result says so. */
    @Test
    public void anAddFromHandSaysAPlaceholderLeft() {
        Game game = startTable();
        Player p = game.getPhaseHandler().getPlayerTurn();
        TableEditor.Result result = TableEditor.addCard(p, "Sol Ring", true, TableEditor.Mode.CAST);
        assertTrue(result.done(), result.message());
        assertEquals(result.fromHand(), CompanionLogEntry.FromHand.PLACEHOLDER);
        assertEquals(result.affected(), List.of(p.getName()));
        assertEquals(result.card(), "Sol Ring");
        assertEquals(CompanionCards.hiddenInHand(p), 6);
    }

    /** AC-20: the player's known copy leaves the hand instead of an unknown card. */
    @Test
    public void anAddFromHandSaysTheKnownCopyLeft() {
        Game game = startTable();
        Player p = game.getPhaseHandler().getPlayerTurn();
        addCardToZone("Sol Ring", p, ZoneType.Hand);
        TableEditor.Result result = TableEditor.addCard(p, "Sol Ring", true, TableEditor.Mode.CAST);
        assertTrue(result.done(), result.message());
        assertEquals(result.fromHand(), CompanionLogEntry.FromHand.KNOWN);
        assertEquals(CompanionCards.hiddenInHand(p), 7);
    }

    /** AC-19 clamped: an empty hand gives nothing up, so nothing would be given back. */
    @Test
    public void anAddFromAnEmptyHandSaysNothingLeft() {
        Game game = startTable();
        Player p = game.getPhaseHandler().getPlayerTurn();
        for (Card c : new ArrayList<>(p.getCardsIn(ZoneType.Hand))) {
            game.getAction().ceaseToExist(c, true);
        }
        TableEditor.Result result = TableEditor.addCard(p, "Sol Ring", true, TableEditor.Mode.CAST);
        assertTrue(result.done(), result.message());
        assertEquals(result.fromHand(), CompanionLogEntry.FromHand.NONE);
        assertEquals(p.getCardsIn(ZoneType.Hand).size(), 0);
    }

    @Test
    public void anAddNotFromHandOrOfATokenSaysNothingLeft() {
        Game game = startTable();
        Player p = game.getPhaseHandler().getPlayerTurn();
        addCardToZone("Sol Ring", p, ZoneType.Hand);
        TableEditor.Result put = TableEditor.addCard(p, "Sol Ring", false, TableEditor.Mode.PUT_ONTO_BATTLEFIELD);
        assertEquals(put.fromHand(), CompanionLogEntry.FromHand.NONE, "the known copy stays in the hand");
        assertEquals(countCardsWithName(game, "Sol Ring", ZoneType.Hand), 1);
        TableEditor.Result token = TableEditor.addCard(p, "Treasure", true, TableEditor.Mode.CAST);
        assertTrue(token.done(), token.message());
        assertEquals(token.fromHand(), CompanionLogEntry.FromHand.NONE, "a token never comes from a hand");
        assertEquals(CompanionCards.hiddenInHand(p), 7);
    }

    @Test
    public void aFailedAddSaysNothingAboutTheHand() {
        Game game = startTable();
        Player p = game.getPhaseHandler().getPlayerTurn();
        TableEditor.Result result = TableEditor.addCard(p, "Lightning Bolt", true, TableEditor.Mode.PUT_ONTO_BATTLEFIELD);
        assertFalse(result.done());
        assertNull(result.fromHand());
        assertEquals(result.affected(), List.of(p.getName()));
        assertEquals(CompanionCards.hiddenInHand(p), 7, "the unknown card went back");
    }

    @Test
    public void lifeAndReadyResultsNameTheSeat() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        Player other = game.getNextPlayerAfter(active);
        assertEquals(TableEditor.setLife(other, 30, false).affected(), List.of(other.getName()));
        Card bears = putIn(game, other, "Grizzly Bears");
        Card ring = putIn(game, active, "Sol Ring");
        TableEditor.Result ready = TableEditor.readyCreatures(game, List.of(bears.getId(), ring.getId()));
        assertTrue(ready.done(), ready.message());
        assertEquals(ready.affected(), List.of(other.getName()), "only the readied creature's controller");
        assertNull(ready.fromHand());
    }

    /** The log entries the table writes carry the seat, the card and what left the hand, from the result. */
    @Test
    public void theTableLogsTheSeatCardAndHandFromTheResult() {
        Game game = startTable();
        Player active = game.getPhaseHandler().getPlayerTurn();
        Player other = game.getNextPlayerAfter(active);
        addCardToZone("Sol Ring", other, ZoneType.Hand);
        Recorded rec = new Recorded();
        rec.table.submit(new CompanionTable.AddCard(active.getName(), "Grizzly Bears", true, TableEditor.Mode.CAST));
        rec.table.submit(new CompanionTable.AddCard(other.getName(), "Sol Ring", true, TableEditor.Mode.CAST));
        rec.table.submit(new CompanionTable.AddCard(other.getName(), "Grizzly Bears", false,
                TableEditor.Mode.PUT_ONTO_BATTLEFIELD));
        rec.table.submit(g -> TableEditor.setLife(other, 30, false));
        rec.table.submit(new CompanionTable.AddCard("Nobody", "Sol Ring", true, TableEditor.Mode.CAST));
        assertTrue(rec.table.runPending(game));
        resolveAll(game);

        List<CompanionLogEntry> log = rec.store.loadLog("t");
        assertEquals(log.size(), 5, log.toString());
        assertEntry(log.get(0), CompanionLogEntry.Kind.ADD, active.getName(), "Grizzly Bears",
                CompanionLogEntry.FromHand.PLACEHOLDER);
        assertEntry(log.get(1), CompanionLogEntry.Kind.ADD, other.getName(), "Sol Ring",
                CompanionLogEntry.FromHand.KNOWN);
        assertEntry(log.get(2), CompanionLogEntry.Kind.ADD, other.getName(), "Grizzly Bears",
                CompanionLogEntry.FromHand.NONE);
        assertEntry(log.get(3), CompanionLogEntry.Kind.ACTION, other.getName(), null, null);
        assertEntry(log.get(4), CompanionLogEntry.Kind.NOTICE, "Nobody", "Sol Ring", null);

        Card bears = other.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().equals("Grizzly Bears")).findFirst().orElseThrow();
        rec.table.submit(g -> TableEditor.readyCreatures(g, List.of(bears.getId())));
        rec.table.runPending(game);
        List<CompanionLogEntry> after = rec.store.loadLog("t");
        assertEntry(after.get(after.size() - 1), CompanionLogEntry.Kind.ACTION, other.getName(), null, null);
    }

    // ---- Undo (slice 4, AC-27) ----

    /** What the active seat's controller does before its prompt: saves in a main phase with an empty stack. */
    private static void saveAsTheTableDoes(Recorded rec, Game game) {
        rec.table.atPrompt(game);
    }

    /** Runs {@code op} and then the save the next prompt would make. */
    private static void act(Recorded rec, Game game, CompanionTable.Op op) {
        rec.table.submit(op);
        assertTrue(rec.table.runPending(game));
        rec.table.save(game);
    }

    /** Undoes, as a tap on the menu item does, then saves as the next prompt would. */
    private static void undo(Recorded rec, Game game) {
        rec.table.undo();
        assertTrue(rec.table.runPending(game));
        saveAsTheTableDoes(rec, game);
    }

    private static CompanionLogEntry lastEntry(Recorded rec) {
        List<CompanionLogEntry> log = rec.store.loadLog("t");
        return log.get(log.size() - 1);
    }

    /**
     * Through the real loop: End Turn passes Ann's turn, Bo draws, and his Main 1 is saved. Undo there puts Bo's draw
     * back in his library, and the game, and its view, back on Ann's turn 1, where she is asked again: in Main 2, the
     * last stop End Turn passed, which was saved like any main phase.
     */
    @Test
    public void onTheGameLoopUndoAfterEndTurnPutsTheDrawAndTheActiveSeatBack() {
        Game game = startTableIn(PhaseType.MAIN1);
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        Player ann = ph.getPlayerTurn();
        Player bo = game.getNextPlayerAfter(ann);
        int boHand = bo.getCardsIn(ZoneType.Hand).size();
        int boLibrary = bo.getCardsIn(ZoneType.Library).size();
        Recorded rec = new Recorded();
        List<String> asked = new ArrayList<>();
        List<Integer> boHandInHisTurn = new ArrayList<>();
        boolean[] undone = {false};
        seatsFor(game, rec, asked, seat -> {
            saveAsTheTableDoes(rec, game);
            if (!undone[0] && ph.getPlayerTurn() == ann) {
                return null; // End Turn: Ann passes her main phases
            }
            if (!undone[0]) {
                assertEquals(ph.getPlayerTurn(), bo);
                boHandInHisTurn.add(bo.getCardsIn(ZoneType.Hand).size());
                undone[0] = true;
                rec.table.undo();
                return seat.asTheTableDoes();
            }
            throw new OperatorAsked();
        });

        runLoopUntilTheOperatorIsAsked(game);

        assertEquals(asked, List.of("priority MAIN1", "priority MAIN2", "priority MAIN1", "priority MAIN2"));
        assertEquals(boHandInHisTurn, List.of(boHand + 1), "Bo drew in his turn");
        assertEquals(bo.getCardsIn(ZoneType.Hand).size(), boHand, "the draw is back");
        assertEquals(bo.getCardsIn(ZoneType.Library).size(), boLibrary);
        assertEquals(ph.getPlayerTurn(), ann);
        assertEquals(ph.getTurn(), 1);
        assertEquals(ph.getPhase(), PhaseType.MAIN2);
        assertEquals(game.getView().getTurn(), 1, "the view's turn went back too");
        assertEquals(game.getView().getPlayerTurn(), ann.getView());
        assertEquals(rec.store.latestStep("t"), 1, "Bo's turn is gone from disk, and Ann's prompt saved nothing new");
        CompanionLogEntry undo = lastEntry(rec);
        assertEquals(undo.kind(), CompanionLogEntry.Kind.UNDO);
        assertEquals(undo.step(), 1);
        assertEquals(undo.undid(), List.of(2));
        assertEquals(undo.active(), ann.getName());
    }

    /** Undo goes back one save at a time to the start, then says there's nothing left. */
    @Test
    public void undoRepeatsBackToTheStartThenRefuses() {
        Game game = startTable();
        Player bo = game.getPlayers().get(1);
        Recorded rec = new Recorded();
        rec.table.save(game); // step 0, the start
        act(rec, game, g -> TableEditor.setLife(bo, 30, false));
        act(rec, game, g -> TableEditor.setLife(bo, 20, false));
        assertEquals(rec.store.latestStep("t"), 2);

        undo(rec, game);
        assertEquals(bo.getLife(), 30);
        assertEquals(rec.store.latestStep("t"), 1, "the prompt after the undo saved nothing new");
        undo(rec, game);
        assertEquals(bo.getLife(), CompanionSetup.DEFAULT_LIFE);
        assertEquals(rec.store.latestStep("t"), 0);

        undo(rec, game);
        assertEquals(rec.results.get(rec.results.size() - 1), "Nothing to undo: this is the start of the game");
        assertEquals(lastEntry(rec).kind(), CompanionLogEntry.Kind.NOTICE, "a refusal is logged as a notice");
        assertEquals(bo.getLife(), CompanionSetup.DEFAULT_LIFE);
        assertEquals(rec.store.latestStep("t"), 0);
    }

    /** Before the first save there is nothing to go back to. */
    @Test
    public void undoBeforeAnySaveRefuses() {
        Game game = startTable();
        Recorded rec = new Recorded();
        rec.table.undo();
        rec.table.runPending(game);
        assertEquals(rec.results, List.of("Nothing to undo yet"));
    }

    /**
     * The game menu's Undo is enabled from {@link CompanionTable#canUndo}, as of the latest prompt: off before the
     * first save and at the start of the game, on once there's a step to go back to, and on at step 0 with a spell on
     * the stack, as the cast can be taken back.
     */
    @Test
    public void canUndoFollowsThePromptsSteps() {
        Game game = startTable();
        Player ann = game.getPhaseHandler().getPlayerTurn();
        Player bo = game.getNextPlayerAfter(ann);
        Recorded rec = new Recorded();
        assertFalse(rec.table.canUndo(), "no prompt, no save yet");
        saveAsTheTableDoes(rec, game);
        assertEquals(rec.store.latestStep("t"), 0);
        assertFalse(rec.table.canUndo(), "the start of the game");

        rec.table.submit(new CompanionTable.AddCard(ann.getName(), "Grizzly Bears", true, TableEditor.Mode.CAST));
        rec.table.runPending(game);
        saveAsTheTableDoes(rec, game);
        assertEquals(rec.store.latestStep("t"), 0, "nothing saved with a spell on the stack");
        assertTrue(rec.table.canUndo(), "the cast can be taken back");
        undo(rec, game);
        assertFalse(rec.table.canUndo(), "back at the start");

        rec.table.submit(g -> TableEditor.setLife(bo, 30, false));
        rec.table.runPending(game);
        saveAsTheTableDoes(rec, game);
        assertEquals(rec.store.latestStep("t"), 1);
        assertTrue(rec.table.canUndo());
        undo(rec, game);
        assertFalse(rec.table.canUndo());
        undo(rec, game);
        assertEquals(rec.results.get(rec.results.size() - 1), "Nothing to undo: this is the start of the game",
                "an undo sent anyway is refused");
    }

    /** The undone entries stay in the log, marked by the undo; a new action after it writes the step again, unmarked. */
    @Test
    public void anUndoneEntryStaysMarkedAndTheNextActionWritesItsStepAgain() {
        Game game = startTable();
        Player bo = game.getPlayers().get(1);
        Recorded rec = new Recorded();
        rec.table.save(game);
        act(rec, game, g -> TableEditor.setLife(bo, 30, false));
        long undoneSeq = lastEntry(rec).seq();
        assertEquals(lastEntry(rec).step(), 1);

        undo(rec, game);
        act(rec, game, g -> TableEditor.setLife(bo, 25, false));

        List<CompanionLogEntry> log = rec.store.loadLog("t");
        assertEquals(log.size(), 3, log.toString());
        assertEquals(log.get(1).kind(), CompanionLogEntry.Kind.UNDO);
        CompanionLogEntry redone = log.get(2);
        assertEquals(redone.step(), 1, "step 1 again");
        assertEquals(CompanionLogEntry.undone(log), java.util.Set.of(undoneSeq), "only the undone life edit");
        assertEquals(rec.store.latestStep("t"), 1);
        assertTrue(String.join("\n", rec.store.loadStep("t", 1).orElseThrow()).contains("p1life=25"));
    }

    /**
     * A relaunch after an undo loads the step undo went back to, its first prompt doesn't save that again as a new
     * step, and the next undo carries on from there.
     */
    @Test
    public void aRestartAfterAnUndoLoadsItsStepAndTheNextUndoCarriesOn() {
        Game game = startTable();
        Recorded rec = new Recorded();
        rec.table.save(game);
        act(rec, game, g -> TableEditor.setLife(g.getPlayers().get(1), 30, false));
        act(rec, game, g -> TableEditor.setLife(g.getPlayers().get(1), 20, false));
        undo(rec, game);
        assertEquals(rec.store.latestStep("t"), 1);

        Recorded relaunched = new Recorded(rec);
        Game loaded = initAndCreateThreePlayerGame();
        relaunched.table.startFromLatest(loaded);
        Player bo = loaded.getPlayers().get(1);
        assertEquals(bo.getLife(), 30, "the undone 20 isn't loaded");
        seatsFor(loaded, relaunched, new ArrayList<>(), seat -> {
            saveAsTheTableDoes(relaunched, loaded);
            throw new OperatorAsked();
        });
        runLoopUntilTheOperatorIsAsked(loaded);
        assertEquals(rec.store.latestStep("t"), 1, "the loaded game isn't saved again as a new step");

        undo(relaunched, loaded);
        assertEquals(bo.getLife(), CompanionSetup.DEFAULT_LIFE);
        assertEquals(rec.store.latestStep("t"), 0);
    }

    /**
     * A spell on the stack isn't saved yet, so undo takes back just that cast, to the latest save, not the one before
     * it. The stack is emptied: nothing is left pointing at a card the load removed, and nothing resolves later.
     */
    @Test
    public void undoWithASpellOnTheStackTakesBackOnlyTheCast() {
        Game game = startTable();
        Player ann = game.getPhaseHandler().getPlayerTurn();
        Player bo = game.getNextPlayerAfter(ann);
        Recorded rec = new Recorded();
        rec.table.save(game);
        act(rec, game, g -> TableEditor.setLife(bo, 30, false)); // step 1
        rec.table.submit(new CompanionTable.AddCard(ann.getName(), "Grizzly Bears", true, TableEditor.Mode.CAST));
        rec.table.runPending(game);
        assertFalse(game.getStack().isEmpty(), "on the stack");
        assertEquals(CompanionCards.hiddenInHand(ann), CompanionSetup.DEFAULT_HAND - 1);

        undo(rec, game);

        assertTrue(game.getStack().isEmpty());
        assertTrue(game.getStackZone().getCards().isEmpty(), "no card left in the stack zone");
        assertEquals(bo.getLife(), 30, "step 1 stays");
        assertEquals(CompanionCards.hiddenInHand(ann), CompanionSetup.DEFAULT_HAND, "the card is back in the hand");
        resolveAll(game);
        assertEquals(countCardsWithName(game, "Grizzly Bears", ZoneType.Battlefield), 0, "nothing resolved later");
        assertEquals(rec.store.latestStep("t"), 1);
        assertEquals(lastEntry(rec).undid(), List.of(2), "the cast's entry carries step 2, the save it never got");
    }

    /** Through the real loop: undo at a combat stop ends the combat and goes back to the main phase before it. */
    @Test
    public void onTheGameLoopUndoInACombatEndsItAndGoesBackToMain1() {
        Game game = startTableInTurn2(PhaseType.MAIN1, "p0battlefield=Grizzly Bears");
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        Player active = ph.getPlayerTurn();
        Player defender = game.getNextPlayerAfter(active);
        Recorded rec = new Recorded();
        rec.table.setEngineCombatForTurn(2, true);
        List<String> asked = new ArrayList<>();
        boolean[] undone = {false};
        List<LoopSeat> seats = seatsFor(game, rec, asked, seat -> {
            saveAsTheTableDoes(rec, game);
            if (undone[0]) {
                throw new OperatorAsked();
            }
            if (ph.getPhase() == PhaseType.COMBAT_DECLARE_ATTACKERS) {
                assertTrue(ph.inCombat());
                undone[0] = true;
                rec.table.undo();
                return seat.asTheTableDoes();
            }
            return null; // on to combat
        });
        seats.stream().filter(s -> s.getPlayer() == active).findFirst().orElseThrow().attacker =
                active.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Grizzly Bears"))
                        .findFirst().orElseThrow();

        runLoopUntilTheOperatorIsAsked(game);

        assertEquals(asked, List.of("priority MAIN1", "attackers " + active.getName(),
                "priority COMBAT_DECLARE_ATTACKERS", "priority MAIN1"));
        assertEquals(ph.getPhase(), PhaseType.MAIN1);
        assertFalse(ph.inCombat(), "the combat ended");
        Card bears = active.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().equals("Grizzly Bears")).findFirst().orElseThrow();
        assertFalse(bears.isTapped(), "untapped, as saved");
        assertEquals(defender.getLife(), CompanionSetup.DEFAULT_LIFE);
        assertTrue(rec.table.engineCombat(2), "the same turn: combat for it stays on");
    }

    /**
     * A sorcery parked for Bo's Main 1 after End Turn was submitted after the latest save, so undo drops it and says
     * so. Combat for Bo's turn goes too: the game is back in Ann's.
     */
    @Test
    public void undoDropsAParkedSorceryAndCombatForAnotherTurnWithANotice() {
        Game game = startTableIn(PhaseType.MAIN1);
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        Player ann = ph.getPlayerTurn();
        Player bo = game.getNextPlayerAfter(ann);
        Recorded rec = new Recorded();
        rec.table.save(game); // Ann's Main 1, turn 1
        ph.devModeSet(PhaseType.UPKEEP, bo, 2); // End Turn reached Bo's upkeep
        rec.table.setEngineCombatForTurn(2, true);
        rec.table.submit(cast(bo, "Divination"));
        rec.table.runPending(game);
        assertTrue(rec.table.isParked());

        undo(rec, game);

        assertFalse(rec.table.isParked());
        assertEquals(rec.notices, List.of("Divination waits for Main 1",
                "Undone: Divination and anything after it wasn't added"));
        assertEquals(ph.getPlayerTurn(), ann);
        assertEquals(ph.getTurn(), 1);
        assertEquals(castCount(game, "Divination"), 0);
        assertFalse(rec.table.engineCombat(2), "combat for Bo's turn went with it");
        assertEquals(rec.store.latestStep("t"), 0);
    }

    // ---- "Wrong player" correction (slice 4, AC-22a) ----

    private static List<Card> named(Player player, ZoneType zone, String name) {
        return player.getCardsIn(zone).stream().filter(c -> c.getName().equals(name)).toList();
    }

    /** Adds {@code name} from {@code player}'s hand, cast, and lets it resolve. */
    private static TableEditor.Result castFromHand(Game game, Player player, String name) {
        TableEditor.Result add = TableEditor.addCard(player, name, true, TableEditor.Mode.CAST);
        assertTrue(add.done(), add.message());
        resolveAll(game);
        return add;
    }

    /**
     * AC-22a's example: Ann and Bo at 7, a card logged to Ann (6). Reassigned to Bo, Ann has 7, Bo 6, and the card is
     * on Bo's battlefield, his own.
     */
    @Test
    public void aCorrectionGivesTheCardToTheRightPlayerAndBothHandsComeOutRight() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        TableEditor.Result add = castFromHand(game, ann, "Grizzly Bears");
        assertEquals(CompanionCards.hiddenInHand(ann), 6);
        assertEquals(add.fromHand(), CompanionLogEntry.FromHand.PLACEHOLDER);

        Card bears = TableEditor.findToReassign(ann, "Grizzly Bears");
        TableEditor.Result fix = TableEditor.reassign(bears, bo, add.fromHand());

        assertTrue(fix.done(), fix.message());
        assertEquals(CompanionCards.hiddenInHand(ann), 7, "the unknown card is back");
        assertEquals(CompanionCards.hiddenInHand(bo), 6, "AC-19 for Bo");
        assertEquals(named(bo, ZoneType.Battlefield, "Grizzly Bears"), List.of(bears));
        assertEquals(named(ann, ZoneType.Battlefield, "Grizzly Bears"), List.of());
        assertEquals(bears.getOwner(), bo);
        assertEquals(bears.getController(), bo);
        assertEquals(fix.affected(), List.of(ann.getName(), bo.getName()));
        assertEquals(fix.card(), "Grizzly Bears");
        assertEquals(fix.fromHand(), CompanionLogEntry.FromHand.PLACEHOLDER, "Bo's side");
        assertTrue(bears.isSick(), "it still came in this turn");
    }

    /** Ann's hand was empty (clamped at 0), so she gets nothing back; Bo's is empty too, so he gives up nothing. */
    @Test
    public void aClampedHandGetsNothingBackAndAnEmptyTargetHandGivesNothing() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        for (Player p : List.of(ann, bo)) {
            for (Card c : new ArrayList<>(p.getCardsIn(ZoneType.Hand))) {
                game.getAction().ceaseToExist(c, true);
            }
        }
        TableEditor.Result add = castFromHand(game, ann, "Grizzly Bears");
        assertEquals(add.fromHand(), CompanionLogEntry.FromHand.NONE);

        TableEditor.Result fix = TableEditor.reassign(TableEditor.findToReassign(ann, "Grizzly Bears"), bo,
                add.fromHand());

        assertTrue(fix.done(), fix.message());
        assertEquals(ann.getCardsIn(ZoneType.Hand).size(), 0, "no extra card for Ann");
        assertEquals(bo.getCardsIn(ZoneType.Hand).size(), 0);
        assertEquals(fix.fromHand(), CompanionLogEntry.FromHand.NONE);
        assertEquals(named(bo, ZoneType.Battlefield, "Grizzly Bears").size(), 1);
    }

    /** AC-20 for the target: Bo's known copy leaves his hand, not an unknown card. */
    @Test
    public void theTargetsKnownCopyLeavesTheirHand() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        addCardToZone("Grizzly Bears", bo, ZoneType.Hand);
        castFromHand(game, ann, "Grizzly Bears");
        Card bears = TableEditor.findToReassign(ann, "Grizzly Bears");

        TableEditor.Result fix = TableEditor.reassign(bears, bo, CompanionLogEntry.FromHand.PLACEHOLDER);

        assertTrue(fix.done(), fix.message());
        assertEquals(fix.fromHand(), CompanionLogEntry.FromHand.KNOWN);
        assertEquals(named(bo, ZoneType.Hand, "Grizzly Bears"), List.of());
        assertEquals(CompanionCards.hiddenInHand(bo), 7);
        assertEquals(named(bo, ZoneType.Battlefield, "Grizzly Bears"), List.of(bears));
        assertEquals(countCardsWithName(game, "Grizzly Bears"), 1);
    }

    /**
     * Decision for {@code known}: Ann's known copy left her hand when it was logged; the operator says she never
     * played it, so it goes back to her hand as a known card, and the card on the table is Bo's.
     */
    @Test
    public void aKnownCardGoesBackToTheOriginalHandAsAKnownCard() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        addCardToZone("Sol Ring", ann, ZoneType.Hand);
        TableEditor.Result add = castFromHand(game, ann, "Sol Ring");
        assertEquals(add.fromHand(), CompanionLogEntry.FromHand.KNOWN);
        Card ring = TableEditor.findToReassign(ann, "Sol Ring");

        TableEditor.Result fix = TableEditor.reassign(ring, bo, add.fromHand());

        assertTrue(fix.done(), fix.message());
        List<Card> backInHand = named(ann, ZoneType.Hand, "Sol Ring");
        assertEquals(backInHand.size(), 1, "Ann has her Sol Ring again");
        assertFalse(CompanionCards.isPlaceholder(backInHand.get(0)));
        assertEquals(backInHand.get(0).getOwner(), ann);
        assertEquals(CompanionCards.hiddenInHand(ann), 7);
        assertEquals(CompanionCards.hiddenInHand(bo), 6);
        assertEquals(named(bo, ZoneType.Battlefield, "Sol Ring"), List.of(ring));
    }

    /** Effects that resolved aren't redone: Gray Merchant's drain doesn't run again for Bo, nor undo for Ann. */
    @Test
    public void aCorrectionSetsOffNoTrigger() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        Player cy = game.getPlayers().get(2);
        castFromHand(game, ann, "Gray Merchant of Asphodel");
        assertEquals(ann.getLife(), 44);
        assertEquals(bo.getLife(), 38);

        TableEditor.Result fix = TableEditor.reassign(TableEditor.findToReassign(ann, "Gray Merchant of Asphodel"),
                bo, CompanionLogEntry.FromHand.PLACEHOLDER);

        assertTrue(fix.done(), fix.message());
        assertTrue(game.getStack().isEmpty(), "no trigger went on the stack");
        resolveAll(game);
        assertEquals(ann.getLife(), 44);
        assertEquals(bo.getLife(), 38);
        assertEquals(cy.getLife(), 38);
        assertFalse(game.getTriggerHandler().isTriggerSuppressed(forge.game.trigger.TriggerType.ChangesZone),
                "the suppression is lifted");
    }

    /** The card died since it was logged: it's reassigned where it is now, Bo's graveyard. */
    @Test
    public void aCardThatDiedIsReassignedInTheGraveyard() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        Card bears = putIn(game, ann, "Grizzly Bears");
        game.getAction().moveToGraveyard(bears, null);
        resolveAll(game);
        Card dead = TableEditor.findToReassign(ann, "Grizzly Bears");
        assertTrue(dead.isInZone(ZoneType.Graveyard));

        TableEditor.Result fix = TableEditor.reassign(dead, bo, CompanionLogEntry.FromHand.NONE);

        assertTrue(fix.done(), fix.message());
        assertEquals(named(bo, ZoneType.Graveyard, "Grizzly Bears"), List.of(dead));
        assertEquals(named(ann, ZoneType.Graveyard, "Grizzly Bears"), List.of());
        assertEquals(dead.getOwner(), bo);
    }

    /**
     * The card bounced to Ann's hand since: it goes to Bo's hand, and isn't taken for Bo's known copy (AC-20 is asked
     * of Bo's hand before the card arrives), so he gives up an unknown card and holds the bounced one.
     */
    @Test
    public void aBouncedCardGoesToTheRightHand() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        castFromHand(game, ann, "Grizzly Bears");
        Card bears = TableEditor.findToReassign(ann, "Grizzly Bears");
        game.getAction().moveToHand(bears, null);
        resolveAll(game);
        Card inHand = TableEditor.findToReassign(ann, "Grizzly Bears");

        TableEditor.Result fix = TableEditor.reassign(inHand, bo, CompanionLogEntry.FromHand.PLACEHOLDER);

        assertTrue(fix.done(), fix.message());
        assertEquals(named(bo, ZoneType.Hand, "Grizzly Bears"), List.of(inHand));
        assertEquals(named(ann, ZoneType.Hand, "Grizzly Bears"), List.of());
        assertEquals(CompanionCards.hiddenInHand(ann), 7);
        assertEquals(CompanionCards.hiddenInHand(bo), 6);
        assertEquals(fix.fromHand(), CompanionLogEntry.FromHand.PLACEHOLDER);
    }

    /** Refused, changing nothing: the same player, and a spell still on the stack. */
    @Test
    public void aCorrectionToTheSamePlayerOrOfASpellOnTheStackIsRefused() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        Card bears = putIn(game, ann, "Grizzly Bears");
        TableEditor.Result same = TableEditor.reassign(bears, ann, CompanionLogEntry.FromHand.PLACEHOLDER);
        assertFalse(same.done());
        assertNull(same.fromHand());
        assertEquals(CompanionCards.hiddenInHand(ann), 7);

        assertTrue(TableEditor.addCard(ann, "Divination", true, TableEditor.Mode.CAST).done());
        Card spell = TableEditor.findToReassign(ann, "Divination");
        assertTrue(spell.isInZone(ZoneType.Stack));
        TableEditor.Result onStack = TableEditor.reassign(spell, bo, CompanionLogEntry.FromHand.PLACEHOLDER);
        assertFalse(onStack.done());
        assertEquals(spell.getOwner(), ann);
        assertEquals(CompanionCards.hiddenInHand(ann), 6);
        assertEquals(CompanionCards.hiddenInHand(bo), 7);
    }

    /** Through the table: refusals are notices, and a token that ceased to exist isn't found. */
    @Test
    public void theTableRefusesACardItCantFindOrTheSamePlayer() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        Recorded rec = new Recorded();
        assertTrue(TableEditor.addCard(ann, "Treasure Token", false, TableEditor.Mode.PUT_ONTO_BATTLEFIELD).done());
        Card treasure = ann.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).findFirst().orElseThrow();
        assertEquals(TableEditor.findToReassign(ann, "Treasure Token"), treasure, "found while it exists");
        game.getAction().ceaseToExist(treasure, true);
        putIn(game, ann, "Grizzly Bears");

        rec.table.submit(new CompanionTable.Reassign(ann.getName(), "Treasure Token", bo.getName(),
                CompanionLogEntry.FromHand.NONE));
        rec.table.submit(new CompanionTable.Reassign(ann.getName(), "Grizzly Bears", ann.getName(),
                CompanionLogEntry.FromHand.PLACEHOLDER));
        assertTrue(rec.table.runPending(game));

        List<CompanionLogEntry> log = rec.store.loadLog("t");
        assertEquals(log.size(), 2, log.toString());
        for (CompanionLogEntry entry : log) {
            assertEquals(entry.kind(), CompanionLogEntry.Kind.NOTICE, entry.toString());
        }
        assertEquals(rec.results.get(0), ann.getName() + "'s Treasure Token wasn't found: nothing was changed");
        assertEquals(named(ann, ZoneType.Battlefield, "Grizzly Bears").size(), 1);
        assertEquals(CompanionCards.hiddenInHand(ann), 7);
        assertEquals(CompanionCards.hiddenInHand(bo), 7);
    }

    /**
     * The table logs a correction as {@code correction}, about both seats, with Bo's side of the hand. Undoing it is
     * an ordinary undo: the card is Ann's again (6 and 7), and a second undo takes the add back (7 and 7).
     */
    @Test
    public void aCorrectionIsLoggedAndUndoneLikeAnyStep() {
        Game game = startTable();
        Player ann = game.getPlayers().get(0);
        Player bo = game.getPlayers().get(1);
        Recorded rec = new Recorded();
        rec.table.save(game); // step 0
        rec.table.submit(new CompanionTable.AddCard(ann.getName(), "Grizzly Bears", true, TableEditor.Mode.CAST));
        assertTrue(rec.table.runPending(game));
        resolveAll(game);
        rec.table.save(game); // step 1
        CompanionLogEntry add = lastEntry(rec);

        act(rec, game, new CompanionTable.Reassign(ann.getName(), add.card(), bo.getName(), add.fromHand()));

        CompanionLogEntry fix = lastEntry(rec);
        assertEquals(fix.kind(), CompanionLogEntry.Kind.CORRECTION, fix.toString());
        assertEquals(fix.affected(), List.of(ann.getName(), bo.getName()));
        assertEquals(fix.card(), "Grizzly Bears");
        assertEquals(fix.fromHand(), CompanionLogEntry.FromHand.PLACEHOLDER);
        assertEquals(fix.step(), 2);
        assertEquals(rec.store.latestStep("t"), 2);
        assertEquals(named(bo, ZoneType.Battlefield, "Grizzly Bears").size(), 1);

        undo(rec, game);
        ann = game.getPlayers().get(0);
        bo = game.getPlayers().get(1);
        assertEquals(named(ann, ZoneType.Battlefield, "Grizzly Bears").size(), 1, "Ann's again");
        assertEquals(named(bo, ZoneType.Battlefield, "Grizzly Bears").size(), 0);
        assertEquals(CompanionCards.hiddenInHand(ann), 6);
        assertEquals(CompanionCards.hiddenInHand(bo), 7);
        assertEquals(CompanionLogEntry.undone(rec.store.loadLog("t")), java.util.Set.of(fix.seq()));

        undo(rec, game);
        assertEquals(countCardsWithName(game, "Grizzly Bears"), 0);
        assertEquals(CompanionCards.hiddenInHand(ann), 7);
        assertEquals(CompanionCards.hiddenInHand(bo), 7);
    }

    private static void assertEntry(CompanionLogEntry entry, CompanionLogEntry.Kind kind, String seat, String card,
                                    CompanionLogEntry.FromHand fromHand) {
        assertEquals(entry.kind(), kind, entry.toString());
        assertEquals(entry.affected(), List.of(seat), entry.toString());
        assertEquals(entry.card(), card, entry.toString());
        assertEquals(entry.fromHand(), fromHand, entry.toString());
    }
}
