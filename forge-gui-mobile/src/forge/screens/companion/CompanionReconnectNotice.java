package forge.screens.companion;

import com.badlogic.gdx.Input.Keys;
import forge.Forge;
import forge.toolbox.FOptionPane;

import java.util.List;

/**
 * Over a followed game's board while the host can't be reached (AC-111): says it's reconnecting, without counting
 * attempts or ever giving up, as Companion keeps trying for as long as the phone is in the game. "View board" hides it
 * (back does too) and the phone keeps trying; "Leave" leaves the game. Net play's reconnect dialog isn't used: it
 * counts to its last attempt and offers to give up. UI thread.
 */
final class CompanionReconnectNotice {
    private final Runnable onLeave;
    private FOptionPane pane;
    /** Set when the notice is taken away by {@link #dismiss}, so it's not taken for the user's choice. */
    private boolean dismissing;

    /** @param onLeave run when the user picks Leave */
    CompanionReconnectNotice(Runnable onLeave) {
        this.onLeave = onLeave;
    }

    /** Shows the notice, unless it's showing already. */
    void show() {
        if (pane != null) {
            return;
        }
        dismissing = false;
        pane = new FOptionPane(Forge.getLocalizer().getMessage("lblCompanionReconnecting"), null,
                Forge.getLocalizer().getMessage("lblReconnectTitle"), FOptionPane.WARNING_ICON, null,
                List.of(Forge.getLocalizer().getMessage("lblCompanionViewBoard"),
                        Forge.getLocalizer().getMessage("lblLeave")), 0, this::chosen) {
            @Override
            public boolean keyDown(int keyCode) {
                // back hides it, as View board does, rather than picking the last button (Leave)
                if (keyCode == Keys.ESCAPE || keyCode == Keys.BACK || keyCode == Keys.BUTTON_B) {
                    setResult(0);
                    return true;
                }
                return super.keyDown(keyCode);
            }
        };
        pane.show();
    }

    private void chosen(Integer choice) {
        pane = null;
        if (!dismissing && choice != null && choice == 1) {
            onLeave.run();
        }
    }

    /** Takes the notice away, if it shows: the host is back, or the game is over. */
    void dismiss() {
        FOptionPane shown = pane;
        if (shown == null) {
            return;
        }
        dismissing = true;
        pane = null;
        shown.hide();
    }
}
