package forge.gamemodes.companion;

import forge.StaticData;
import forge.card.CardRules;
import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CardUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.item.PaperToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the operator says happened at the table, done to the real game. The engine never refuses the operator: a card
 * is cast without paying its costs, at any time. Its effects then resolve by the rules.
 * <p>
 * Every method runs on the game loop thread, between priority prompts (see {@link CompanionTable}).
 */
public final class TableEditor {
    /** How a card added from a hand or from nowhere gets onto the table. */
    public enum Mode {
        /** Cast it, or play it if it's a land: cast triggers fire, and a spell resolves by its text. */
        CAST,
        /** Put it onto the battlefield: no cast triggers, but it still has its enters effects. */
        PUT_ONTO_BATTLEFIELD
    }

    /** What happened, for the operator and the log. */
    public record Result(boolean done, String message) { }

    private TableEditor() {
    }

    /**
     * Adds the card named {@code name} for {@code player}. With {@code fromHand}, it leaves that hand: the player's
     * known copy if they have one, otherwise one of the unknown cards. A name that is neither a card nor a token is
     * added as an emblem, as Forge has no list of emblems.
     */
    public static Result addCard(Player player, String name, boolean fromHand, Mode mode) {
        Game game = player.getGame();
        PaperCard printing = StaticData.instance().getCommonCards().getUniqueByName(name);
        if (printing == null) {
            PaperToken token = findToken(name);
            if (token == null) {
                addEmblem(player, name);
                return new Result(true, player.getName() + " gets the emblem " + name);
            }
            Card made = CardFactory.getCard(token, player, game);
            made.setGameTimestamp(game.getNextTimestamp());
            game.getAction().moveTo(ZoneType.Battlefield, made, null, AbilityKey.newMap());
            settle(game);
            return new Result(true, player.getName() + " makes " + name);
        }

        Card known = fromHand ? findKnownInHand(player, name) : null;
        Card hidden = fromHand && known == null ? firstPlaceholderInHand(player) : null;
        Card card = known;
        if (card == null) {
            if (hidden != null) {
                game.getAction().ceaseToExist(hidden, true);
            }
            card = Card.fromPaperCard(printing, player);
            card.setGameTimestamp(game.getNextTimestamp());
            CardUtil.turnToRightFace(name, card);
            // in hand first, as the rules expect of anything cast, and for zone-change triggers to see where it was
            game.getAction().moveToHand(card, null);
        }

        boolean done;
        if (mode == Mode.PUT_ONTO_BATTLEFIELD || card.isLand()) {
            done = card.isPermanent();
            if (done) {
                game.getAction().moveToPlay(card, player, null, AbilityKey.newMap());
            }
        } else {
            done = cast(player, card);
        }
        if (!done) {
            undoAdd(player, card, known, hidden);
            settle(game);
            return new Result(false, name + " wasn't added: "
                    + (mode == Mode.CAST ? "it couldn't be cast, or the cast was cancelled." : "it isn't a permanent."));
        }
        settle(game);
        return new Result(true, player.getName() + (mode == Mode.CAST ? " casts " : " puts in ") + name);
    }

    /** The trigger modes a life change sets off. */
    private static final TriggerType[] LIFE_TRIGGERS = {
            TriggerType.LifeGained, TriggerType.LifeLost, TriggerType.LifeLostAll
    };

    /**
     * Sets {@code player}'s life to {@code life}, as the operator says it is (AC-25). With {@code fireTriggers}, it's
     * a real gain or loss: whatever triggers on gaining or losing life fires, for example Ajani's Pridemate. Without,
     * it's a correction: none of them fire (decision D2), and it doesn't count as life gained or lost this turn.
     * Replacement and static effects on the table apply either
     * way, for example "can't gain life", and when they change or stop it, the result says so instead of forcing the
     * value. Life of 0 or below is allowed: the table effect keeps the player in the game.
     *
     * @see #lifeChangeCouldTrigger(Player, int)
     */
    public static Result setLife(Player player, int life, boolean fireTriggers) {
        Game game = player.getGame();
        if (fireTriggers) {
            int before = player.getLife();
            player.setLife(life, null);
            // Player.setLife fires LifeLost but not LifeLostAll ("whenever one or more players lose life"); an effect
            // that sets life fires it after, as LifeSetEffect does
            if (player.getLife() < before) {
                Map<Player, Integer> lost = new HashMap<>();
                lost.put(player, before - player.getLife());
                game.getTriggerHandler().runTrigger(TriggerType.LifeLostAll, AbilityKey.mapFromPIMap(lost), false);
            }
        } else {
            TriggerHandler triggers = game.getTriggerHandler();
            boolean[] wasSuppressed = new boolean[LIFE_TRIGGERS.length];
            for (int i = 0; i < LIFE_TRIGGERS.length; i++) {
                wasSuppressed[i] = triggers.isTriggerSuppressed(LIFE_TRIGGERS[i]);
                if (!wasSuppressed[i]) {
                    triggers.suppressMode(LIFE_TRIGGERS[i]);
                }
            }
            // a correction isn't life gained or lost this turn either: spectacle, "the first time you gain life each
            // turn" and the like see the turn's tallies as they were
            int gained = player.getLifeGainedThisTurn();
            int gainedTimes = player.getLifeGainedTimesThisTurn();
            int lost = player.getLifeLostThisTurn();
            Map<Player, Integer> gainedByTeam = new HashMap<>();
            for (Player p : game.getPlayers()) {
                gainedByTeam.put(p, p.getLifeGainedByTeamThisTurn());
            }
            try {
                player.setLife(life, null);
            } finally {
                player.setLifeGainedThisTurn(gained);
                player.setLifeGainedTimesThisTurn(gainedTimes);
                player.setLifeLostThisTurn(lost);
                for (Map.Entry<Player, Integer> e : gainedByTeam.entrySet()) {
                    Player p = e.getKey();
                    p.addLifeGainedByTeamThisTurn(e.getValue() - p.getLifeGainedByTeamThisTurn());
                }
                // leave alone a suppression someone else set
                for (int i = 0; i < LIFE_TRIGGERS.length; i++) {
                    if (!wasSuppressed[i]) {
                        triggers.clearSuppression(LIFE_TRIGGERS[i]);
                    }
                }
            }
        }
        settle(game);
        if (player.getLife() != life) {
            return new Result(false, player.getName() + "'s life is " + player.getLife() + ", not " + life
                    + ": something on the table changes life gain or loss");
        }
        return new Result(true, player.getName() + "'s life is set to " + life);
    }

