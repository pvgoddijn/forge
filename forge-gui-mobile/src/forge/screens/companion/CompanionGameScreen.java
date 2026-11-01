package forge.screens.companion;

import forge.assets.FSkinFont;
import forge.gamemodes.companion.CompanionGame;
import forge.gamemodes.companion.CompanionState;
import forge.screens.FScreen;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FScrollPane;
import forge.toolbox.FTextField;
import forge.util.Utils;

/** Every player's name and Battlefield at once, with the way to add a card. */
public class CompanionGameScreen extends FScreen {
    private static final float PADDING = Utils.scale(5);
    private static final FSkinFont NAME_FONT = FSkinFont.get(14);
    private static final FSkinFont CARD_FONT = FSkinFont.get(12);

    private final CompanionGame game;
    private final PlayerList playerList = add(new PlayerList());
    private final FButton btnAddCard = add(new FButton("Add card"));

    public CompanionGameScreen(CompanionGame game0) {
        super("Companion");
        game = game0;
        btnAddCard.setCommand(e -> new CardSearchDialog(game, this::refresh).show());
        refresh();
    }

    private void refresh() {
        playerList.rebuild();
        playerList.revalidate();
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float buttonHeight = FTextField.getDefaultHeight() * 1.2f;
        btnAddCard.setBounds(PADDING, height - buttonHeight - PADDING, width - 2 * PADDING, buttonHeight);
        playerList.setBounds(0, startY, width, btnAddCard.getTop() - PADDING - startY);
    }

    /** One name row per player, followed by a row per card on their Battlefield. */
    private class PlayerList extends FScrollPane {
        void rebuild() {
            clear();
            for (CompanionState.PlayerState player : game.state().players()) {
                add(new FLabel.Builder().text(player.me() ? player.name() + " (Me)" : player.name())
                        .font(NAME_FONT).opaque().build());
                for (CompanionState.GameCard card : player.battlefield()) {
                    add(new FLabel.Builder().text(card.name()).font(CARD_FONT).build());
                }
            }
        }

        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            float y = 0;
            for (var child : getChildren()) {
                FLabel label = (FLabel) child;
                float rowHeight = label.getFont() == NAME_FONT ? Utils.scale(26) : Utils.scale(20);
                label.setBounds(PADDING, y, visibleWidth - 2 * PADDING, rowHeight);
                y += rowHeight;
            }
            return new ScrollBounds(visibleWidth, y);
        }
    }
}
