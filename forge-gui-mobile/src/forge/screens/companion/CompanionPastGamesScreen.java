package forge.screens.companion;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gamemodes.companion.CompanionLogEntry;
import forge.gamemodes.companion.CompanionSnapshotSummary;
import forge.gamemodes.companion.CompanionStore;
import forge.screens.FScreen;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.toolbox.FTextArea;
import forge.util.Localizer;
import forge.util.Utils;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * The games that have ended, newest first (AC-30), each with its date and players. Opening one shows its final boards
 * and, from there, its history. Everything here is read-only: a past game is read from its files, never started.
 */
public class CompanionPastGamesScreen extends FScreen {
    private static final float PADDING = Utils.scale(5);
    private static final FSkinFont DATE_FONT = FSkinFont.get(11);
    private static final FSkinFont SEATS_FONT = FSkinFont.get(14);

    private final CompanionStore store;
    private final FList<CompanionStore.Ended> lstGames = add(new FList<>());
    private final FLabel lblEmpty = add(new FLabel.Builder().align(Align.center)
            .text(Forge.getLocalizer().getMessage("lblCompanionNoPastGames")).build());

    public CompanionPastGamesScreen(CompanionStore store) {
        super(Forge.getLocalizer().getMessage("lblCompanionPastGames"));
        this.store = store;
        lstGames.setListItemRenderer(new GameRenderer());
    }

    @Override
    public void onActivate() {
        super.onActivate();
        List<CompanionStore.Ended> games = store.listEnded();
        lstGames.setListData(games);
        lblEmpty.setVisible(games.isEmpty());
        revalidate();
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        lstGames.setBounds(0, startY, width, height - startY);
        lblEmpty.setBounds(PADDING, startY + PADDING, width - 2 * PADDING,
                lblEmpty.getAutoSizeBounds().height + 2 * PADDING);
    }

    private static String date(CompanionStore.Ended game) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(game.endedAt()));
    }

    private void open(CompanionStore.Ended game) {
        // the state first: reading it settles the log (CompanionStore.loadState), so the history matches the boards
        String boards;
        try {
            List<String> state = store.loadState(game.id()).orElseGet(game.setup()::initialState);
            boards = boardsText(CompanionSnapshotSummary.of(state, game.setup()));
        } catch (RuntimeException e) {
            boards = Forge.getLocalizer().getMessage("lblCompanionBoardsUnreadable") + "\n" + e.getMessage();
        }
        List<CompanionLogEntry> log;
        try {
            log = store.loadLog(game.id());
        } catch (RuntimeException e) {
            e.printStackTrace(); // the boards can still be shown; the history is then empty
            log = List.of();
        }
        Forge.openScreen(new PastGameScreen(date(game), boards, log, game.seatNames()));
    }

    /** The boards as plain text: each seat's life and hand, then its zones that have cards. */
    static String boardsText(CompanionSnapshotSummary summary) {
        Localizer localizer = Forge.getLocalizer();
        StringBuilder text = new StringBuilder();
        if (summary.turn() > 0) {
            text.append(summary.active() == null ? localizer.getMessage("lblCompanionTurn", summary.turn())
                    : localizer.getMessage("lblCompanionTurnOf", summary.turn(), summary.active())).append("\n");
        }
        for (CompanionSnapshotSummary.Seat seat : summary.seats()) {
            text.append("\n").append(localizer.getMessage("lblCompanionSeatLife", seat.name(), seat.life()))
                    .append("\n");
            String hand = localizer.getMessage("lblCompanionHiddenInHand", seat.hiddenInHand());
            if (!seat.knownInHand().isEmpty()) {
                hand += ", " + String.join(", ", seat.knownInHand());
            }
            appendZone(text, "lblCompanionHand", List.of(hand));
            appendZone(text, "lblCompanionBattlefield", seat.battlefield());
            appendZone(text, "lblCompanionGraveyard", seat.graveyard());
            appendZone(text, "lblCompanionExile", seat.exile());
            appendZone(text, "lblCompanionCommand", seat.command());
        }
        return text.toString();
    }

    private static void appendZone(StringBuilder text, String key, List<String> cards) {
        if (!cards.isEmpty()) {
            text.append("  ").append(Forge.getLocalizer().getMessage(key)).append(": ")
                    .append(String.join(", ", cards)).append("\n");
        }
    }

    private class GameRenderer extends FList.ListItemRenderer<CompanionStore.Ended> {
        @Override
        public float getItemHeight() {
            return DATE_FONT.getLineHeight() + SEATS_FONT.getLineHeight() + 3 * FList.PADDING;
        }

        @Override
        public boolean tap(Integer index, CompanionStore.Ended value, float x, float y, int count) {
            open(value);
            return true;
        }

        @Override
        public void drawValue(Graphics g, Integer index, CompanionStore.Ended value, FSkinFont font,
                              FSkinColor foreColor, FSkinColor backColor, boolean pressed, float x, float y, float w,
                              float h) {
            float dateHeight = DATE_FONT.getLineHeight();
            g.drawText(date(value), DATE_FONT, foreColor.alphaColor(0.75f), x, y, w, dateHeight, false, Align.left,
                    false);
            float seatsY = y + dateHeight + FList.PADDING;
            g.drawText(String.join(", ", value.seatNames()), SEATS_FONT, foreColor, x, seatsY, w, h - (seatsY - y),
                    false, Align.left, false);
        }
    }

    /** One past game: its final boards as a plain list, and a button to its history. Nothing on it changes the game. */
    private static class PastGameScreen extends FScreen {
        private final FButton btnHistory = add(new FButton(Forge.getLocalizer().getMessage("lblCompanionHistory")));
        private final FLabel lblBoards = add(new FLabel.Builder()
                .text(Forge.getLocalizer().getMessage("lblCompanionFinalBoards")).build());
        private final FTextArea txtBoards;

        PastGameScreen(String caption, String boards, List<CompanionLogEntry> log, List<String> seats) {
            super(caption);
            txtBoards = add(new FTextArea(false, boards));
            btnHistory.setCommand(e -> Forge.openScreen(new CompanionHistoryScreen(log, seats)));
        }

        @Override
        protected void doLayout(float startY, float width, float height) {
            float x = PADDING;
            float w = width - 2 * PADDING;
            float y = startY + PADDING;
            float buttonHeight = Utils.AVG_FINGER_HEIGHT;
            btnHistory.setBounds(x, y, w, buttonHeight);
            y += buttonHeight + PADDING;
            float labelHeight = lblBoards.getAutoSizeBounds().height;
            lblBoards.setBounds(x, y, w, labelHeight);
            y += labelHeight + PADDING;
            txtBoards.setBounds(x, y, w, height - y - PADDING);
        }
    }
}
