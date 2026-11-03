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
     * Starts {@code table}'s game in {@code match}, shown on {@code gui}, from {@code state}: the saved state when
     * resuming, or the setup's starting state for a new game.
     */
    public static void start(HostedMatch match, IGuiGame gui, CompanionTable table, List<String> state) {
        List<RegisteredPlayer> players = new ArrayList<>();
        Map<RegisteredPlayer, IGuiGame> guis = new LinkedHashMap<>();
        for (CompanionSetup.Seat seat : table.setup().gameOrder()) {
            // no deck and no opening hand: the hands and libraries come from the state, like a puzzle's
            RegisteredPlayer rp = new RegisteredPlayer(new Deck()).setPlayer(new CompanionLobbyPlayer(seat.name(), table));
            rp.setStartingHand(0);
            players.add(rp);
            guis.put(rp, gui);
        }
        CompanionGameState start = CompanionGameState.of(state);
        match.setStartGameHook(() -> start.applyNow(match.getGame()));

        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        match.startMatch(rules, null, players, guis, null);
    }
}
