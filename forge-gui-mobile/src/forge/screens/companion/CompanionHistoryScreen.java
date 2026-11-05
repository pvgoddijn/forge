package forge.screens.companion;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gamemodes.companion.CompanionHistory;
import forge.gamemodes.companion.CompanionLogEntry;
import forge.screens.FScreen;
import forge.toolbox.FComboBox;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.util.Utils;

import java.util.List;

/**
 * A game's history (AC-26), oldest at the top and scrolled to the newest at the bottom, with a filter by player
 * (AC-28). Undone rows stay, greyed and marked (AC-27). Read-only: it's built from log entries and seat names, so it
 * shows a live game's history as of its latest prompt and a past game's alike.
 */
public class CompanionHistoryScreen extends FScreen {
    private static final float PADDING = Utils.scale(5);
    private static final FSkinFont HEADER_FONT = FSkinFont.get(11);
    private static final FSkinFont TEXT_FONT = FSkinFont.get(14);
    private static final int TEXT_LINES = 2;

    private final CompanionHistory history;
    private final List<String> seats;
    private final FComboBox<String> cbxPlayer = add(new FComboBox<>());
    private final FList<CompanionHistory.Row> lstRows = add(new FList<>());
    private final FLabel lblEmpty = add(new FLabel.Builder().align(Align.center)
            .text(Forge.getLocalizer().getMessage("lblCompanionHistoryEmpty")).build());
    private boolean scrollToNewest = true;

    /**
     * @param log   the game's log entries, oldest first, as {@link forge.gamemodes.companion.CompanionStore#loadLog}
     *              reads them
     * @param seats the game's seat names, for the filter
     */
    public CompanionHistoryScreen(List<CompanionLogEntry> log, List<String> seats) {
        super(Forge.getLocalizer().getMessage("lblCompanionHistory"));
        this.history = CompanionHistory.of(log);
        this.seats = List.copyOf(seats);

        // by index, as a seat could be named like the "everyone" item
        cbxPlayer.addItem(Forge.getLocalizer().getMessage("lblCompanionEveryone"));
        for (String seat : this.seats) {
            cbxPlayer.addItem(seat);
        }
        cbxPlayer.setChangedHandler(e -> showRows());

        lstRows.setListItemRenderer(new RowRenderer());
        showRows();
    }

    private void showRows() {
        int index = cbxPlayer.getSelectedIndex();
        CompanionHistory shown = index <= 0 ? history : history.filter(seats.get(index - 1));
        lstRows.setListData(shown.rows());
        lblEmpty.setVisible(shown.rows().isEmpty());
        scrollToNewest = true;
        revalidate();
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float x = PADDING;
        float y = startY + PADDING;
        float w = width - 2 * PADDING;
        cbxPlayer.setBounds(x, y, w, cbxPlayer.getHeight());
        y += cbxPlayer.getHeight() + PADDING;
        lstRows.setBounds(0, y, width, height - y);
        lblEmpty.setBounds(x, y, w, lblEmpty.getAutoSizeBounds().height + 2 * PADDING);
        if (scrollToNewest && lstRows.getHeight() > 0) {
            lstRows.scrollToBottom(); // the newest is what the operator looks for
            scrollToNewest = false;
        }
    }

    /** "Turn 3, Ann" over the row's text; an undone row is greyed and says so. */
    private static String header(CompanionHistory.Row row) {
        String header;
        if (row.turn() <= 0) {
            header = "";
        } else if (row.active() == null) {
            header = Forge.getLocalizer().getMessage("lblCompanionTurn", row.turn());
        } else {
            header = Forge.getLocalizer().getMessage("lblCompanionTurnOf", row.turn(), row.active());
        }
        if (row.undone()) {
            String undone = Forge.getLocalizer().getMessage("lblCompanionUndone");
            header = header.isEmpty() ? undone : header + " · " + undone;
        }
        return header;
    }

    private static class RowRenderer extends FList.ListItemRenderer<CompanionHistory.Row> {
        @Override
        public float getItemHeight() {
            return HEADER_FONT.getLineHeight() + TEXT_LINES * TEXT_FONT.getLineHeight() + 3 * FList.PADDING;
        }

        @Override
        public boolean tap(Integer index, CompanionHistory.Row value, float x, float y, int count) {
            return false; // read-only
        }

        @Override
        public void drawValue(Graphics g, Integer index, CompanionHistory.Row value, FSkinFont font,
                              FSkinColor foreColor, FSkinColor backColor, boolean pressed, float x, float y, float w,
                              float h) {
            FSkinColor color = value.undone() ? foreColor.alphaColor(0.5f) : foreColor;
            float headerHeight = HEADER_FONT.getLineHeight();
            g.drawText(header(value), HEADER_FONT, foreColor.alphaColor(value.undone() ? 0.4f : 0.75f), x, y, w,
                    headerHeight, false, Align.left, false);
            float textY = y + headerHeight + FList.PADDING;
            g.drawText(value.text(), TEXT_FONT, color, x, textY, w, h - (textY - y), true, Align.left, false);
        }
    }
}
