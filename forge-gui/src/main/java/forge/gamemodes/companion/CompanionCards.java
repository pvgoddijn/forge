package forge.gamemodes.companion;

import forge.ImageKeys;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.card.GamePieceType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.Arrays;

/**
 * The cards Companion adds to a real game. A placeholder stands for a card the operator doesn't know, in a hand or a
 * library, so the engine's own draws, discards and bounces keep each hand's count right. The table effect keeps a
 * tracked game from ending on its own: at a real table the players decide that.
 */
public final class CompanionCards {
    /** The placeholder's name, as saved in a game state. No real card has it. */
    public static final String PLACEHOLDER_NAME = "Unknown card";
    static final String TABLE_EFFECT_NAME = "Companion table";

    // Never added to the card database, so search, deck building and the AI never see it.
    private static final PaperCard PLACEHOLDER = new PaperCard(CardRules.fromScript(Arrays.asList(
            "Name:" + PLACEHOLDER_NAME,
            "ManaCost:no cost",
            "Types:Card",
            "Oracle:A card nobody at the table has seen.")), CardEdition.UNKNOWN_CODE, CardRarity.Special) {
        // A card is rebuilt from its printing whenever it changes zone, so the card back has to come from here.
        @Override
        public String getImageKey(boolean altState) {
            return ImageKeys.getTokenKey(ImageKeys.HIDDEN_CARD);
        }
    };

    private CompanionCards() {
    }

    public static boolean isPlaceholder(Card card) {
        return card.getPaperCard() == PLACEHOLDER;
    }

    /** A new placeholder for {@code player}, shown as a card back. Not yet in any zone. */
    public static Card newPlaceholder(Player player) {
        Card card = Card.fromPaperCard(PLACEHOLDER, player);
        showAsCardBack(card);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        return card;
    }

    static void showAsCardBack(Card card) {
        // on this card's own state: Card.setImageKey changes the shared copy used for previews instead
        card.getCurrentState().setImageKey(ImageKeys.getTokenKey(ImageKeys.HIDDEN_CARD));
    }

    /** How many cards in {@code player}'s hand nobody knows. */
    public static int hiddenInHand(Player player) {
        int count = 0;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (isPlaceholder(card)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Gives {@code player} the effect that keeps the game going: no maximum hand size, and no losing or winning by the
     * rules. A game state doesn't save effects, so this is added again whenever a game is loaded.
     */
    static void addTableEffect(Game game, Player player) {
        Card effect = new Card(game.nextCardId(), game);
        effect.setOwner(player);
        effect.setName(TABLE_EFFECT_NAME);
        effect.setImageKey("t:puzzle");
        effect.setGamePieceType(GamePieceType.EFFECT);
        effect.setOracleText("You have no maximum hand size. You can't lose the game and can't win it: the table decides when it ends.");
        effect.addStaticAbility("Mode$ Continuous | Affected$ You | EffectZone$ Command | SetMaxHandSize$ Unlimited"
                + " | Description$ You have no maximum hand size.");
        addReplacement(effect, "Event$ GameLoss | ActiveZones$ Command | ValidPlayer$ You | Layer$ CantHappen"
                + " | Description$ You can't lose the game.");
        addReplacement(effect, "Event$ GameWin | ActiveZones$ Command | ValidPlayer$ You | Layer$ CantHappen"
                + " | Description$ You can't win the game.");
        effect.setGameTimestamp(game.getNextTimestamp());
        player.getZone(ZoneType.Command).add(effect);
    }

    private static void addReplacement(Card host, String script) {
        ReplacementEffect re = ReplacementHandler.parseReplacement(script, host, true);
        host.addReplacementEffect(re);
    }
}
