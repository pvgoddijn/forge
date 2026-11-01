package forge.gamemodes.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The immutable result of replaying a game's log. */
public record CompanionState(List<PlayerState> players, boolean ended) {
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 6;
    public static final int STARTING_HAND = 7;

    /** One physical card in the game. Two copies of a card have different instance IDs. */
    public record GameCard(int instanceId, String name) { }

    public record PlayerState(String name, boolean me, int hiddenHand, Map<CompanionZone, List<GameCard>> zones) {
        public List<GameCard> zone(CompanionZone zone) {
            return zones.get(zone);
        }

        public List<GameCard> battlefield() {
            return zone(CompanionZone.BATTLEFIELD);
        }

        /** The cards known to be in this player's hand. */
        public List<GameCard> knownHand() {
            return zone(CompanionZone.HAND);
        }
    }

    public CompanionState {
        players = Collections.unmodifiableList(new ArrayList<>(players));
    }

    /** A player while the log is being replayed. */
    private static final class Seat {
        private int hiddenHand = STARTING_HAND;
        private final Map<CompanionZone, List<GameCard>> zones = new EnumMap<>(CompanionZone.class);

        Seat() {
            for (CompanionZone zone : CompanionZone.values()) {
                zones.put(zone, new ArrayList<>());
            }
        }

        void changeHidden(int delta) {
            hiddenHand = Math.max(0, hiddenHand + delta);
        }

        /** Takes the card out of the hand: a known one by that name if there is one, else one from the hidden count. */
        GameCard takeFromHand(String cardName) {
            List<GameCard> known = zones.get(CompanionZone.HAND);
            for (int i = 0; i < known.size(); i++) {
                if (known.get(i).name().equals(cardName)) {
                    return known.remove(i);
                }
            }
            changeHidden(-1);
            return null;
        }

        PlayerState toState(CompanionAction.PlayerSetup setup) {
            Map<CompanionZone, List<GameCard>> frozen = new EnumMap<>(CompanionZone.class);
            zones.forEach((zone, cards) -> frozen.put(zone, Collections.unmodifiableList(cards)));
            return new PlayerState(setup.name(), setup.me(), hiddenHand, Collections.unmodifiableMap(frozen));
        }
    }

    /**
     * Computes the state a log leads to.
     *
     * @throws IllegalArgumentException if the log isn't a valid game
     */
    public static CompanionState replay(List<CompanionAction> log) {
        if (log.isEmpty() || !(log.get(0) instanceof CompanionAction.GameStarted started)) {
            throw new IllegalArgumentException("A game's log must begin with GameStarted");
        }
        checkPlayers(started.players());

        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < started.players().size(); i++) {
            seats.add(new Seat());
        }

        boolean ended = false;
        for (int i = 0; i < log.size(); i++) {
            CompanionAction action = log.get(i);
            if (action.seq() != i) {
                throw new IllegalArgumentException("Action " + i + " has sequence number " + action.seq());
            }
            if (ended) {
                throw new IllegalArgumentException("Action " + i + " comes after the game ended");
            }
            if (action instanceof CompanionAction.GameStarted) {
                if (i != 0) {
                    throw new IllegalArgumentException("GameStarted at action " + i);
                }
            } else if (action instanceof CompanionAction.CardAdded added) {
                Seat seat = seat(seats, added.player());
                GameCard known = added.from() == CompanionZone.HAND ? seat.takeFromHand(added.cardName()) : null;
                GameCard card = known != null ? known : new GameCard(added.seq(), added.cardName());
                seat.zones.get(added.to()).add(card);
            } else if (action instanceof CompanionAction.CardMoved moved) {
                Seat target = seat(seats, moved.toPlayer());
                GameCard card = removeCard(seats, moved);
                target.zones.get(moved.to()).add(card);
            } else if (action instanceof CompanionAction.HandCountChanged changed) {
                seat(seats, changed.player()).changeHidden(changed.delta());
            } else if (action instanceof CompanionAction.GameEnded) {
                ended = true;
            }
        }

        List<PlayerState> players = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            players.add(seats.get(i).toState(started.players().get(i)));
        }
        return new CompanionState(players, ended);
    }

    private static Seat seat(List<Seat> seats, int player) {
        if (player < 0 || player >= seats.size()) {
            throw new IllegalArgumentException("No player " + player);
        }
        return seats.get(player);
    }

    /** A tracked card in a hand is a known card, so moving it out leaves the hidden count alone. */
    private static GameCard removeCard(List<Seat> seats, CompanionAction.CardMoved moved) {
        for (Seat seat : seats) {
            for (List<GameCard> cards : seat.zones.values()) {
                for (int i = 0; i < cards.size(); i++) {
                    if (cards.get(i).instanceId() == moved.instanceId()) {
                        return cards.remove(i);
                    }
                }
            }
        }
        throw new IllegalArgumentException("No card with instance " + moved.instanceId());
    }

    private static void checkPlayers(List<CompanionAction.PlayerSetup> players) {
        if (players.size() < MIN_PLAYERS || players.size() > MAX_PLAYERS) {
            throw new IllegalArgumentException("A game has " + MIN_PLAYERS + " to " + MAX_PLAYERS
                    + " players, not " + players.size());
        }
        int me = 0;
        for (CompanionAction.PlayerSetup player : players) {
            if (player.name() == null || player.name().trim().isEmpty()) {
                throw new IllegalArgumentException("Every player needs a name");
            }
            if (player.me()) {
                me++;
            }
        }
        if (me != 1) {
            throw new IllegalArgumentException("Exactly one player must be Me, not " + me);
        }
    }
}
