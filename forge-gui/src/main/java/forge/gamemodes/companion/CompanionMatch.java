package forge.gamemodes.companion;

import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Starts a tracked game as a real match: one human seat per player, all on the operator's screen. */
public final class CompanionMatch {
    private CompanionMatch() {
    }

    /**
     * Starts {@code table}'s game in {@code match}, shown on {@code gui}, from its latest save, or from the setup for
     * a new game. The save is read each time the game starts, so a restart from the match screen doesn't go back to
     * an older state and then save over the newer one.
     */
    public static void start(HostedMatch match, IGuiGame gui, CompanionTable table) {
        List<RegisteredPlayer> players = new ArrayList<>();
        Map<RegisteredPlayer, IGuiGame> guis = new LinkedHashMap<>();
        for (CompanionSetup.Seat seat : table.setup().gameOrder()) {
            // no deck and no opening hand: the hands and libraries come from the state, like a puzzle's
            RegisteredPlayer rp = new RegisteredPlayer(new Deck()).setPlayer(new CompanionLobbyPlayer(seat.name(), table));
            rp.setStartingHand(0);
            players.add(rp);
            guis.put(rp, gui);
        }
        match.setStartGameHook(() -> table.startFromLatest(match.getGame()));

        match.startMatch(rules(table.setup()), null, players, guis, null);
    }

    /**
     * The match's rules: one game, played under {@link #addVariants}, with no coin toss for who starts, as the table
     * decided that. The engine gives the first turn to the first player in game order, and the state the start hook
     * loads then sets whose turn it is: the setup's first seat for a new game, the saved turn for a resumed one.
     */
    public static GameRules rules(CompanionSetup setup) {
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        rules.setFirstSeatStarts(true);
        addVariants(setup, rules);
        return rules;
    }

    /**
     * With a commander at the table, the game is played under the Commander variant, so the engine's command-zone
     * rules apply: a commander that went to a graveyard or exile may go to the Command zone (rule 903.9a), and its
     * casts from there are counted for tax. Casting from the Command zone and the hand-or-library replacement (903.9b)
     * come with the commander effect, which the state's {@code |IsCommander} makes on every load. Starting life, the
     * decks and the opening hands still come from the setup and the state, not from the variant, so the decks stay
     * empty. The table effect still stops a loss by 21 commander damage, which is out of scope (spec v1).
     */
    public static void addVariants(CompanionSetup setup, GameRules rules) {
        if (setup.hasCommanders()) {
            rules.addAppliedVariant(GameType.Commander);
        }
    }
}
