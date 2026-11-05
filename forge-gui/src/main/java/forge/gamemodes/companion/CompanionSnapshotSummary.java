package forge.gamemodes.companion;

import java.util.ArrayList;
import java.util.List;

/**
 * What a saved state says about each seat, for a past game's final boards: life, the hand (how many cards nobody knows,
 * and the known ones by name) and the public zones by card name. Read from the state's text alone, with no game: a
 * live engine game would run its loop and take taps, which a read-only view mustn't.
 *
 * @param turn   the turn the state was saved in, or 0 if it doesn't say
 * @param active the seat whose turn it was, or null if the state doesn't say
 * @param seats  one per seat, in seat order
 */
public record CompanionSnapshotSummary(int turn, String active, List<Seat> seats) {
    private static final String TOKEN_PIPE = "%7C";

    /**
     * One seat's board. Card names are as the state has them: a token by its name, such as "Soldier Token".
     *
     * @param hiddenInHand the cards in hand nobody knows
     * @param knownInHand  the cards in hand the operator named
     */
    public record Seat(String name, int life, int hiddenInHand, List<String> knownInHand, List<String> battlefield,
                       List<String> graveyard, List<String> exile, List<String> command) {
        public Seat {
            knownInHand = List.copyOf(knownInHand);
            battlefield = List.copyOf(battlefield);
            graveyard = List.copyOf(graveyard);
            exile = List.copyOf(exile);
            command = List.copyOf(command);
        }
    }

    public CompanionSnapshotSummary {
        seats = List.copyOf(seats);
    }

    /**
     * Reads {@code state}, in Forge's game-state text format, as saved for the game {@code setup} describes. The state
     * holds its players in {@link CompanionSetup#gameOrder()}, so {@code p0} is Me. A player the setup has no seat for
     * is left out; a seat the state doesn't mention keeps the starting life and empty zones.
     *
     * @throws IllegalArgumentException if a number in the state doesn't read
     */
    public static CompanionSnapshotSummary of(List<String> state, CompanionSetup setup) {
        List<CompanionSetup.Seat> order = setup.gameOrder();
        List<Builder> builders = new ArrayList<>();
        for (CompanionSetup.Seat seat : order) {
            builders.add(new Builder(seat.name(), setup.life()));
        }
        int turn = 0;
        String active = null;
        for (String line : state) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (key.equals("turn")) {
                turn = number(value);
                continue;
            }
            if (key.equals("activeplayer")) {
                int index = playerIndex(value);
                active = index >= 0 && index < order.size() ? order.get(index).name() : null;
                continue;
            }
            int digits = playerDigits(key);
            if (digits == 0) {
                continue; // not a player's line, such as activephase
            }
            int index = number(key.substring(1, 1 + digits));
            if (index >= builders.size()) {
                continue;
            }
            builders.get(index).read(key.substring(1 + digits), value);
        }
        // seat order, which is how the operator set the table up, rather than the game's order from Me
        List<Seat> seats = new ArrayList<>();
        for (CompanionSetup.Seat seat : setup.seats()) {
            seats.add(builders.get(order.indexOf(seat)).build());
        }
        return new CompanionSnapshotSummary(turn, active, seats);
    }

    /** How many digits follow the {@code p} of a player's key such as {@code p2life}; 0 if it isn't one. */
    private static int playerDigits(String key) {
        if (!key.startsWith("p")) {
            return 0;
        }
        int end = 1;
        while (end < key.length() && Character.isDigit(key.charAt(end))) {
            end++;
        }
        return end - 1;
    }

    /** The index in {@code p2}, or -1. */
    private static int playerIndex(String value) {
        int digits = playerDigits(value);
        return digits == 0 || digits != value.length() - 1 ? -1 : number(value.substring(1));
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number in the saved state: " + text, e);
        }
    }

    /**
     * The card's name in one of a zone's {@code ;}-separated entries, such as {@code Forest|Set:MDD|Art:1|Tapped}, a
     * token {@code t:Soldier Token,P:1,...} or a token script {@code T:w_1_1_soldier}. A foil's {@code +} is dropped.
     */
    static String cardName(String entry) {
        String first = entry.split("\\|", 2)[0].trim();
        if (first.startsWith("t:")) {
            String info = first.substring(2).replace(TOKEN_PIPE, "|");
            int comma = info.indexOf(',');
            return comma < 0 ? info : info.substring(0, comma);
        }
        if (first.startsWith("T:")) {
            return first.substring(2);
        }
        return first.endsWith("+") ? first.substring(0, first.length() - 1) : first;
    }

    private static List<String> cardNames(String value) {
        List<String> names = new ArrayList<>();
        for (String entry : value.split(";")) {
            if (!entry.isBlank()) {
                names.add(cardName(entry));
            }
        }
        return names;
    }

    private static final class Builder {
        private final String name;
        private int life;
        private int hidden;
        private List<String> known = List.of();
        private List<String> battlefield = List.of();
        private List<String> graveyard = List.of();
        private List<String> exile = List.of();
        private List<String> command = List.of();

        Builder(String name, int life) {
            this.name = name;
            this.life = life;
        }

        void read(String field, String value) {
            switch (field) {
                case "life" -> life = number(value);
                case "hand" -> {
                    List<String> known = new ArrayList<>();
                    int hidden = 0;
                    for (String card : cardNames(value)) {
                        if (CompanionCards.PLACEHOLDER_NAME.equals(card)) {
                            hidden++;
                        } else {
                            known.add(card);
                        }
                    }
                    this.known = known;
                    this.hidden = hidden;
                }
                case "battlefield" -> battlefield = cardNames(value);
                case "graveyard" -> graveyard = cardNames(value);
                case "exile" -> exile = cardNames(value);
                case "command" -> command = cardNames(value);
                default -> { } // the library is all unknown cards, and the rest isn't part of a board
            }
        }

        Seat build() {
            return new Seat(name, life, hidden, known, battlefield, graveyard, exile, command);
        }
    }
}
