package forge.screens.companion;

import forge.Forge;
import forge.gamemodes.companion.CompanionLogEntry;
import forge.gamemodes.companion.CompanionSnapshotSummary;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.share.ActiveGame;
import forge.gamemodes.companion.share.CompanionClient;
import forge.gamemodes.companion.share.CompanionResume;
import forge.gamemodes.companion.share.JoinCode;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.screens.FScreen;
import forge.screens.match.MatchController;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FTextArea;
import forge.util.Utils;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * A followed game carried on from Load game (slice 04, owner's decision 2026-11-09): rejoins its host with the code from
 * its {@code share.json}, nothing to type ({@link CompanionClient#rejoin}). Until the host's board arrives it shows N6:
 * how the link is, the last saved board as text and the history, read without writing anything to the copy
 * ({@link CompanionStore#loadFinalState}, {@link CompanionStore#loadFinalLog}), with "Stop following"; it keeps trying
 * for as long as it's open. The board's arrival ({@code openView}) opens the match screen over it, read-only
 * ({@link CompanionViewExtension}); leaving that comes back here. When the host ended the game, it says so, and the game
 * is one of the past games. A phone whose host left Companion (it missed the end) keeps trying, and "Stop following"
 * is its way out (plan Q29). UI thread.
 */
class CompanionRejoinScreen extends FScreen {
    private static final float PADDING = Utils.scale(5);

    private final CompanionStore store;
    private final ActiveGame game;
    private final JoinCode code;
    private final FTextArea lblStatus = add(new FTextArea(false));
    private final FButton btnStopFollowing = add(new FButton(
            Forge.getLocalizer().getMessage("lblCompanionStopFollowing")));
    private final FButton btnHistory = add(new FButton(Forge.getLocalizer().getMessage("lblCompanionHistory")));
    private final FLabel lblBoards = add(new FLabel.Builder()
            .text(Forge.getLocalizer().getMessage("lblCompanionLastSavedBoard")).build());
    private final FTextArea txtBoards;
    private final CompanionViewExtension extension;
    private CompanionClient client;
    /** Set once this follow is over on this screen (left, stopped, ended or failed), so late callbacks do nothing. */
    private boolean over;

    /** @param code the code from the game's {@code share.json} */
    CompanionRejoinScreen(CompanionStore store, ActiveGame game, JoinCode code) {
        super(Forge.getLocalizer().getMessage("lblCompanionFollowedGameTitle"));
        this.store = store;
        this.game = game;
        this.code = code;
        // read before the client writes anything: the board and history as the copy holds them now
        String boards;
        try {
            List<String> state = store.loadFinalState(game.id()).orElseGet(game.game().setup()::initialState);
            boards = CompanionPastGamesScreen.boardsText(CompanionSnapshotSummary.of(state, game.game().setup()));
        } catch (RuntimeException e) {
            boards = Forge.getLocalizer().getMessage("lblCompanionBoardsUnreadable") + "\n" + e.getMessage();
        }
        txtBoards = add(new FTextArea(false, boards));
        List<CompanionLogEntry> log;
        try {
            log = store.loadFinalLog(game.id());
        } catch (RuntimeException e) {
            e.printStackTrace(); // the board can still be shown; the history is then empty
            log = List.of();
        }
        List<CompanionLogEntry> history = log;
        btnHistory.setCommand(e -> Forge.openScreen(new CompanionHistoryScreen(history, game.game().seatNames())));
        btnStopFollowing.setCommand(e -> stopFollowing());
        extension = new CompanionViewExtension(store, this::left);
        showStatus(Forge.getLocalizer().getMessage(CompanionResume.statusKey(CompanionClient.Link.CONNECTING)));
    }

    /** Starts following again; call once, after opening the screen. */
    void start() {
        client = new CompanionClient(JoinGameScreen.playerName(), GuiBase.getInterface().getNewGuiGame(), code,
                Forge.getDeviceAdapter(), store);
        // before it connects: openView builds the match screen with it
        MatchController.setExtension(extension);
        extension.setClient(client);
        client.addLinkListener(link -> FThreads.invokeInEdtLater(() -> linkChanged(link)));
        CompanionClient joining = client;
        Thread t = new Thread(() -> {
            try {
                joining.rejoin(game.id()); // at most a few seconds; an unreachable host isn't an error, it's retried
            } catch (RuntimeException e) {
                // the copy couldn't be read: nothing to follow from
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                FThreads.invokeInEdtLater(() -> fail(Forge.getLocalizer().getMessage("lblCompanionCopyUnreadable",
                        why)));
            }
        }, "Companion-Rejoin");
        t.setDaemon(true);
        t.start();
    }

    private void linkChanged(CompanionClient.Link link) {
        if (over) {
            return;
        }
        // the board itself replaces this screen once it arrives; ended and stopped come through left()
        showStatus(Forge.getLocalizer().getMessage(CompanionResume.statusKey(link)));
    }

    private void fail(String message) {
        if (over) {
            return;
        }
        over = true;
        showStatus(message);
        extension.matchLeft(); // closes the client and takes the extension off
    }

    /**
     * The extension's leave callback: Leave on the match screen, Stop following, back, or with {@code notice} when the
     * host ended the game or the copy failed.
     */
    private void left(String notice) {
        if (over) {
            return;
        }
        over = true;
        showStatus(notice != null ? notice : Forge.getLocalizer().getMessage("lblCompanionLeft"));
        btnStopFollowing.setVisible(!store.isEnded(game.id())); // an ended game is a past game already
    }

    /** Leaves the host, and the copy is a past game, read-only. Then back to Load game. */
    private void stopFollowing() {
        extension.matchLeft();
        try {
            game.stopFollowing(store);
        } catch (UncheckedIOException e) {
            FOptionPane.showErrorDialog(Forge.getLocalizer().getMessage("lblCompanionStopFollowingFailed",
                    e.getMessage()));
            return;
        }
        Forge.back();
    }

    private void showStatus(String text) {
        lblStatus.setText(text);
        revalidate();
    }

    /** Back from this screen leaves the game, so no connection or extension outlives it. */
    @Override
    public void onClose(Consumer<Boolean> canCloseCallback) {
        extension.matchLeft();
        super.onClose(canCloseCallback);
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float x = PADDING;
        float w = width - 2 * PADDING;
        float y = startY + PADDING;
        float statusHeight = lblStatus.getPreferredHeight(w);
        lblStatus.setBounds(x, y, w, statusHeight);
        y += statusHeight + PADDING;
        float buttonHeight = Utils.AVG_FINGER_HEIGHT;
        float buttonWidth = (w - PADDING) / 2;
        btnHistory.setBounds(x, y, buttonWidth, buttonHeight);
        btnStopFollowing.setBounds(x + buttonWidth + PADDING, y, buttonWidth, buttonHeight);
        y += buttonHeight + PADDING;
        float labelHeight = lblBoards.getAutoSizeBounds().height;
        lblBoards.setBounds(x, y, w, labelHeight);
        y += labelHeight + PADDING;
        txtBoards.setBounds(x, y, w, Math.max(0, height - y - PADDING));
    }
}
