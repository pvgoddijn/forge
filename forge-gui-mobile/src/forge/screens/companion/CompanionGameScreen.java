package forge.screens.companion;

import forge.assets.FSkinFont;
import forge.gamemodes.companion.CompanionGame;
import forge.gamemodes.companion.CompanionState;
import forge.screens.FScreen;
import forge.screens.home.NewGameMenu;
import forge.toolbox.FButton;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FScrollPane;
import forge.toolbox.FTextArea;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every player's name and Battlefield at once, with the way to add a card. */
public class CompanionGameScreen extends FScreen {
    private static final float PADDING = Utils.scale(5);
    private static final float NAME_HEIGHT = Utils.scale(26);
    private static final FSkinFont NAME_FONT = FSkinFont.get(14);
    private static final FSkinFont CARD_FONT = FSkinFont.get(12);

    private final CompanionGame game;
    private final PlayerList playerList = add(new PlayerList());
    private final FButton btnAddCard = add(new FButton("Add card"));

    public CompanionGameScreen(CompanionGame game0) {
        super("Companion", NewGameMenu.getMenu());
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

    /** "Forest x3; Sol Ring": the Battlefield in few lines, so six players fit on a phone. */
    private static String describe(List<CompanionState.GameCard> cards) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CompanionState.GameCard card : cards) {
            counts.merge(card.name(), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((name, count) -> parts.add(count > 1 ? name + " x" + count : name));
        return String.join("; ", parts); // not commas, which card names contain
    }

    /** One name row per player, followed by their Battlefield as wrapped text. */
    private class PlayerList extends FScrollPane {
        void rebuild() {
            clear();
            for (CompanionState.PlayerState player : game.state().players()) {
                add(new FLabel.Builder().text(player.me() ? player.name() + " (Me)" : player.name())
                        .font(NAME_FONT).opaque().build());
                if (!player.battlefield().isEmpty()) {
                    FTextArea cards = new FTextArea(false, describe(player.battlefield()));
                    cards.setFont(CARD_FONT);
                    add(cards);
                }
            }
        }

        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            float y = 0;
            float width = visibleWidth - 2 * PADDING;
            for (FDisplayObject child : getChildren()) {
                float rowHeight = child instanceof FTextArea
                        ? ((FTextArea) child).getPreferredHeight(width)
                        : NAME_HEIGHT;
                child.setBounds(PADDING, y, width, rowHeight);
                y += rowHeight;
            }
            return new ScrollBounds(visibleWidth, y);
        }
    }
}
