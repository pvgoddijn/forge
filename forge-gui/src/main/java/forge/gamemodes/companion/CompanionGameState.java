package forge.gamemodes.companion;

import forge.StaticData;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.MagicStack;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge's game-state text, plus what Companion needs on load: the placeholders, which Forge's loader can't make, are
 * put back (see {@link PlaceholderText}), and every player gets the table effect back, as a game state doesn't save
 * effects. It also holds what Forge's state doesn't: how often each commander was cast from the Command zone, the tax,
 * one {@code p1commandercast=2|Krenko, Mob Boss} line per commander cast, put back on the player's commander of that
 * name after a load (see {@link #applyGameOnThread}). And the spells cast this turn, which the storm count and "the
 * second spell you cast each turn" read: one {@code p0spellcastthisturn=Original|Divination} line per spell, in the
 * order cast, by the index of the player who cast it (see {@link #restoreSpellsCastThisTurn}).
 */
public class CompanionGameState extends GameState {
    private static final String COMMANDER_CAST = "commandercast";
    private static final String SPELL_CAST = "spellcastthisturn";

    /** A commander's cast count from the Command zone, by the index of its owner in the game's players. */
    private record CommanderCast(int player, String name, int count) { }

    /** A spell cast this turn: by the player at that index, the card's printed name and the face it was cast as. */
    private record SpellCast(int player, CardStateName state, String name) { }

    private final List<CommanderCast> commanderCasts = new ArrayList<>();
    private final List<SpellCast> spellsCast = new ArrayList<>();
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
        for (SpellAbility spell : game.getStack().getSpellsCastThisTurn()) {
            Card card = spell.getHostCard();
            int player = players.indexOf(spell.getActivatingPlayer());
            if (card != null && player >= 0) {
                state.spellsCast.add(new SpellCast(player, card.getCurrentStateName(), card.getPaperCard().getName()));
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
        for (SpellCast cast : spellsCast) {
            sb.append('p').append(cast.player()).append(SPELL_CAST).append('=')
                    .append(cast.state().name()).append('|').append(cast.name()).append('\n');
        }
        return sb.toString();
    }

    @Override
    protected void parseLine(String line) {
        int eq = line.indexOf('=');
        String key = eq < 0 ? "" : line.substring(0, eq).trim().toLowerCase();
        String suffix = key.endsWith(COMMANDER_CAST) ? COMMANDER_CAST : key.endsWith(SPELL_CAST) ? SPELL_CAST : null;
        if (!key.startsWith("p") || suffix == null) {
            super.parseLine(line);
            return;
        }
        String value = line.substring(eq + 1);
        int bar = value.indexOf('|');
        String before = value.substring(0, Math.max(bar, 0)).trim();
        String name = value.substring(bar + 1).trim();
        try {
            int player = Integer.parseInt(key.substring(1, key.length() - suffix.length()));
            if (suffix.equals(COMMANDER_CAST)) {
                commanderCasts.add(new CommanderCast(player, name, Integer.parseInt(before)));
            } else {
                CardStateName state = CardStateName.smartValueOf(before);
                spellsCast.add(new SpellCast(player, state == null ? CardStateName.Original : state, name));
            }
        } catch (IllegalArgumentException e) { // NumberFormatException too
            System.err.println("Unreadable " + suffix + " line, skipped: " + line);
        }
    }

    /**
     * Applies this state right away. Call on the game thread, before the main loop or between priority prompts. A
     * save is made with an empty stack, so anything on the stack now is from after it and goes (see
     * {@link #clearStack}). A combat in progress is ended by Forge's own apply, which sets the phase.
     */
    public void applyNow(Game game) {
        clearStack(game);
        applyGameOnThread(game);
        for (Player player : game.getPlayers()) {
            clearLifeThisTurn(player);
        }
        // Forge sets the turn number without telling the view, which would show the old turn until the next one began
        game.updateTurnForView();
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
        restoreSpellsCastThisTurn(game);
    }

    /**
     * The engine keeps each spell cast this turn as a copy of its spell, on a copy of its card, made as it was cast;
     * the storm count is how many there are, and a player's count those they cast. A load doesn't touch them, so an
     * undo kept the spells cast after the save, and a resume had none. They are put back as the save had them: for
     * each, a new card of that name, on the face it was cast as, out of every zone, and its spell, cast by that
     * player. That is what the cast was read for (its colors, types, mana value, who cast it); what the copy can't
     * have back is what was true only of that cast (the mana paid, X, targets, a cost reduction), and a card the
     * catalog doesn't know counts as a spell with nothing to read. They don't count again toward the spells the
     * player cast this game.
     */
    private void restoreSpellsCastThisTurn(Game game) {
        List<Player> players = game.getPlayers();
        List<SpellAbility> castThisTurn = game.getStack().getSpellsCastThisTurn();
        castThisTurn.clear();
        for (SpellCast cast : spellsCast) {
            if (cast.player() < 0 || cast.player() >= players.size()) {
                continue;
            }
            Player player = players.get(cast.player());
            PaperCard printing = StaticData.instance().getCommonCards().getUniqueByName(cast.name());
            Card card = printing == null ? CompanionCards.newPlaceholder(player) : Card.fromPaperCard(printing, player);
            if (printing != null && cast.state() != CardStateName.Original && card.hasState(cast.state())) {
                card.setState(cast.state(), false);
            }
            SpellAbility spell = card.getCurrentState().getNonManaAbilities().stream()
                    .filter(SpellAbility::isSpell).findFirst()
                    .map(sa -> sa.copy(card, player, true))
                    .orElseGet(() -> new SpellAbility.EmptySa(card, player));
            castThisTurn.add(spell);
        }
        game.updateStackForView();
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
