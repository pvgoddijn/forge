package forge.screens.companion;

import com.badlogic.gdx.utils.Align;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gamemodes.companion.CardNameMatcher;
import forge.gamemodes.companion.CompanionGame;
import forge.gamemodes.companion.CompanionState;
import forge.gamemodes.companion.CompanionZone;
import forge.toolbox.FComboBox;
import forge.toolbox.FDialog;
import forge.toolbox.FList;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a card by typing: type, tap a match, check the player and zones, confirm. Text that matches nothing can still be
 * added as typed, which is how emblems get in, as Forge has no list of them.
 */
public class CardSearchDialog extends FDialog {
    private static final float PADDING = Utils.scale(5);
    private static final float ITEM_HEIGHT = Utils.scale(26);
    private static final int MAX_MATCHES = 30;
    private static final String ELSEWHERE = "Elsewhere";
    private static CardNameMatcher matcher; // built once, on first use: it indexes every card name

    private final CompanionGame game;
    private final Runnable onAdded;
    private final FComboBox<String> cbPlayer = add(new FComboBox<>());
    private final FComboBox<String> cbFrom = add(new FComboBox<>("From:"));
    private final FComboBox<String> cbTo = add(new FComboBox<>("To:"));
    private final FTextField txtSearch = add(new FTextField());
    private final FList<CardNameMatcher.Suggestion> lstMatches = add(new FList<>());
    private String selectedCard; // a name from the database; null while the text is only what was typed

    public CardSearchDialog(CompanionGame game0, Runnable onAdded0) {
        super("Add card", 2);
        game = game0;
        onAdded = onAdded0;

        List<String> playerNames = new ArrayList<>();
        for (CompanionState.PlayerState player : game.state().players()) {
            playerNames.add(player.name());
        }
        cbPlayer.setItems(playerNames, playerNames.get(0));

        List<String> fromNames = new ArrayList<>();
        fromNames.add(ELSEWHERE);
        List<String> toNames = new ArrayList<>();
        for (CompanionZone zone : CompanionZone.values()) {
            fromNames.add(CompanionGameScreen.zoneName(zone));
            toNames.add(CompanionGameScreen.zoneName(zone));
        }
        cbFrom.setItems(fromNames, ELSEWHERE);
        cbTo.setItems(toNames, CompanionGameScreen.zoneName(CompanionZone.BATTLEFIELD));

        txtSearch.setGhostText("Card or token name");
        txtSearch.setLiveChangeEvents(true);
        txtSearch.setChangedHandler(e -> updateMatches());

        lstMatches.setListItemRenderer(new FList.ListItemRenderer<>() {
            @Override
            public float getItemHeight() {
                return ITEM_HEIGHT;
            }

            @Override
            public boolean tap(Integer index, CardNameMatcher.Suggestion value, float x, float y, int count) {
                txtSearch.setText(value.card());
                select(value.card());
                return true;
            }

            @Override
            public void drawValue(Graphics g, Integer index, CardNameMatcher.Suggestion value, FSkinFont font,
                    FSkinColor foreColor, FSkinColor backColor, boolean pressed, float x, float y, float w, float h) {
                g.drawText(value.card(), font, foreColor, x, y, w, h, false, Align.left, true);
            }
        });

        initButton(0, "Add as typed", e -> addCard());
        initButton(1, "Cancel", e -> hide());
    }

    private void updateMatches() {
        if (matcher == null) {
            matcher = CardNameMatcher.fromStaticData();
        }
        List<CardNameMatcher.Suggestion> matches = matcher.match(txtSearch.getText(), MAX_MATCHES);
        lstMatches.setListData(matches);
        lstMatches.setScrollTop(0);
        lstMatches.revalidate();
        if (!matches.isEmpty() && matches.get(0).confidence() == 1) {
            select(matches.get(0).card()); // typed in full, so it needn't be tapped too
        } else if (selectedCard != null) {
            selectedCard = null;
            getButton(0).setText("Add as typed");
        }
    }

    /** Takes a database name as the card, and sets the zones this card defaults to (AC-18). */
    private void select(String card) {
        selectedCard = card;
        getButton(0).setText("Add");
        CompanionZone.AddDefaults defaults = CompanionZone.addDefaults(card);
        cbFrom.setSelectedItem(defaults.from() == null ? ELSEWHERE : CompanionGameScreen.zoneName(defaults.from()));
        cbTo.setSelectedItem(CompanionGameScreen.zoneName(defaults.to()));
    }

    private void addCard() {
        String name = selectedCard != null ? selectedCard : txtSearch.getText().trim();
        if (name.isEmpty()) {
            return;
        }
        int player = cbPlayer.getSelectedIndex();
        int fromIndex = cbFrom.getSelectedIndex();
        CompanionZone from = fromIndex == 0 ? null : CompanionZone.values()[fromIndex - 1];
        CompanionZone to = CompanionZone.values()[cbTo.getSelectedIndex()];
        if (CompanionGameScreen.save(() -> game.addCard(player, name, from, to))) {
            hide();
            onAdded.run();
        }
    }

    @Override
    protected float layoutAndGetHeight(float width, float maxHeight) {
        float rowHeight = FTextField.getDefaultHeight();
        float w = width - 2 * PADDING;
        float y = PADDING;
        cbPlayer.setBounds(PADDING, y, w, rowHeight);
        y += rowHeight + PADDING;
        float half = (w - PADDING) / 2;
        cbFrom.setBounds(PADDING, y, half, rowHeight);
        cbTo.setBounds(PADDING + half + PADDING, y, half, rowHeight);
        y += rowHeight + PADDING;
        txtSearch.setBounds(PADDING, y, w, rowHeight);
        y += rowHeight + PADDING;
        // tall enough that the drop-downs above, which open downward, stay on the screen
        float listHeight = Math.min(maxHeight - y - PADDING, ITEM_HEIGHT * 7);
        lstMatches.setBounds(PADDING, y, w, listHeight);
        return y + listHeight + PADDING;
    }
}
