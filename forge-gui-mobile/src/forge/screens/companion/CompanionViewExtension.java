package forge.screens.companion;

import forge.Forge;
import forge.assets.FSkinImage;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CompanionLogEntry;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.share.CompanionClient;
import forge.gamemodes.companion.share.CompanionResume;
import forge.gui.FThreads;
import forge.menu.FDropDownMenu;
import forge.menu.FMenuItem;
import forge.screens.match.MatchController;
import forge.screens.match.MatchExtension;
import forge.toolbox.FOptionPane;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The match screen of a game shared by another device and joined here (AC-107, AC-108): read-only. The game menu has
 * only Leave; no Concede, no Add card button, no life tap, no card menu. What the screen's own buttons and keys still
 * send (OK, Ctrl+Z, a tap on a card) the host drops ({@code PeerGate}). Set before the client connects, so the match
 * screen {@code openView} builds is built with it.
 *
 * <p>The game menu's History shows this device's own copy of the game's log ({@link CompanionStore#loadLog}, which
 * writes nothing), with the player filter (AC-107). When the host ends the game, this device leaves the match screen
 * and says so; the copy is then one of its past games (AC-113). When the copy can't be written, it stops following
 * and says so, rather than going on with a copy that no longer matches the host's.
 *
 * <p>While the host can't be reached, the board stays and {@link CompanionReconnectNotice} says it's reconnecting
 * (AC-111, slice 04); it goes once the host is back, whose attach then brings the board up to date. Before the board
 * arrives the screen that joined says how the link is (N6 for a rejoin), so the notice shows only over the board.
 */
final class CompanionViewExtension implements MatchExtension {
    private final CompanionStore store;
    private final Consumer<String> onLeft;
    private volatile CompanionClient client;
    private volatile boolean left;
    /** Set once the copy has ended or failed, so only the first of them leaves. */
    private final AtomicBoolean stopping = new AtomicBoolean();
    /** The notice for {@link #onLeft} when the copy makes this device leave, else null. UI thread. */
    private String leaveNotice;
    /** Over the board while the host can't be reached; Leave there leaves as the menu's Leave does. UI thread. */
    private final CompanionReconnectNotice reconnectNotice = new CompanionReconnectNotice(
            () -> MatchController.instance.afterGameEnd());

    /**
     * @param store  where the client keeps the copy of the game
     * @param onLeft run on the UI thread once this device has left the game, by Leave or any other way out; given
     *               the notice shown when the copy made it leave (the host ended the game, or the copy failed), else
     *               null
     */
    CompanionViewExtension(CompanionStore store, Consumer<String> onLeft) {
        this.store = store;
        this.onLeft = onLeft;
    }

    /** The connection to close on leaving; set before it connects. Listens to its copy for the end and failures. */
    void setClient(CompanionClient client) {
        this.client = client;
        client.addLinkListener(link -> FThreads.invokeInEdtLater(() -> linkChanged(link)));
        client.addCopyListener(new CompanionClient.CopyListener() {
            @Override
            public void ended(String game) {
                leaveWithNotice(Forge.getLocalizer().getMessage("lblCompanionHostEnded"));
            }

            @Override
            public void failed(String game, RuntimeException e) {
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                leaveWithNotice(Forge.getLocalizer().getMessage("lblCompanionCopyFailed", why));
            }
        });
    }

    /** The board shows the reconnect notice while the host can't be reached, and only then. UI thread. */
    private void linkChanged(CompanionClient.Link link) {
        if (!left && CompanionResume.showsReconnectNotice(link, boardShown())) {
            reconnectNotice.show();
        } else {
            reconnectNotice.dismiss();
        }
    }

    /** Whether this game's board is on screen: the match screen, current, while this extension is set. */
    private static boolean boardShown() {
        return MatchController.getView() != null && Forge.getCurrentScreen() == MatchController.getView();
    }

    @Override
    public boolean showsOwnReconnectState() {
        return true;
    }

    /** The copy's thread: leaves the game on the UI thread, once, and shows {@code notice}. */
    private void leaveWithNotice(String notice) {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        FThreads.invokeInEdtLater(() -> {
            if (!left) {
                leaveNotice = notice;
                if (MatchController.getView() != null && Forge.getCurrentScreen() == MatchController.getView()) {
                    MatchController.instance.afterGameEnd(); // as Leave: closes the match screen, then matchLeft
                }
                matchLeft(); // when the match screen wasn't open yet, or wasn't left that way
            }
            // also when this device had just left (what came before is still written): it's still news. After
            // leaving, so the notice belongs to the screen this device is back on
            FOptionPane.showMessageDialog(notice);
        });
    }

    @Override
    public void addGameMenuItems(FDropDownMenu menu) {
        menu.addItem(new FMenuItem(Forge.getLocalizer().getMessage("lblCompanionHistory"), e -> showHistory()));
        // the match screen closes through afterGameEnd, as at the end of any match; that calls matchLeft
        menu.addItem(new FMenuItem(Forge.getLocalizer().getMessage("lblLeave"), FSkinImage.CONCEDE,
                e -> MatchController.instance.afterGameEnd()));
    }

    @Override
    public boolean replacesGameMenu() {
        return true;
    }

    /**
     * Only while this device still follows the host: once it left, or the connection is gone for good, the extension
     * no longer applies, so it can't leak into a later net play game ({@code MatchController.getExtension}).
     */
    @Override
    public boolean followsRemoteGame() {
        CompanionClient c = client;
        return !left && c != null && c.isActive();
    }

    /** This device's own copy of the log: what the host sent, written here before it's shown. UI thread. */
    private void showHistory() {
        CompanionClient c = client;
        String game = c == null ? null : c.welcomedTo();
        if (game == null) {
            return; // nothing followed yet: the menu shows only once the game has arrived, after the welcome
        }
        List<CompanionLogEntry> log;
        List<String> seats;
        try {
            log = store.loadLog(game);
            // not Stream.toList: Android has it from API 34 only, and D8 doesn't backport it
            seats = store.loadSetup(game).seats().stream().map(CompanionSetup.Seat::name)
                    .collect(Collectors.toList());
        } catch (RuntimeException e) {
            FOptionPane.showErrorDialog(Forge.getLocalizer().getMessage("lblCompanionHistoryUnreadable",
                    String.valueOf(e.getMessage())));
            return;
        }
        Forge.openScreen(new CompanionHistoryScreen(log, seats));
    }

    /** Closes the connection (no reconnect after it) and takes this extension off the match screen. Idempotent. */
    @Override
    public void matchLeft() {
        if (left) {
            return;
        }
        left = true;
        reconnectNotice.dismiss();
        CompanionClient c = client;
        if (c != null) {
            c.close();
        }
        MatchController.clearExtension(this);
        onLeft.accept(leaveNotice);
    }

    @Override
    public boolean offersConcede() {
        return false;
    }

    @Override
    public boolean showsHandsAtStart() {
        return false; // the host's hands aren't known either: the rows would only show card backs
    }

    @Override
    public boolean showsTopPrompt() {
        return false; // as on the host: one prompt, the right way up
    }

    @Override
    public boolean tapLife(PlayerView player) {
        return true; // handled: nothing happens, rather than a player selection sent to the host to be dropped
    }
}
