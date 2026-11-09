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
        match.setStartGameHook(() -> CompanionGameState.of(table.latestState()).applyNow(match.getGame()));

        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        match.startMatch(rules, null, players, guis, null);
    }
}
