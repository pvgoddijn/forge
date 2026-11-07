package forge.gamemodes.companion;

import forge.StaticData;
import forge.card.CardRules;
import forge.card.CardSplitType;
import forge.card.GamePieceType;
import forge.card.ICardFace;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CardUtil;
import forge.game.card.CardZoneTable;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.item.PaperToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * What happened, for the operator and the log.
     *
     * @param done     whether the action was made
     * @param message  the readable line
     * @param affected the seats it was about, by setup name, maybe none
     * @param card     the card it was about, by name, or null
     * @param fromHand for an added card, what left the hand (AC-19/AC-20, for AC-22a); null when not an add, or when
     *                 the add wasn't made (nothing left the hand then, but it isn't an add either)
     */
    public record Result(boolean done, String message, List<String> affected, String card,
                         CompanionLogEntry.FromHand fromHand) {
        public Result {
            affected = affected == null ? List.of() : List.copyOf(affected);
        }

        /** About nobody in particular. */
        public Result(boolean done, String message) {
            this(done, message, List.of(), null, null);
        }

        /** About {@code seat}, and no card. */
        static Result about(String seat, boolean done, String message) {
            return new Result(done, message, List.of(seat), null, null);
        }

        /** An add of {@code card} for {@code seat}. */
        static Result add(String seat, String card, CompanionLogEntry.FromHand fromHand, boolean done,
                          String message) {
            return new Result(done, message, List.of(seat), card, fromHand);
        }
    }

    private TableEditor() {
    }

    /**
     * Adds the card named {@code name} for {@code player}. With {@code fromHand}, it leaves that hand: the player's
     * known copy if they have one, otherwise one of the unknown cards. A name that is neither a card nor a token is
     * added as an emblem, as Forge has no list of emblems.
     * <p>
     * When {@code player}'s Command zone holds their commander of that name, it's that card, from there, whatever
     * {@code fromHand} says, and the hand isn't touched. The cast is free, as every add is, but the engine still counts
     * it as a cast from the Command zone, so the next one's tax goes up as the rules say.
     */
    public static Result addCard(Player player, String name, boolean fromHand, Mode mode) {
        Card commander = findCommanderInCommandZone(player, name);
        if (commander != null) {
            return addCommander(player, commander, mode);
        }
        Game game = player.getGame();
        String seat = player.getName();
        PaperCard printing = StaticData.instance().getCommonCards().getUniqueByName(name);
        if (printing == null) {
            PaperToken token = findToken(name);
            if (token == null) {
                addEmblem(player, name);
                // tokens and emblems never come from a hand
                return Result.add(seat, name, CompanionLogEntry.FromHand.NONE, true,
                        player.getName() + " gets the emblem " + name);
            }
            Card made = CardFactory.getCard(token, player, game);
            made.setGameTimestamp(game.getNextTimestamp());
            game.getAction().moveTo(ZoneType.Battlefield, made, null, AbilityKey.newMap());
            settle(game);
            return Result.add(seat, name, CompanionLogEntry.FromHand.NONE, true, player.getName() + " makes " + name);
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
            return Result.add(seat, name, null, false, name + " wasn't added: "
                    + (mode == Mode.CAST ? "it couldn't be cast, or the cast was cancelled." : "it isn't a permanent."));
        }
        settle(game);
        // AC-19/AC-20: the known copy, else an unknown card, else nothing (the hand was empty); none when not from it
        CompanionLogEntry.FromHand left = fromHand ? handSide(known, hidden) : CompanionLogEntry.FromHand.NONE;
        return Result.add(seat, name, left, true,
                player.getName() + (mode == Mode.CAST ? " casts " : " puts in ") + name);
    }

    /** Where {@link #findToReassign} looks, in order: the stack first, so a spell still on it is refused, not missed. */
    private static final List<ZoneType> REASSIGN_ZONES = List.of(ZoneType.Stack, ZoneType.Battlefield,
            ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Hand, ZoneType.Library);

    /** The trigger modes moving a card to another player could set off; {@link #reassign} sets off none. */
    private static final TriggerType[] REASSIGN_TRIGGERS = {
            TriggerType.ChangesZone, TriggerType.ChangesZoneAll, TriggerType.ChangesController
    };

    /**
     * The card named {@code name} that {@code owner} owns, wherever it is now, for {@link #reassign}: the log names
     * cards by name, and the card may have moved since it was logged (it died, it bounced). The first zone that has
     * one wins, in the order of {@link #REASSIGN_ZONES}, and in that zone the one that got there last. Unknown cards
     * never match. Null when there's none: for example a token that ceased to exist.
     */
    public static Card findToReassign(Player owner, String name) {
        Game game = owner.getGame();
        for (ZoneType zone : REASSIGN_ZONES) {
            Card found = null;
            for (Card card : game.getCardsIn(zone)) {
                if (card.getOwner() == owner && !CompanionCards.isPlaceholder(card) && isNamed(card, name)) {
                    found = card;
                }
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean isNamed(Card card, String name) {
        return name.equals(card.getName())
                || (card.getPaperCard() != null && name.equals(card.getPaperCard().getName()));
    }

    /**
     * The "wrong player" correction (AC-22a): {@code card} goes to the same zone of {@code to}, and {@code to} owns and
     * controls it. Its owner, the player it was logged for, gets back what left their hand ({@code fromHand}, from the
     * add's log entry): an unknown card for {@code placeholder}; the known copy, as a known card, for {@code known}
     * (the operator says they never played it, so it's still in their hand); nothing for {@code none} (not from the
     * hand, or their hand was empty then; null reads as none). Then, only when it was from the hand
     * ({@link CompanionLogEntry.FromHand#isFromHand}; null reads as not), AC-19/AC-20 for {@code to}: their known copy
     * of that name leaves their hand if they have one, otherwise one unknown card, otherwise nothing. A card that
     * wasn't from the hand (a token, from elsewhere) leaves {@code to}'s hand alone.
     * <p>
     * Nothing triggers: it's a correction of the log, not something that happened at the table. The card isn't moved
     * through the game's zone changes; it's taken out of one zone and put into the other as a game load does, with
     * zone-change and control-change triggers suppressed for the move as well. Effects that already resolved stay
     * (an ETB isn't redone or taken back); to replay them for the right player the operator undoes and logs again.
     * It keeps its tapped state and summoning sickness.
     * <p>
     * Refused, changing nothing, when {@code to} already owns it, when it's on the stack (correct it once it has
     * resolved, or undo the cast), and when it's in no zone of the game.
     *
     * @return about both seats, the original player first, with {@code to}'s side of the hand in {@code fromHand}
     */
    public static Result reassign(Card card, Player to, CompanionLogEntry.FromHand fromHand) {
        Game game = to.getGame();
        Player from = card.getOwner();
        String name = card.getName();
        List<String> seats = List.of(from.getName(), to.getName());
        if (from == to) {
            return new Result(false, name + " is already " + to.getName() + "'s: nothing was changed", seats, name,
                    null);
        }
        Zone zone = game.getZoneOf(card);
        if (zone == null || !zone.contains(card) || zone.is(ZoneType.None)) {
            return new Result(false, name + " isn't in the game any more: nothing was changed", seats, name, null);
        }
        if (zone.is(ZoneType.Stack)) {
            return new Result(false, name + " is still on the stack: correct it once it has resolved, or undo the cast",
                    seats, name, null);
        }

        TriggerHandler triggers = game.getTriggerHandler();
        boolean[] wasSuppressed = new boolean[REASSIGN_TRIGGERS.length];
        for (int i = 0; i < REASSIGN_TRIGGERS.length; i++) {
            wasSuppressed[i] = triggers.isTriggerSuppressed(REASSIGN_TRIGGERS[i]);
            if (!wasSuppressed[i]) {
                triggers.suppressMode(REASSIGN_TRIGGERS[i]);
            }
        }
        CompanionLogEntry.FromHand toSide;
        try {
            if (fromHand != null && fromHand.isFromHand()) {
                // to's hand first, so the card itself, if it's in a hand, isn't taken for their known copy
                Card known = findKnownInHand(to, name);
                Card hidden = known == null ? firstPlaceholderInHand(to) : null;
                if (known != null) {
                    game.getAction().ceaseToExist(known, true);
                } else if (hidden != null) {
                    game.getAction().ceaseToExist(hidden, true);
                }
                toSide = handSide(known, hidden);
            } else {
                toSide = CompanionLogEntry.FromHand.NONE;
            }

            if (fromHand == CompanionLogEntry.FromHand.PLACEHOLDER) {
                from.getZone(ZoneType.Hand).add(CompanionCards.newPlaceholder(from));
            } else if (fromHand == CompanionLogEntry.FromHand.KNOWN) {
                from.getZone(ZoneType.Hand).add(knownCopy(card, from));
            }

            if (zone.is(ZoneType.Battlefield) && game.getCombat() != null) {
                game.getCombat().removeFromCombat(card);
            }
            zone.remove(card);
            card.setOwner(to);
            card.clearControllers();
            // as a load or rollback puts it there: no sickness, tapping, "descended" or entered-this-turn bookkeeping
            to.getZone(zone.getZoneType()).add(card, null, null, true);
        } finally {
            for (int i = 0; i < REASSIGN_TRIGGERS.length; i++) {
                if (!wasSuppressed[i]) {
                    triggers.clearSuppression(REASSIGN_TRIGGERS[i]);
                }
            }
        }
        settle(game);
        return new Result(true, name + " is " + to.getName() + "'s, not " + from.getName() + "'s", seats, name, toSide);
    }

    /** The zones {@link #moveTo} moves a card to. */
    public static final List<ZoneType> MOVE_ZONES = List.of(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard,
            ZoneType.Exile, ZoneType.Library, ZoneType.Command);

    /**
     * The operator's "Move…" (AC-22): {@code card} goes to {@code player}'s {@code zone} through the game's own zone
     * change, so it's something that happened at the table: zone-change triggers fire ("when this dies"), replacement
     * effects apply, and a commander's owner may be asked whether it goes to the Command zone instead.
     * <ul>
     * <li>To a Battlefield, {@code player} controls it. From another battlefield that's a control change
     * ({@code setController}, then the engine's {@code controllerChangeZoneCorrection}), so it's summoning sick for
     * them and control-change triggers fire; from anywhere else it's put onto that battlefield under their control,
     * and when the engine refuses that (an instant or sorcery, rule 304.4) its controller stays who it was. Either way
     * its owner stays the same.</li>
     * <li>To any other zone, {@code player} owns it from then on, the way {@link #reassign} does: the card is theirs in
     * that zone, and through a save and a resume. While it leaves a battlefield, its controller stays who it was, so a
     * "when this dies" trigger is still theirs.</li>
     * <li>A commander ({@link Card#isCommander}, a merged or melded one too) is never given another owner: the engine's
     * commander rules ({@code IsCommander+YouOwn}) would make it the other player's commander. Moved to another
     * player's zone other than a battlefield, it goes to its owner's zone of that kind instead, and the message says
     * so. That's done rather than refused, as the operator is never refused what the table did (AC-39): the card is
     * where the table put it as far as the engine's rules allow.</li>
     * <li>A token moved off the battlefield to another zone ceases to exist at the next state-based check (rule
     * 111.7); the message says so.</li>
     * <li>The Library is its top. The Command zone takes any card, though it's meant for a commander.</li>
     * </ul>
     * A hand needs nothing more (AC-20, AC-21): only real cards are moved, so a card moved into a hand is a known card
     * there, and one moved out of it leaves the hidden count as it was.
     * <p>
     * Refused, changing nothing: a zone not in {@link #MOVE_ZONES}; an unknown card (a placeholder has no name yet);
     * an emblem or effect; a card on the stack (let it resolve, or undo the cast: moving a spell by hand would leave
     * its ability on the stack); a card in no zone; and a move to where it already is.
     *
     * @return about the seats it concerns: for a move to a battlefield the seat it was with, for any other its owner
     *         before the move; then {@code player} when that's someone else. When the move gave the card to
     *         {@code player} (a new owner), {@code fromHand} is {@link CompanionLogEntry.FromHand#NONE}: nothing left
     *         their hand for it, and the "wrong player" correction follows the card to them
     *         ({@link CompanionLogEntry#addedInTurn}). Null when the owner stayed the same.
     */
    public static Result moveTo(Card card, Player player, ZoneType zone) {
        Game game = player.getGame();
        String name = card.getName();
        Zone from = game.getZoneOf(card);
        Player owner = card.getOwner();
        Player fromSeat = zone != ZoneType.Battlefield ? owner
                : from != null && from.getPlayer() != null ? from.getPlayer() : owner;
        List<String> seats = fromSeat == player ? List.of(player.getName())
                : List.of(fromSeat.getName(), player.getName());
        String to = where(player, zone);
        if (!MOVE_ZONES.contains(zone)) {
            return new Result(false, name + " can't be moved to " + to, seats, name, null);
        }
        if (CompanionCards.isPlaceholder(card)) {
            return new Result(false, "An unknown card can't be moved: nothing was changed", seats, null, null);
        }
        if (card.isImmutable()) {
            return new Result(false, name + " is an emblem or effect: it can't be moved", seats, name, null);
        }
        if (from == null || !from.contains(card) || from.is(ZoneType.None)) {
            return new Result(false, name + " isn't in the game any more: nothing was changed", seats, name, null);
        }
        if (from.is(ZoneType.Stack)) {
            return new Result(false, name + " is still on the stack: move it once it has resolved, or undo the cast",
                    seats, name, null);
        }
        // a commander stays its owner's: it goes to their zone, not another player's (the commander rules key on owner)
        Player dest = zone != ZoneType.Battlefield && card.isCommander() ? owner : player;
        String toDest = where(dest, zone);
        String redirect = dest == player ? "" : " (a commander stays its owner's, so not " + to + ")";
        if (from.is(zone) && from.getPlayer() == dest) {
            return new Result(false, name + " is already in " + toDest + ": nothing was changed" + redirect, seats,
                    name, null);
        }

        String was = where(from.getPlayer() != null ? from.getPlayer() : owner, from.getZoneType());
        boolean tokenLeaves = card.isToken() && from.is(ZoneType.Battlefield) && zone != ZoneType.Battlefield;
        // as an effect moves a card: the table remembers the battlefield before the move, so a trigger that looks back
        // in time ("when this dies") sees the card that left
        CardZoneTable moves = new CardZoneTable(game.copyLastStateBattlefield(), game.copyLastStateGraveyard());
        Map<AbilityKey, Object> params = AbilityKey.newMap();
        AbilityKey.addCardZoneTableParams(params, moves);
        Card moved;
        if (zone == ZoneType.Battlefield) {
            if (from.is(ZoneType.Battlefield)) {
                card.setController(player, game.getNextTimestamp());
                game.getAction().controllerChangeZoneCorrection(card);
                moved = card;
            } else {
                // the engine puts it onto the battlefield of its controller; when it refuses (an instant or a
                // sorcery, rule 304.4) the card stays where it was, and under who it was
                Player controller = card.getController();
                card.setController(player, game.getNextTimestamp());
                moved = game.getAction().moveToPlay(card, player, null, params);
                if (from.contains(card)) {
                    if (controller == owner) {
                        card.clearControllers();
                    } else {
                        card.setController(controller, game.getNextTimestamp());
                    }
                }
            }
        } else {
            if (owner != dest) {
                if (card.isInPlay()) {
                    // keeps who controls it now for its last known information, which its owner would otherwise be
                    card.setController(card.getController(), game.getNextTimestamp());
                }
                card.setOwner(dest); // the engine sends a card to its owner's zone
            }
            moved = game.getAction().moveTo(zone, card, 0, null, params);
            if (from.contains(card)) {
                card.setOwner(owner); // it didn't move (something stopped it): it isn't theirs either
            }
        }
        moves.triggerChangesZoneAll(game, null);
        settle(game);

        Zone now = moved == null ? null : game.getZoneOf(moved);
        if (now == null || from.contains(moved)) {
            return new Result(false, name + " couldn't be moved to " + to + ": something on the table stopped it",
                    seats, name, null);
        }
        // the card is player's now when it wasn't before: the "wrong player" correction follows it there
        CompanionLogEntry.FromHand newOwner = owner != player && moved.getOwner() == player
                ? CompanionLogEntry.FromHand.NONE : null;
        if (!now.is(zone) || now.getPlayer() != dest) {
            Player nowSeat = now.getPlayer() != null ? now.getPlayer() : moved.getOwner();
            return new Result(true, name + " was moved from " + was + ", but went to " + where(nowSeat,
                    now.getZoneType()) + " instead of " + toDest + redirect, seats, name, newOwner);
        }
        String ceases = tokenLeaves ? ", where it ceases to exist: it's a token" : "";
        return new Result(true, name + " was moved from " + was + " to " + toDest + ceases + redirect, seats, name,
                newOwner);
    }

    /** A zone for the operator to read: "Bo's Battlefield", "the top of Bo's Library". */
    private static String where(Player player, ZoneType zone) {
        String seat = player.getName() + "'s " + zone.name();
        return zone == ZoneType.Library ? "the top of " + seat : seat;
    }

    /** What left a hand for a card from it (AC-19/AC-20): the known copy, else an unknown card, else nothing. */
    private static CompanionLogEntry.FromHand handSide(Card known, Card hidden) {
        return known != null ? CompanionLogEntry.FromHand.KNOWN
                : hidden != null ? CompanionLogEntry.FromHand.PLACEHOLDER : CompanionLogEntry.FromHand.EMPTY;
    }

    /** A fresh known copy of {@code card}'s printing for {@code player}'s hand, front face up as in any hand. */
    private static Card knownCopy(Card card, Player player) {
        Card copy = Card.fromPaperCard(card.getPaperCard(), player);
        copy.setGameTimestamp(player.getGame().getNextTimestamp());
        return copy;
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
            return Result.about(player.getName(), false, player.getName() + "'s life is " + player.getLife() + ", not "
                    + life + ": something on the table changes life gain or loss");
        }
        return Result.about(player.getName(), true, player.getName() + "'s life is set to " + life);
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
        Set<String> controllers = new LinkedHashSet<>();
        for (int id : cardIds) {
            Card card = game.findById(id);
            if (card == null || !card.isInPlay() || !card.isCreature()) {
                skipped.add(card == null ? "#" + id : card.getName());
                continue;
            }
            card.setSickness(false);
            readied.add(card.getName());
            controllers.add(card.getController().getName());
        }
        String skippedNote = skipped.isEmpty() ? ""
                : " (skipped, not a creature on the battlefield: " + String.join(", ", skipped) + ")";
        if (readied.isEmpty()) {
            return new Result(false, "Nothing to ready" + skippedNote);
        }
        return new Result(true, "Ready to attack: " + String.join(", ", readied) + skippedNote,
                List.copyOf(controllers), null, null);
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
     * A seat's mana sources on the battlefield (item 11): one entry per card with a mana ability, holding the
     * {@link ManaAtom} colors it can make ({@link ManaAtom#COLORLESS} for {C}), or 0 when it makes only mana the
     * engine can't name a color for. Any source pays generic mana.
     */
    public record ManaSources(String seat, List<Integer> sources) {
        public ManaSources {
            sources = List.copyOf(sources);
        }
    }

    /** Every seat's {@link #manaSources(Player)}, in the game's order. */
    public static List<ManaSources> manaSources(Game game) {
        List<ManaSources> all = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            all.add(manaSources(player));
        }
        return List.copyOf(all);
    }

    /**
     * What {@code player}'s battlefield can make, by the engine's own check ({@link Card#getProducibleColors}, which
     * walks the card's mana abilities, reflected ones too). Tapped or not, and whatever the ability costs: at the
     * table casts are free, so no land is ever tapped for one, and this asks which colors a seat has, not whether
     * they're free right now.
     */
    public static ManaSources manaSources(Player player) {
        List<Integer> sources = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.getManaAbilities().isEmpty()) {
                continue;
            }
            int colors = 0;
            for (String color : card.getProducibleColors()) {
                colors |= ManaAtom.fromName(color);
            }
            sources.add(colors);
        }
        return new ManaSources(player.getName(), sources);
    }

    /**
     * The players who could have cast {@code name} from their mana sources (item 11), when it's an instant or has
     * flash on a face that can be cast; for any other card, or a name that isn't a card, every player, as only
     * instant-speed spells are narrowed down. The list can be empty: then nobody could have, and the operator is
     * offered every seat anyway (AC-39). Game loop thread. See {@link #couldPay} for what it approximates.
     */
    public static List<Player> couldHaveCast(Game game, String name) {
        List<String> seats = couldHaveCast(manaSources(game), name);
        List<Player> players = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            if (seats.contains(player.getName())) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * {@link #couldHaveCast(Game, String)} from sources already gathered, so the UI can ask with what the latest
     * prompt published ({@link CompanionTable#couldHaveCast}). Touches no game: any thread.
     */
    public static List<String> couldHaveCast(List<ManaSources> seats, String name) {
        List<ManaCost> costs = instantSpeedCosts(name);
        List<String> could = new ArrayList<>();
        for (ManaSources seat : seats) {
            if (costs == null || costs.stream().anyMatch(cost -> couldPay(cost, seat.sources()))) {
                could.add(seat.seat());
            }
        }
        return could;
    }

    /** Whether {@code name} is a card with a face that can be cast at instant speed: an instant, or with flash. */
    public static boolean castsAtInstantSpeed(String name) {
        return instantSpeedCosts(name) != null;
    }

    /**
     * The mana costs of {@code name}'s faces that can be cast at instant speed (an instant, or with flash), or
     * {@code null} when there are none, or it isn't a card: only those faces are narrowed down. The faces that can be
     * cast are the front and, for a split, adventure, omen or modal double-faced card, the other one; flash granted by
     * another card on the table isn't seen. Card data only: any thread.
     */
    static List<ManaCost> instantSpeedCosts(String name) {
        PaperCard printing = StaticData.instance().getCommonCards().getUniqueByName(name);
        if (printing == null) {
            return null;
        }
        CardRules rules = printing.getRules();
        List<ICardFace> faces = new ArrayList<>();
        faces.add(rules.getMainPart());
        CardSplitType split = rules.getSplitType();
        if (rules.getOtherPart() != null && (split == CardSplitType.Split || split == CardSplitType.Adventure
                || split == CardSplitType.Omen || split == CardSplitType.Modal)) {
            faces.add(rules.getOtherPart());
        }
        List<ManaCost> costs = new ArrayList<>();
        for (ICardFace face : faces) {
            if (face.getType().isInstant() || hasFlash(face)) {
                costs.add(face.getManaCost());
            }
        }
        return costs.isEmpty() ? null : costs;
    }

    private static boolean hasFlash(ICardFace face) {
        if (face.getKeywords() == null) {
            return false;
        }
        for (String keyword : face.getKeywords()) {
            if (keyword.equals("Flash")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code sources} could pay {@code cost}, each source making one mana: every colored or {C} pip gets a
     * source of its own that makes it (a matching, so a source that makes any color isn't counted twice), and the
     * sources left over cover the generic part. An approximation: X is 0; a hybrid pip takes either color, and {2/W}
     * either W or two generic; a Phyrexian pip is paid with life, so needs no source; snow counts as generic; a source
     * that makes two mana (Sol Ring) or only for some spells counts as one; cost changes and extra costs aren't seen.
     * A card with no mana cost can't be cast.
     */
    static boolean couldPay(ManaCost cost, List<Integer> sources) {
        if (cost.isNoCost()) {
            return false;
        }
        int generic = cost.getGenericCost();
        List<Integer> pips = new ArrayList<>();
        List<Integer> orTwo = new ArrayList<>(); // {2/W}: a pip, or two generic
        for (ManaCostShard shard : cost) {
            int colors = shard.getShard() & (ManaAtom.ALL_MANA_COLORS | ManaAtom.COLORLESS);
            if (shard.isOfKind(ManaAtom.IS_X) || shard.isPhyrexian()) {
                continue;
            }
            if (colors == 0) {
                generic += shard.getCmc(); // snow, or a generic shard
            } else if (shard.isOr2Generic()) {
                orTwo.add(colors);
            } else {
                pips.add(colors);
            }
        }
        // a cost has few {2/W} pips, so trying each way to pay them is cheap
        for (int asPips = 0; asPips < 1 << orTwo.size(); asPips++) {
            List<Integer> needed = new ArrayList<>(pips);
            int extraGeneric = 0;
            for (int i = 0; i < orTwo.size(); i++) {
                if ((asPips & 1 << i) != 0) {
                    needed.add(orTwo.get(i));
                } else {
                    extraGeneric += 2;
                }
            }
            if (sources.size() >= needed.size() + generic + extraGeneric && allMatched(needed, sources)) {
                return true;
            }
        }
        return false;
    }

    /** Whether each pip gets a different source that makes one of its colors (Kuhn's augmenting paths). */
    private static boolean allMatched(List<Integer> pips, List<Integer> sources) {
        int[] pipOfSource = new int[sources.size()];
        Arrays.fill(pipOfSource, -1);
        for (int pip = 0; pip < pips.size(); pip++) {
            if (!augment(pip, pips, sources, pipOfSource, new boolean[sources.size()])) {
                return false;
            }
        }
        return true;
    }

    private static boolean augment(int pip, List<Integer> pips, List<Integer> sources, int[] pipOfSource,
                                   boolean[] seen) {
        for (int s = 0; s < sources.size(); s++) {
            if (seen[s] || (sources.get(s) & pips.get(pip)) == 0) {
                continue;
            }
            seen[s] = true;
            if (pipOfSource[s] < 0 || augment(pipOfSource[s], pips, sources, pipOfSource, seen)) {
                pipOfSource[s] = pip;
                return true;
            }
        }
        return false;
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

    /** Casts {@code commander} from its owner's Command zone, or puts it onto the battlefield from there. */
    private static Result addCommander(Player player, Card commander, Mode mode) {
        Game game = player.getGame();
        String name = commander.getName();
        boolean done;
        if (mode == Mode.PUT_ONTO_BATTLEFIELD) {
            Card moved = commander.isPermanent()
                    ? game.getAction().moveToPlay(commander, player, null, AbilityKey.newMap()) : null;
            done = moved != null && moved.isInZone(ZoneType.Battlefield);
        } else {
            done = cast(player, commander);
        }
        settle(game);
        if (!done) {
            // a cancelled cast leaves it where it was, in the Command zone
            return Result.add(player.getName(), name, null, false, name + " wasn't added: "
                    + (mode == Mode.CAST ? "it couldn't be cast, or the cast was cancelled." : "it isn't a permanent."));
        }
        // nothing left the hand
        return Result.add(player.getName(), name, CompanionLogEntry.FromHand.NONE, true, player.getName()
                + (mode == Mode.CAST ? " casts " : " puts in ") + name + " from the Command zone");
    }

    /** {@code player}'s own commander named {@code name} in their Command zone, or null. */
    private static Card findCommanderInCommandZone(Player player, String name) {
        for (Card card : player.getCardsIn(ZoneType.Command)) {
            if (card.isCommander() && card.getOwner() == player && card.getName().equals(name)) {
                return card;
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
