package forge.screens.companion;

import forge.gamemodes.companion.CompanionGame;
import forge.gamemodes.companion.CompanionState;
import forge.gamemodes.companion.CompanionZone;
import forge.toolbox.FComboBox;
import forge.toolbox.FDialog;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.util.ArrayList;
import java.util.List;

/** Moves one card to any zone of any player. */
class CardMoveDialog extends FDialog {
    private static final float PADDING = Utils.scale(5);

    private final FComboBox<String> cbPlayer = add(new FComboBox<>());
    private final FComboBox<String> cbZone = add(new FComboBox<>());

    CardMoveDialog(CompanionGame game, CompanionState.GameCard card, int fromPlayer, CompanionZone fromZone, Runnable onMoved) {
        super("Move " + card.name(), 2);

        List<String> playerNames = new ArrayList<>();
        for (CompanionState.PlayerState player : game.state().players()) {
            playerNames.add(player.name());
        }
        cbPlayer.setItems(playerNames, playerNames.get(fromPlayer));

        List<String> zoneNames = new ArrayList<>();
        for (CompanionZone zone : CompanionZone.values()) {
            zoneNames.add(CompanionGameScreen.zoneName(zone));
        }
        // the most common destination: the card's current zone would be no move at all
        CompanionZone suggested = fromZone == CompanionZone.GRAVEYARD ? CompanionZone.BATTLEFIELD : CompanionZone.GRAVEYARD;
        cbZone.setItems(zoneNames, CompanionGameScreen.zoneName(suggested));

        initButton(0, "Move", e -> {
            int toPlayer = cbPlayer.getSelectedIndex();
            CompanionZone toZone = CompanionZone.values()[cbZone.getSelectedIndex()];
            hide();
            if (toPlayer == fromPlayer && toZone == fromZone) {
                return;
            }
            if (CompanionGameScreen.save(() -> game.moveCard(card.instanceId(), toPlayer, toZone))) {
                onMoved.run();
            }
        });
        initButton(1, "Cancel", e -> hide());
    }

    @Override
    protected float layoutAndGetHeight(float width, float maxHeight) {
        float rowHeight = FTextField.getDefaultHeight();
        float w = width - 2 * PADDING;
        cbPlayer.setBounds(PADDING, PADDING, w, rowHeight);
        cbZone.setBounds(PADDING, 2 * PADDING + rowHeight, w, rowHeight);
        // room below for the drop-downs, which open downward and would otherwise run off the screen
        return 3 * PADDING + 6 * rowHeight;
    }
}