    /**
     * Whether setting {@code player}'s life to {@code newLife} as a real gain or loss would set off a trigger on the
     * table, so the operator is asked whether it should. It asks the engine's trigger handler which of its active
     * triggers would run for the gain or loss that {@code Player.gainLife} or {@code loseLife} would report: the same
     * check the engine makes, so "whenever you gain life" only counts for its controller, a once-a-turn trigger that
     * already fired doesn't count, and Torpor Orb-like effects are respected. It's false when the player can't gain
     * or lose life at all.
     * <p>
     * Approximations: the amount is the one asked for, before any replacement effect changes it, and delayed triggers
     * ("whenever you gain life this turn" from a resolved spell) aren't looked at. Triggers are the ones the engine has
     * registered as active, which it refreshes when it checks state-based actions, as the table does between prompts.
     */
    public static boolean lifeChangeCouldTrigger(Player player, int newLife) {
        int current = player.getLife();
        TriggerHandler triggers = player.getGame().getTriggerHandler();
        if (newLife > current) {
            if (!player.canGainLife() || triggers.isTriggerSuppressed(TriggerType.LifeGained)) {
                return false;
            }
            Map<AbilityKey, Object> gained = AbilityKey.mapFromPlayer(player);
            gained.put(AbilityKey.LifeAmount, newLife - current);
            gained.put(AbilityKey.Source, null);
            gained.put(AbilityKey.SourceSA, null);
            gained.put(AbilityKey.FirstTime, player.getLifeGainedTimesThisTurn() == 0);
            return !triggers.getActiveTrigger(TriggerType.LifeGained, gained).isEmpty();
        }
        if (newLife < current) {
            if (!player.canLoseLife()) {
                return false;
            }
            Map<AbilityKey, Object> lost = AbilityKey.mapFromPlayer(player);
            lost.put(AbilityKey.LifeAmount, current - newLife);
            lost.put(AbilityKey.FirstTime, player.getLifeLostThisTurn() == 0);
            lost.put(AbilityKey.SpellAbility, null);
            if (!triggers.isTriggerSuppressed(TriggerType.LifeLost)
                    && !triggers.getActiveTrigger(TriggerType.LifeLost, lost).isEmpty()) {
                return true;
            }
            Map<Player, Integer> lostAll = new HashMap<>();
            lostAll.put(player, current - newLife);
            return !triggers.isTriggerSuppressed(TriggerType.LifeLostAll)
                    && !triggers.getActiveTrigger(TriggerType.LifeLostAll, AbilityKey.mapFromPIMap(lostAll)).isEmpty();
        }
        return false;
    }

