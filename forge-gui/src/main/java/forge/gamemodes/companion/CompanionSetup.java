package forge.gamemodes.companion;

import forge.card.CardEdition;

import java.util.ArrayList;
import java.util.List;

/**
 * Who sits at the table, in seat order, and how the game starts. The first seat takes the first turn.
 *
 * @param seats       2 to 6 players in seat order; exactly one is Me
 * @param life        starting life for everyone, at least 1
 * @param handSize    unknown cards each player starts with in hand
 * @param librarySize unknown cards the library of a seat with one commander starts with, for the engine to draw
 *                    from: a 100-card deck less the commander and the hand. Each commander more or fewer is one card
 *                    fewer or more, so the deck is 100 cards whatever its commanders
 * @param engineCombat whether combat is declared through the engine, so its damage and triggers resolve by the
 *                     rules (spec AC-41); otherwise the operator records its outcome
 * @param commanderMode whether a card outside the target player's commanders' colour identity is refused (AC-43)
 */
public record CompanionSetup(List<Seat> seats, int life, int handSize, int librarySize, boolean engineCombat,
                             boolean commanderMode) {
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 6;
    public static final int DEFAULT_LIFE = 40;
    public static final int DEFAULT_HAND = 7;
    /** A Commander deck: 100 cards less the one commander and the opening hand. */
    public static final int DECK_SIZE = 100;
    public static final int DEFAULT_LIBRARY = DECK_SIZE - 1 - DEFAULT_HAND;

    /**
     * A player.
     *
     * @param name       the name shown and saved; unique at the table
     * @param me         whether this is the operator's seat
     * @param commanders the commanders' card names, none, one, or two (partners, a background)
     * @param deckList   the player's decklist as it was imported, empty when none
     */
    public record Seat(String name, boolean me, List<String> commanders, String deckList) {
        public Seat {
            commanders = commanders == null ? List.of() : List.copyOf(commanders);
            deckList = deckList == null ? "" : deckList;
        }

        /** A seat with no commander and no deck. */
        public Seat(String name, boolean me) {
            this(name, me, List.of(), "");
        }

        public Seat withCommanders(List<String> names) {
            return new Seat(name, me, names, deckList);
        }

        public Seat withDeckList(String list) {
            return new Seat(name, me, commanders, list);
        }
    }

    public CompanionSetup {
        seats = List.copyOf(seats);
        if (seats.size() < MIN_PLAYERS || seats.size() > MAX_PLAYERS) {
            throw new IllegalArgumentException("A game needs " + MIN_PLAYERS + " to " + MAX_PLAYERS + " players");
        }
        if (seats.stream().filter(Seat::me).count() != 1) {
            throw new IllegalArgumentException("Exactly one player must be Me");
        }
        if (life < 1) {
            throw new IllegalArgumentException("Starting life must be at least 1");
        }
    }

    public static CompanionSetup withDefaults(List<Seat> seats) {
        return new CompanionSetup(seats, DEFAULT_LIFE, DEFAULT_HAND, DEFAULT_LIBRARY, false, true);
    }

    public CompanionSetup withLife(int life) {
        return new CompanionSetup(seats, life, handSize, librarySize, engineCombat, commanderMode);
    }

    public CompanionSetup withEngineCombat(boolean on) {
        return new CompanionSetup(seats, life, handSize, librarySize, on, commanderMode);
    }

    public CompanionSetup withCommanderMode(boolean on) {
        return new CompanionSetup(seats, life, handSize, librarySize, engineCombat, on);
    }

    /** Whether any seat has a commander, so the game is played under the Commander variant's rules. */
    public boolean hasCommanders() {
        return seats.stream().anyMatch(seat -> !seat.commanders().isEmpty());
    }

    /** The commanders' card names of the seat named {@code name}, empty for none or no such seat. */
    public List<String> commandersOf(String name) {
        for (Seat seat : seats) {
            if (seat.name().equals(name)) {
                return seat.commanders();
            }
        }
        return List.of();
    }

    public int meIndex() {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).me()) {
                return i;
            }
        }
        throw new IllegalStateException("No Me");
    }

    /**
     * The seats in the order the game holds its players: starting at Me, so Me's board is at the bottom of the match
     * screen. Turn order is a circle, so this changes nothing about who plays after whom.
     */
    public List<Seat> gameOrder() {
        List<Seat> order = new ArrayList<>(seats.size());
        int me = meIndex();
        for (int i = 0; i < seats.size(); i++) {
            order.add(seats.get((me + i) % seats.size()));
        }
        return order;
    }

    /** Where the first seat, which plays first, sits in {@link #gameOrder()}. */
    public int firstPlayerInGameOrder() {
        return (seats.size() - meIndex()) % seats.size();
    }

    /** The game state the table starts from, in Forge's game-state text format. */
    public List<String> initialState() {
        List<String> lines = new ArrayList<>();
        lines.add("turn=1");
        lines.add("activeplayer=p" + firstPlayerInGameOrder());
        lines.add("activephase=MAIN1");
        List<Seat> order = gameOrder(); // p0 is the game's first player, Me
        for (int i = 0; i < order.size(); i++) {
            List<String> commanders = order.get(i).commanders();
            lines.add("p" + i + "life=" + life);
            lines.add("p" + i + "hand=" + placeholders(handSize));
            lines.add("p" + i + "library=" + placeholders(libraryOf(order.get(i))));
            if (!commanders.isEmpty()) {
                List<String> cards = new ArrayList<>();
                for (String commander : commanders) {
                    cards.add(commander + "|IsCommander");
                }
                lines.add("p" + i + "command=" + String.join(";", cards));
            }
        }
        return lines;
    }

    /** A seat's library at the start: the deck less its commanders and the hand. */
    public int libraryOf(Seat seat) {
        return Math.max(0, librarySize + 1 - seat.commanders().size());
    }

    /**
     * Throws if {@code state} can't belong to this game. Forge's state loader prints most problems and carries on, but
     * a wrong number of players fails once the game has started, where nothing can be set aside any more.
     */
    public void checkState(List<String> state) {
        long players = state.stream().filter(line -> line.matches("p\\d+life=.*")).count();
        if (players != seats.size()) {
            throw new IllegalArgumentException("The save has " + players + " players, the game " + seats.size());
        }
    }

    private static String placeholders(int count) {
        // with its set, or loading looks the name up in the card database for one
        return String.join(";", java.util.Collections.nCopies(count, CompanionCards.PLACEHOLDER_NAME + "|Set:" + CardEdition.UNKNOWN_CODE));
    }
}
