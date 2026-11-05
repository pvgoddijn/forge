package forge.screens.companion;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gamemodes.companion.CompanionTable;
import forge.toolbox.GuiChoose;

import java.util.ArrayList;
import java.util.List;

/**
 * "Move…" from a card's menu (AC-22): pick the player, then the zone, and the table moves the card there. Two choices
 * on the UI thread, from the card's view; the move itself is an op, run on the game thread, which finds the card again
 * by id and refuses with a message if it's gone or can't go there.
 */
final class MoveDialog {
    private MoveDialog() {
    }

    /** One zone to pick, as the operator calls it. */
    private record Zone(String label, ZoneType zone) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<Zone> ZONES = List.of(new Zone("Hand", ZoneType.Hand),
            new Zone("Battlefield", ZoneType.Battlefield), new Zone("Graveyard", ZoneType.Graveyard),
            new Zone("Exile", ZoneType.Exile), new Zone("Library top", ZoneType.Library),
            new Zone("Command", ZoneType.Command));

    /** Asks for the player, then the zone, and submits the move of {@code card}. */
    static void show(CompanionTable table, GameView gameView, CardView card) {
        String name = card.getName();
        List<String> seats = new ArrayList<>();
        for (PlayerView p : gameView.getPlayers()) {
            seats.add(p.getName());
        }
        GuiChoose.oneOrNone("Move " + name + " to which player?", seats, player -> {
            if (player == null) {
                return;
            }
            GuiChoose.oneOrNone("Move " + name + " to " + player + "'s", ZONES, zone -> {
                if (zone != null) {
                    // by seat name, which setup keeps unique, as the other dialogs do
                    table.submit(new CompanionTable.Move(card.getId(), name, player, zone.zone()));
                }
            });
        });
    }
}