    /**
     * Clears summoning sickness on the creatures the operator says were on the table before this turn began, so they
     * can attack and use {@code {T}} abilities this turn (decision D3). The engine makes everything that enters sick and
     * clears it at its controller's next turn; only the operator knows a creature logged late wasn't cast this turn.
     * It's the same engine call {@code GameState} makes for {@code removesummoningsickness}, so nothing about the rules
     * changes, and a ready creature stays ready through a save and a resume ({@code GameState} writes
     * {@code |SummonSick} only for sick cards).
     * <p>
     * Ids that aren't a creature on the battlefield are skipped and named in the result, not an error: the card may
     * have died or left between the pick and the op.
     */
    public static Result readyCreatures(Game game, Collection<Integer> cardIds) {
        List<String> readied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int id : cardIds) {
            Card card = game.findById(id);
            if (card == null || !card.isInPlay() || !card.isCreature()) {
                skipped.add(card == null ? "#" + id : card.getName());
                continue;
            }
            card.setSickness(false);
            readied.add(card.getName());
        }
        String skippedNote = skipped.isEmpty() ? ""
                : " (skipped, not a creature on the battlefield: " + String.join(", ", skipped) + ")";
        if (readied.isEmpty()) {
            return new Result(false, "Nothing to ready" + skippedNote);
        }
        return new Result(true, "Ready to attack: " + String.join(", ", readied) + skippedNote);
    }

    /** {@code player}'s creatures that can't attack this turn because of summoning sickness, for the operator to pick. */
    public static List<Card> sickCreatures(Player player) {
        List<Card> sick = new ArrayList<>();
        for (Card card : player.getCreaturesInPlay()) {
            if (card.isSick()) {
                sick.add(card);
            }
        }
        return sick;
    }

    /**
     * Whether adding {@code printing} this way has to wait for a main phase: a sorcery, cast (AC-42). Permanent spells
     * are sorcery-speed too, but the owner asked for sorceries only; widen it here if that changes (D5).
     */
    static boolean waitsForMainPhase(PaperCard printing, Mode mode) {
        return mode == Mode.CAST && printing.getRules().getType().isSorcery();
    }

    /**
     * The main phase the game has to move on to before {@code player} casts {@code name} (AC-42, D5), or {@code null}
     * to add it now. Only the active player's sorcery waits, and only outside a main phase with an empty stack. The
     * target comes from the position in the turn, not from {@code PhaseHandler.getNumCombat()}, which a saved game
     * doesn't keep: before combat it's Main 1, from combat on it's Main 2. In the end step or cleanup the main phases
     * are over, and the game never goes back, so it's {@code null} there too.
     */
    public static PhaseType mainPhaseFor(Game game, Player player, String name, Mode mode) {
        PaperCard printing = StaticData.instance().getCommonCards().getUniqueByName(name);
        if (printing == null || !waitsForMainPhase(printing, mode)) {
            return null;
        }
        PhaseHandler ph = game.getPhaseHandler();
        if (!player.equals(ph.getPlayerTurn())) {
            return null;
        }
        PhaseType phase = ph.getPhase();
        if (phase == null) {
            return null;
        }
        if (phase.isMain() && game.getStack().isEmpty()) {
            return null;
        }
        if (phase == PhaseType.END_OF_TURN || phase == PhaseType.CLEANUP) {
            return null;
        }
        return phase.isBefore(PhaseType.COMBAT_BEGIN) ? PhaseType.MAIN1 : PhaseType.MAIN2;
    }

    private static boolean cast(Player player, Card card) {
        // as a "cast it without paying" effect sees them: at any time, on anyone's turn, every face and half
        List<SpellAbility> spells = AbilityUtils.getBasicSpellsFromPlayEffect(card, player);
        if (spells.isEmpty()) {
            return false;
        }
        SpellAbility chosen = spells.size() == 1 ? spells.get(0)
                : player.getController().getAbilityToPlay(card, spells);
        if (chosen == null) {
            return false;
        }
        chosen.setActivatingPlayer(player);
        card.setSplitStateToPlayAbility(chosen);
        // free, so no mana is asked for; targets and choices are, from whoever makes them at the table
        return new PlaySpellAbility(player.getController(), chosen).playAbility(true, true, false);
    }

    /** Puts things back as they were before {@link #addCard}, when the card couldn't go where it was meant to. */
    private static void undoAdd(Player player, Card card, Card known, Card hidden) {
        if (known != null) {
            return; // it never left the hand
        }
        Game game = player.getGame();
        game.getAction().ceaseToExist(card, true);
        if (hidden != null) {
            player.getZone(ZoneType.Hand).add(CompanionCards.newPlaceholder(player));
        }
    }

    private static void addEmblem(Player player, String name) {
        Game game = player.getGame();
        Card emblem = new Card(game.nextCardId(), game);
        emblem.setOwner(player);
        emblem.setName(name);
        emblem.setGamePieceType(GamePieceType.EFFECT);
        emblem.setEmblem(true);
        emblem.setGameTimestamp(game.getNextTimestamp());
        player.getZone(ZoneType.Command).add(emblem);
    }

    /** The token whose name is {@code name}. The token database is keyed by script name, not by the name shown. */
    private static PaperToken findToken(String name) {
        for (Map.Entry<String, CardRules> entry : StaticData.instance().getAllTokens().getRules().entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(name)) {
                return StaticData.instance().getAllTokens().getToken(entry.getKey());
            }
        }
        return null;
    }

    private static Card findKnownInHand(Player player, String name) {
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (!CompanionCards.isPlaceholder(card) && card.getName().equals(name)) {
                return card;
            }
        }
        return null;
    }

    private static Card firstPlaceholderInHand(Player player) {
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (CompanionCards.isPlaceholder(card)) {
                return card;
            }
        }
        return null;
    }

    /** Lets triggers the change set off go onto the stack, where the table resolves them. */
    private static void settle(Game game) {
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
    }
}
