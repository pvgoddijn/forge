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
import forge.game.player.Player;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.item.PaperToken;

import java.util.List;

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
        for (java.util.Map.Entry<String, CardRules> entry : StaticData.instance().getAllTokens().getRules().entrySet()) {
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
