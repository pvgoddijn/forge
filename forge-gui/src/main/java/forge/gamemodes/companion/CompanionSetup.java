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
 * @param librarySize unknown cards each library starts with, for the engine to draw from
 * @param engineCombat whether combat is declared through the engine, so its damage and triggers resolve by the
 *                     rules (spec AC-41); otherwise the operator records its outcome
 */
public record CompanionSetup(List<Seat> seats, int life, int handSize, int librarySize, boolean engineCombat) {
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 6;
    public static final int DEFAULT_LIFE = 40;
    public static final int DEFAULT_HAND = 7;
    /** A Commander deck: 99 cards besides the commander, less the opening hand. */
    public static final int DEFAULT_LIBRARY = 99 - DEFAULT_HAND;

    public record Seat(String name, boolean me) { }

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
        return new CompanionSetup(seats, DEFAULT_LIFE, DEFAULT_HAND, DEFAULT_LIBRARY, false);
    }

    public CompanionSetup withLife(int life) {
        return new CompanionSetup(seats, life, handSize, librarySize, engineCombat);
    }

    public CompanionSetup withEngineCombat(boolean on) {
        return new CompanionSetup(seats, life, handSize, librarySize, on);
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
        for (int i = 0; i < seats.size(); i++) {
            lines.add("p" + i + "life=" + life);
            lines.add("p" + i + "hand=" + placeholders(handSize));
            lines.add("p" + i + "library=" + placeholders(librarySize));
        }
        return lines;
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
