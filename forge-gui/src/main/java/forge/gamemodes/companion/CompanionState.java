package forge.gamemodes.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The immutable result of replaying a game's log. */
public record CompanionState(List<PlayerState> players, boolean ended) {
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 6;

    /** One physical card in the game. Two copies of a card have different instance IDs. */
    public record GameCard(int instanceId, String name) { }

    public record PlayerState(String name, boolean me, List<GameCard> battlefield) { }

    public CompanionState {
        players = Collections.unmodifiableList(new ArrayList<>(players));
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

        List<List<GameCard>> battlefields = new ArrayList<>();
        for (int i = 0; i < started.players().size(); i++) {
            battlefields.add(new ArrayList<>());
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
                if (added.player() < 0 || added.player() >= battlefields.size()) {
                    throw new IllegalArgumentException("No player " + added.player());
                }
                battlefields.get(added.player()).add(new GameCard(added.seq(), added.cardName()));
            } else if (action instanceof CompanionAction.GameEnded) {
                ended = true;
            }
        }

        List<PlayerState> players = new ArrayList<>();
        for (int i = 0; i < started.players().size(); i++) {
            CompanionAction.PlayerSetup setup = started.players().get(i);
            players.add(new PlayerState(setup.name(), setup.me(),
                    Collections.unmodifiableList(battlefields.get(i))));
        }
        return new CompanionState(players, ended);
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
