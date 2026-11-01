package forge.screens.companion;

import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardRules;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gamemodes.companion.CompanionState;
import forge.gamemodes.companion.CompanionZone;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableProperty;
import forge.trackable.Tracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows a tracked game through the view objects Forge's match screens draw. It holds no game state of its own: each
 * {@link #sync} copies a {@link CompanionState} into the views. No {@code Game} exists behind them.
 */
class CompanionBoard {
    /** A card in a hand that the app doesn't know. These are drawn as card backs, and have IDs below zero. */
    private static final int HIDDEN_ID_BASE = -1000;
    /** Life isn't tracked until turns and life are added, so every player shows the Commander starting total. */
    private static final int STARTING_LIFE = 40;

    private final Tracker tracker = new Tracker();
    private final List<PlayerView> players = new ArrayList<>();
    private final Map<Integer, CardView> cards = new HashMap<>();
    private final Map<Integer, CardView> hiddenCards = new HashMap<>();

    CompanionBoard(CompanionState state) {
        for (int i = 0; i < state.players().size(); i++) {
            CompanionState.PlayerState player = state.players().get(i);
            PlayerView view = new PlayerView(i, tracker);
            view.set(TrackableProperty.Name, player.name());
            view.set(TrackableProperty.LobbyPlayerName, player.name());
            view.set(TrackableProperty.Life, STARTING_LIFE);
            players.add(view);
        }
        sync(state);
    }

    List<PlayerView> players() {
        return players;
    }

    static boolean isHidden(CardView card) {
        return card.getId() <= HIDDEN_ID_BASE;
    }

    /** The seat a hidden card belongs to. */
    int ownerOf(CardView card) {
        return players.indexOf(card.getController());
    }

    /** Copies the state into the views. A card keeps its view for life, as the card panels are tied to that object. */
    void sync(CompanionState state) {
        for (int i = 0; i < players.size(); i++) {
            CompanionState.PlayerState player = state.players().get(i);
            PlayerView view = players.get(i);
            view.set(TrackableProperty.Battlefield, cardsIn(player, CompanionZone.BATTLEFIELD, ZoneType.Battlefield, view));
            view.set(TrackableProperty.Graveyard, cardsIn(player, CompanionZone.GRAVEYARD, ZoneType.Graveyard, view));
            view.set(TrackableProperty.Exile, cardsIn(player, CompanionZone.EXILE, ZoneType.Exile, view));
            view.set(TrackableProperty.Command, cardsIn(player, CompanionZone.COMMAND, ZoneType.Command, view));

            TrackableCollection<CardView> hand = cardsIn(player, CompanionZone.HAND, ZoneType.Hand, view);
            for (int n = 0; n < player.hiddenHand(); n++) {
                hand.add(hiddenCard(i, n, view));
            }
            view.set(TrackableProperty.Hand, hand);
        }
    }

    private TrackableCollection<CardView> cardsIn(CompanionState.PlayerState player, CompanionZone zone, ZoneType zoneType, PlayerView owner) {
        TrackableCollection<CardView> views = new TrackableCollection<>();
        for (CompanionState.GameCard card : player.zone(zone)) {
            CardView view = cards.computeIfAbsent(card.instanceId(), id -> createCard(id, card.name()));
            place(view, zoneType, owner);
            views.add(view);
        }
        return views;
    }

    private CardView hiddenCard(int player, int n, PlayerView owner) {
        int id = HIDDEN_ID_BASE - player * 1000 - n;
        CardView view = hiddenCards.computeIfAbsent(id, i -> {
            CardView hidden = new CardView(i, tracker, "");
            hidden.getCurrentState().set(TrackableProperty.ImageKey, ImageKeys.getTokenKey(ImageKeys.HIDDEN_CARD));
            return hidden;
        });
        place(view, ZoneType.Hand, owner);
        return view;
    }

    private static void place(CardView view, ZoneType zone, PlayerView owner) {
        view.set(TrackableProperty.Zone, zone);
        view.set(TrackableProperty.Owner, owner);
        view.set(TrackableProperty.Controller, owner);
    }

    /** A view with the card's real image and text if Forge knows the name, else one showing just the name. */
    private CardView createCard(int instanceId, String name) {
        IPaperCard paper = findPrinting(name);
        if (paper != null) {
            Card card = CardFactory.getCard(paper, null, instanceId, null);
            return card.getView();
        }
        return new CardView(instanceId, tracker, name);
    }

    /** The default printing of a card, of the card with this back face, or of a token with this name. */
    private static IPaperCard findPrinting(String name) {
        PaperCard card = StaticData.instance().getCommonCards().getCard(name);
        if (card != null) {
            return card;
        }
        if (StaticData.instance().getCommonCards().getFaceByName(name) != null) {
            for (PaperCard other : StaticData.instance().getCommonCards().getUniqueCards()) {
                CardRules rules = other.getRules();
                if (rules.getOtherPart() != null && name.equals(rules.getOtherPart().getName())) {
                    return other;
                }
            }
        }
        for (Map.Entry<String, CardRules> token : StaticData.instance().getAllTokens().getRules().entrySet()) {
            if (name.equals(token.getValue().getName())) {
                return StaticData.instance().getAllTokens().getToken(token.getKey());
            }
        }
        return null;
    }
}
