package forge.gamemodes.companion;

import forge.game.Game;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.event.GameEventSnapshotRestored;
import forge.game.player.Player;
import forge.game.zone.MagicStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge's game-state text, plus what Companion needs on load: the placeholders, which Forge's loader can't make, are
 * put back (see {@link PlaceholderText}), and every player gets the table effect back, as a game state doesn't save
 * effects. It also holds what Forge's state doesn't: how often each commander was cast from the Command zone, the tax,
 * one {@code p1commandercast=2|Krenko, Mob Boss} line per commander cast, put back on the player's commander of that
 * name after a load (see {@link #applyGameOnThread}).
 */
public class CompanionGameState extends GameState {
    private static final String COMMANDER_CAST = "commandercast";

    /** A commander's cast count from the Command zone, by the index of its owner in the game's players. */
    private record CommanderCast(int player, String name, int count) { }

    private final List<CommanderCast> commanderCasts = new ArrayList<>();
    private PlaceholderText placeholders;

    public static CompanionGameState of(List<String> lines) {
        CompanionGameState state = new CompanionGameState();
        state.placeholders = PlaceholderText.extract(lines);
        state.parse(state.placeholders.lines());
        return state;
    }

    /** The current state of {@code game} as text. Call on the game thread. */
    public static String capture(Game game) {
        CompanionGameState state = new CompanionGameState();
        state.initFromGame(game);
        List<Player> players = game.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            for (Card commander : player.getCommanders()) {
                int count = player.getCommanderCast(commander);
                if (count > 0) {
                    state.commanderCasts.add(new CommanderCast(i, commander.getName(), count));
                }
            }
        }
        return state.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        for (CommanderCast cast : commanderCasts) {
            sb.append('p').append(cast.player()).append(COMMANDER_CAST).append('=')
                    .append(cast.count()).append('|').append(cast.name()).append('\n');
        }
        return sb.toString();
    }

    @Override
    protected void parseLine(String line) {
        int eq = line.indexOf('=');
        String key = eq < 0 ? "" : line.substring(0, eq).trim().toLowerCase();
        if (!key.startsWith("p") || !key.endsWith(COMMANDER_CAST)) {
            super.parseLine(line);
            return;
        }
        String value = line.substring(eq + 1);
        int bar = value.indexOf('|');
        try {
            int player = Integer.parseInt(key.substring(1, key.length() - COMMANDER_CAST.length()));
            int count = Integer.parseInt(value.substring(0, Math.max(bar, 0)).trim());
            commanderCasts.add(new CommanderCast(player, value.substring(bar + 1).trim(), count));
        } catch (NumberFormatException e) {
            System.err.println("Unreadable commander cast count, skipped: " + line);
        }
    }

    /**
     * Applies this state right away. Call on the game thread, before the main loop or between priority prompts. A
     * save is made with an empty stack, so anything on the stack now is from after it and goes (see
     * {@link #clearStack}). A combat in progress is ended by Forge's own apply, which sets the phase.
     */
    public void applyNow(Game game) {
        // as Forge's own undo does: every card is made again with a new id, so a screen rebuilds and a remote view
        // gets the whole state again (ProtocolGuiGame), as changes over the old ids would name nothing
        game.fireEvent(new GameEventSnapshotRestored(true));
        try {
            clearStack(game);
            applyGameOnThread(game);
            for (Player player : game.getPlayers()) {
                clearLifeThisTurn(player);
            }
            // Forge sets the turn number without telling the view, which would show the old turn until the next one began
            game.updateTurnForView();
        } finally {
            game.fireEvent(new GameEventSnapshotRestored(false));
        }
    }

    /**
     * Empties the stack: its spells and abilities, the triggers waiting to go on it, and the cards in the stack zone.
     * Forge's apply only resets the players' zones, so a spell cast after the save would stay on the stack, pointing
     * at a card no zone has any more, and resolve after the state is back.
     */
    private static void clearStack(Game game) {
        MagicStack stack = game.getStack();
        stack.clearSimultaneousStack();
        stack.clearFrozen();
        stack.clear();
        game.getStackZone().removeAllCards(true);
        game.updateStackForView();
    }

    /**
     * Forge sets each player's life from a state by a gain or a loss, which counts toward this turn's tallies: a
     * table starting at 40 would have gained 20 already, and "the first time you gain life each turn" wouldn't fire.
     * A save doesn't hold the tallies, so zero is the honest value. On a resume mid-turn that loses what was really
     * gained or lost earlier in that turn.
     */
    private static void clearLifeThisTurn(Player player) {
        player.setLifeGainedThisTurn(0);
        player.setLifeGainedTimesThisTurn(0);
        player.setLifeLostThisTurn(0);
        player.addLifeGainedByTeamThisTurn(-player.getLifeGainedByTeamThisTurn());
    }

    @Override
    protected void applyGameOnThread(Game game) {
        super.applyGameOnThread(game);
        if (placeholders != null) {
            placeholders.putBack(game);
        }
        for (Player player : game.getPlayers()) {
            CompanionCards.addTableEffect(game, player);
        }
        restoreCommanderCasts(game);
    }

    /**
     * The engine keys the tax by card, and the load made new cards: what it counted is for cards no longer in the
     * game (its commander damage too, which a state doesn't save either), so it goes, and each saved count is put on
     * the player's commander of that name.
     */
    private void restoreCommanderCasts(Game game) {
        List<Player> players = game.getPlayers();
        for (Player player : players) {
            player.resetCommanderStats();
        }
        for (CommanderCast cast : commanderCasts) {
            if (cast.player() < 0 || cast.player() >= players.size()) {
                continue;
            }
            Player player = players.get(cast.player());
            for (Card commander : player.getCommanders()) {
                if (commander.getName().equals(cast.name())) {
                    for (int i = 0; i < cast.count(); i++) {
                        player.incCommanderCast(commander);
                    }
                    break;
                }
            }
        }
    }
}
