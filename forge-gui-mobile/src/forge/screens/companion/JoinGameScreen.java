package forge.screens.companion;

import com.badlogic.gdx.utils.Align;
import forge.Forge;
import forge.assets.FSkinFont;
import forge.gamemodes.companion.share.CompanionClient;
import forge.gamemodes.companion.share.JoinCode;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.ILobbyListener;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.FScreen;
import forge.screens.match.MatchController;
import forge.toolbox.FButton;
import forge.toolbox.FTextArea;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Joins a game another device shares (AC-102, typed code only): the code, Join, then the match screen once the host's
 * game arrives ({@code openView} opens it). Read-only on this device ({@link CompanionViewExtension}). Each failure
 * says what went wrong: not a code, nothing reachable there, the host refused the code, the host left first, or the
 * game didn't arrive in time. Back from this screen ends a join under way.
 */
public class JoinGameScreen extends FScreen {
    private static final float PADDING = Utils.scale(5);
    private static final FSkinFont CODE_FONT = FSkinFont.get(18);
    private static final String DEFAULT_NAME = "Companion";

    private final FTextArea lblHow = add(new FTextArea(false,
            Forge.getLocalizer().getMessage("lblCompanionJoinHow")));
    private final FTextField txtCode = add(new FTextField());
    private final FButton btnJoin = add(new FButton(Forge.getLocalizer().getMessage("lblCompanionJoin")));
    private final FTextArea lblStatus = add(new FTextArea(false));
    /** The join under way or on screen, or null. UI thread. */
    private Attempt attempt;

    public JoinGameScreen() {
        super(Forge.getLocalizer().getMessage("lblCompanionJoinGame"));
        txtCode.setFont(CODE_FONT);
        txtCode.setAlignment(Align.center);
        txtCode.setGhostText("XXXX-XXXX-XXXX-XXXX");
        lblStatus.setAlignment(Align.center);
        btnJoin.setCommand(e -> {
            if (attempt == null) {
                join();
            } else {
                attempt.cancel();
            }
        });
    }

    private void join() {
        Optional<JoinCode> code = JoinCode.parse(txtCode.getText());
        if (code.isEmpty()) {
            showStatus(Forge.getLocalizer().getMessage("lblCompanionNotACode"));
            return;
        }
        attempt = new Attempt(code.get());
        btnJoin.setText(Forge.getLocalizer().getMessage("lblCancel"));
        showStatus(Forge.getLocalizer().getMessage("lblCompanionConnecting", where(code.get())));
        attempt.start();
    }

    private void showStatus(String text) {
        lblStatus.setText(text);
        revalidate();
    }

    private static String where(JoinCode code) {
        return code.address().getHostAddress() + ":" + code.port();
    }

    private static String playerName() {
        String name = FModel.getPreferences().getPref(FPref.PLAYER_NAME);
        return name == null || name.isBlank() ? DEFAULT_NAME : name.trim();
    }

    /**
     * One try at joining, from Join to leaving. The match GUI is reset and the extension set before the client
     * connects, as {@code openView} builds the match screen with it. Network callbacks only post to the UI thread.
     */
    private final class Attempt {
        private final JoinCode code;
        private final CompanionViewExtension extension = new CompanionViewExtension(this::ended);
        private final CompanionClient client;
        /** Set once this try is over on this screen (cancelled, failed or left), so late callbacks do nothing. */
        private boolean over;

        Attempt(JoinCode code) {
            this.code = code;
            IGuiGame gui = GuiBase.getInterface().getNewGuiGame();
            MatchController.setExtension(extension);
            client = new CompanionClient(playerName(), gui, code, Forge.getDeviceAdapter()); // joins over Wi-Fi with mobile data on
            extension.setClient(client);
            // before the host seats this device, a closed connection isn't retried: the client tells its listeners
            client.addLobbyListener(new ILobbyListener() {
                @Override
                public void message(String source, String message, ChatMessage.MessageType type) {
                }

                @Override
                public void update(GameLobbyData state, int slot) {
                }

                @Override
                public void close() {
                    // welcome is read before the close on the same connection, so it's settled by now
                    String why = client.attachTimedOut() ? "lblCompanionGameDidntArrive"
                            : client.welcomedTo() != null ? "lblCompanionHostLeft" : "lblCompanionJoinRefused";
                    FThreads.invokeInEdtLater(() -> fail(Forge.getLocalizer().getMessage(why)));
                }

                @Override
                public ClientGameLobby getLobby() {
                    return null;
                }
            });
        }

        void start() {
            Thread t = new Thread(() -> {
                try {
                    client.connect(); // blocks until connected; a refused or unreachable host throws, unchecked
                    FThreads.invokeInEdtLater(this::connected);
                } catch (Exception e) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    FThreads.invokeInEdtLater(() -> fail(Forge.getLocalizer().getMessage("lblCompanionCantReach",
                            where(code), reason)));
                }
            }, "Companion-Join");
            t.setDaemon(true);
            t.start();
        }

        private void connected() {
            if (!over && attempt == this) {
                // the host sends the game at its next prompt, so this can take a moment
                showStatus(Forge.getLocalizer().getMessage("lblCompanionWaitingForGame"));
            }
        }

        private void fail(String message) {
            if (over || attempt != this) {
                return;
            }
            over = true;
            showStatus(message);
            extension.matchLeft(); // closes the client and takes the extension off; ended() then resets the button
        }

        void cancel() {
            if (over) {
                return;
            }
            over = true;
            showStatus(Forge.getLocalizer().getMessage("lblCompanionJoinCancelled"));
            extension.matchLeft();
        }

        /** The extension's leave callback, on the UI thread: by Leave on the match screen, or after fail or cancel. */
        private void ended() {
            if (!over) {
                over = true;
                showStatus(Forge.getLocalizer().getMessage("lblCompanionLeft"));
            }
            if (attempt == this) {
                attempt = null;
                btnJoin.setText(Forge.getLocalizer().getMessage("lblCompanionJoin"));
            }
        }
    }

    /** Back from this screen ends a join still under way, so no connection or extension outlives it. */
    @Override
    public void onClose(Consumer<Boolean> canCloseCallback) {
        if (attempt != null) {
            attempt.cancel();
        }
        super.onClose(canCloseCallback);
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float x = PADDING;
        float w = width - 2 * PADDING;
        float y = startY + PADDING;
        float howHeight = lblHow.getPreferredHeight(w);
        lblHow.setBounds(x, y, w, howHeight);
        y += howHeight + PADDING;
        float codeHeight = FTextField.getDefaultHeight(CODE_FONT);
        txtCode.setBounds(x, y, w, codeHeight);
        y += codeHeight + PADDING;
        float buttonHeight = FTextField.getDefaultHeight();
        btnJoin.setBounds(x, y, w, buttonHeight);
        y += buttonHeight + PADDING;
        lblStatus.setBounds(x, y, w, Math.max(0, height - y - PADDING));
    }
}
