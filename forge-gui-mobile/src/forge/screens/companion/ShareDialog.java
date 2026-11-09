package forge.screens.companion;

import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Timer;
import forge.Forge;
import forge.assets.FSkinFont;
import forge.gamemodes.companion.share.CompanionShareHost;
import forge.toolbox.FDialog;
import forge.toolbox.FOptionPane;
import forge.toolbox.FTextArea;
import forge.util.Localizer;
import forge.util.Utils;

import java.util.List;

/**
 * Shares the running table (AC-101, text code only): starts {@code share} if it isn't yet, then shows a join code per
 * usable local address, labelled with its interface, and how many devices are connected. Stop sharing lets every
 * joined device go, for good: continuing the game later doesn't share it again (slice 04); Close keeps sharing. No
 * QR code (slice 08), no list of the phones (slice 05). UI thread.
 */
class ShareDialog extends FDialog {
    private static final float PADDING = Utils.scale(5);
    private static final FSkinFont FONT = FSkinFont.get(14);
    /** How often the connected count is read again while the dialog is open, in seconds. */
    private static final float REFRESH_SECONDS = 1;

    private final CompanionShareHost share;
    /** Told once the share was stopped for good, so the next "Share game…" makes a new one. */
    private final Runnable onStoppedForGood;
    private final FTextArea text = add(new FTextArea(false));
    private final Timer.Task refresh = new Timer.Task() {
        @Override
        public void run() {
            showCodes(); // libGDX runs timer tasks on the render thread
        }
    };
    private List<CompanionShareHost.Code> codes = List.of();

    /** @param onStoppedForGood run once Stop sharing stopped {@code share} for good */
    ShareDialog(CompanionShareHost share, Runnable onStoppedForGood) {
        super(Forge.getLocalizer().getMessage("lblCompanionShareTitle"), 2);
        this.share = share;
        this.onStoppedForGood = onStoppedForGood;
        text.setFont(FONT);
        text.setAlignment(Align.center);
        initButton(0, Forge.getLocalizer().getMessage("lblCompanionStopSharing"), e -> {
            // for good: the game's share state goes too, so continuing it later doesn't share it again
            share.stopForGood();
            onStoppedForGood.run();
            hide();
        });
        initButton(1, Forge.getLocalizer().getMessage("lblClose"), e -> hide());
    }

    @Override
    public void show() {
        try {
            share.start(); // returns at once when it's already started
        } catch (RuntimeException e) {
            // no free port, or the network refused to listen: nothing was shared
            FOptionPane.showErrorDialog(Forge.getLocalizer().getMessage("lblCompanionShareFailed", e.getMessage()));
            return;
        }
        codes = share.codes(); // the addresses are read once per opening; reopen to see a new network
        showCodes();
        super.show();
        Timer.schedule(refresh, REFRESH_SECONDS, REFRESH_SECONDS);
    }

    @Override
    public void setVisible(boolean visible) {
        if (!visible) {
            refresh.cancel(); // however the dialog goes: a button, or swiped away
        }
        super.setVisible(visible);
    }

    private void showCodes() {
        Localizer loc = Forge.getLocalizer();
        StringBuilder sb = new StringBuilder();
        if (codes.isEmpty()) {
            sb.append(loc.getMessage("lblCompanionNoNetwork"));
        } else {
            sb.append(loc.getMessage("lblCompanionShareHow"));
            for (CompanionShareHost.Code code : codes) {
                sb.append("\n\n").append(code.interfaceName()).append('\n').append(code.code().text());
            }
        }
        sb.append("\n\n").append(loc.getMessage("lblCompanionDevicesConnected", share.peerCount()));
        text.setText(sb.toString());
    }

    @Override
    protected float layoutAndGetHeight(float width, float maxHeight) {
        float w = width - 2 * PADDING;
        float h = Math.min(text.getPreferredHeight(w), maxHeight - 2 * PADDING);
        text.setBounds(PADDING, PADDING, w, h);
        return h + 2 * PADDING;
    }
}
