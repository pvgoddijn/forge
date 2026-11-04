package forge.screens.companion;

import forge.game.GameView;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CompanionLogEntry;
import forge.gamemodes.companion.CompanionTable;
import forge.toolbox.GuiChoose;

import java.util.ArrayList;
import java.util.List;

/**
 * The "wrong player" correction (AC-22a) from the game menu: pick one of the cards added this turn, then the seat it
 * should have gone to, and the table gives it to them. Two choices on the UI thread; the correction itself is an op,
 * run on the game thread, which finds the card again and refuses with a message if it's gone.
 */
final class WrongPlayerDialog {
    private WrongPlayerDialog() {
    }

    /** One card to pick, labelled with the seat it's logged to now. */
    private record Choice(CompanionLogEntry.Added added) {
        @Override
        public String toString() {
            return added.card() + " (" + added.player() + ")";
        }
    }

    /** Asks for the card, then the seat, and submits the correction. Does nothing when nothing was added this turn. */
    static void show(CompanionTable table, GameView gameView) {
        // as of the latest priority prompt, the table's own list: the UI doesn't read the game or the log
        List<Choice> cards = new ArrayList<>();
        for (CompanionLogEntry.Added added : table.addedThisTurn()) {
            cards.add(new Choice(added));
        }
        if (cards.isEmpty()) {
            return;
        }
        GuiChoose.oneOrNone("Which card went to the wrong player?", cards, card -> {
            if (card == null) {
                return;
            }
            List<String> seats = new ArrayList<>();
            for (PlayerView p : gameView.getPlayers()) {
                if (!p.getName().equals(card.added().player())) {
                    seats.add(p.getName());
                }
            }
            GuiChoose.oneOrNone("Give " + card.added().card() + " to", seats, to -> {
                if (to != null) {
                    // by seat name, which setup keeps unique, as the other dialogs do
                    table.submit(card.added().reassignTo(to));
                }
            });
        });
    }
}
