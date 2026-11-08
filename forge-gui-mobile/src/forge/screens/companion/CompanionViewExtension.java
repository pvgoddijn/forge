package forge.screens.companion;

import forge.Forge;
import forge.assets.FSkinImage;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.share.CompanionClient;
import forge.menu.FDropDownMenu;
import forge.menu.FMenuItem;
import forge.screens.match.MatchController;
import forge.screens.match.MatchExtension;

/**
 * The match screen of a game shared by another device and joined here (AC-107, AC-108): read-only. The game menu has
 * only Leave; no Concede, no Add card button, no life tap, no card menu. What the screen's own buttons and keys still
 * send (OK, Ctrl+Z, a tap on a card) the host drops ({@code PeerGate}). Set before the client connects, so the match
 * screen {@code openView} builds is built with it.
 */
final class CompanionViewExtension implements MatchExtension {
    private final Runnable onLeft;
    private volatile CompanionClient client;
    private volatile boolean left;

    /** @param onLeft run on the UI thread once this device has left the game, by Leave or any other way out */
    CompanionViewExtension(Runnable onLeft) {
        this.onLeft = onLeft;
    }

    /** The connection to close on leaving; set before it connects. */
    void setClient(CompanionClient client) {
        this.client = client;
    }

    @Override
    public void addGameMenuItems(FDropDownMenu menu) {
        // the match screen closes through afterGameEnd, as at the end of any match; that calls matchLeft
        menu.addItem(new FMenuItem(Forge.getLocalizer().getMessage("lblLeave"), FSkinImage.CONCEDE,
                e -> MatchController.instance.afterGameEnd()));
    }

    @Override
    public boolean replacesGameMenu() {
        return true;
    }

    @Override
    public boolean followsRemoteGame() {
        return !left;
    }

    /** Closes the connection (no reconnect after it) and takes this extension off the match screen. Idempotent. */
    @Override
    public void matchLeft() {
        if (left) {
            return;
        }
        boolean current = MatchController.getExtension() == this; // asked before left, which hides it
        left = true;
        CompanionClient c = client;
        if (c != null) {
            c.close();
        }
        if (current) {
            MatchController.setExtension(null);
        }
        onLeft.run();
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
