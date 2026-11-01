package forge.screens.companion;

import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gamemodes.companion.CardNameMatcher;
import forge.gamemodes.companion.CompanionGame;
import forge.gamemodes.companion.CompanionState;
import forge.toolbox.FComboBox;
import forge.toolbox.FDialog;
import forge.toolbox.FList;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a card by typing: choose the player, type, tap a match. "Add as typed" takes any text, which is how emblems
 * get in, as Forge has no list of them.
 */
public class CardSearchDialog extends FDialog {
    private static final float PADDING = Utils.scale(5);
    private static final int MAX_MATCHES = 30;
    private static CardNameMatcher matcher; // built once, on first use: it indexes every card name

    private final CompanionGame game;
    private final Runnable onAdded;
    private final FComboBox<String> cbPlayer = add(new FComboBox<>());
    private final FTextField txtSearch = add(new FTextField());
    private final FList<CardNameMatcher.Suggestion> lstMatches = add(new FList<>());

    public CardSearchDialog(CompanionGame game0, Runnable onAdded0) {
        super("Add card", 2);
        game = game0;
        onAdded = onAdded0;

        List<String> playerNames = new ArrayList<>();
        for (CompanionState.PlayerState player : game.state().players()) {
            playerNames.add(player.name());
        }
        cbPlayer.setItems(playerNames, playerNames.get(0));
        cbPlayer.setSelectedIndex(0);

        txtSearch.setGhostText("Card or token name");
        txtSearch.setLiveChangeEvents(true);
        txtSearch.setChangedHandler(e -> updateMatches());

        lstMatches.setListItemRenderer(new FList.ListItemRenderer<>() {
            @Override
            public float getItemHeight() {
                return Utils.scale(26);
            }

            @Override
            public boolean tap(Integer index, CardNameMatcher.Suggestion value, float x, float y, int count) {
                addCard(value.card());
                return true;
            }

            @Override
            public void drawValue(Graphics g, Integer index, CardNameMatcher.Suggestion value, FSkinFont font,
                    FSkinColor foreColor, FSkinColor backColor, boolean pressed, float x, float y, float w, float h) {
                g.drawText(value.card(), font, foreColor, x, y, w, h, false, com.badlogic.gdx.utils.Align.left, true);
            }
        });

        initButton(0, "Add as typed", e -> {
            String typed = txtSearch.getText().trim();
            if (!typed.isEmpty()) {
                addCard(typed);
            }
        });
        initButton(1, "Cancel", e -> hide());
    }

    private void updateMatches() {
        if (matcher == null) {
            matcher = CardNameMatcher.fromStaticData();
        }
        lstMatches.setListData(matcher.match(txtSearch.getText(), MAX_MATCHES));
        lstMatches.setScrollTop(0);
        lstMatches.revalidate();
    }

    private void addCard(String name) {
        game.addCard(cbPlayer.getSelectedIndex(), name);
        hide();
        onAdded.run();
    }

    @Override
    protected float layoutAndGetHeight(float width, float maxHeight) {
        float rowHeight = FTextField.getDefaultHeight();
        float w = width - 2 * PADDING;
        float y = PADDING;
        cbPlayer.setBounds(PADDING, y, w, rowHeight);
        y += rowHeight + PADDING;
        txtSearch.setBounds(PADDING, y, w, rowHeight);
        y += rowHeight + PADDING;
        float listHeight = Math.min(maxHeight - y - PADDING, Utils.scale(26) * 6);
        lstMatches.setBounds(PADDING, y, w, listHeight);
        return y + listHeight + PADDING;
    }
}
