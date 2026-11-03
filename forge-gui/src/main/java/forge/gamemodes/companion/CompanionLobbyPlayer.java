package forge.gamemodes.companion;

import forge.game.Game;
import forge.game.player.Player;
import forge.player.LobbyPlayerHuman;

/** A seat at a tracked table, run by the operator. Its in-game controller is a {@link CompanionPlayerController}. */
public class CompanionLobbyPlayer extends LobbyPlayerHuman {
    private final CompanionTable table;

    public CompanionLobbyPlayer(String name, CompanionTable table) {
        super(name);
        this.table = table;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id); // the seat's own name, not the device owner's
        player.setFirstController(new CompanionPlayerController(game, player, this, table));
        return player;
    }
}
