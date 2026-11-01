package forge.screens.companion;

import forge.Forge;
import forge.gamemodes.companion.CompanionAction;
import forge.gamemodes.companion.CompanionGame;
import forge.gamemodes.companion.CompanionState;
import forge.gamemodes.companion.CompanionStore;
import forge.gui.FThreads;
import forge.localinstance.properties.ForgeConstants;
import forge.screens.LaunchScreen;
import forge.screens.home.NewGameMenu;
import forge.toolbox.FButton;
import forge.toolbox.FCheckBox;
import forge.toolbox.FOptionPane;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.io.File;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Sets up a tracked game: players in seat order, and which one is Me. Goes straight to the active game if there is one. */
public class CompanionSetupScreen extends LaunchScreen {
    private static final float PADDING = Utils.scale(5);

    private final CompanionStore store = new CompanionStore(new File(ForgeConstants.USER_DIR, "companion"));
    private final FTextField[] names = new FTextField[CompanionState.MAX_PLAYERS];
    private final FCheckBox[] mes = new FCheckBox[CompanionState.MAX_PLAYERS];
    private final FButton[] ups = new FButton[CompanionState.MAX_PLAYERS];
    private final FButton btnFewer = add(new FButton("-"));
    private final FButton btnMore = add(new FButton("+"));
    private int playerCount = 4;

    public CompanionSetupScreen() {
        super(null, NewGameMenu.getMenu());

        for (int i = 0; i < CompanionState.MAX_PLAYERS; i++) {
            final int row = i;
            names[i] = add(new FTextField("Player " + (i + 1)));
            mes[i] = add(new FCheckBox("Me", i == 0));
            mes[i].setCommand(e -> setMe(row));
            ups[i] = add(new FButton("^"));
            ups[i].setCommand(e -> swapWithRowAbove(row));
        }
        btnFewer.setCommand(e -> setPlayerCount(playerCount - 1));
        btnMore.setCommand(e -> setPlayerCount(playerCount + 1));
        updateRows();
    }

    @Override
    public void onActivate() {
        super.onActivate();
        Optional<CompanionGame> active = store.loadActive();
        List<File> unreadable = store.unreadableFiles();
        // later, because this runs while the screen change that got us here is still happening
        FThreads.invokeInEdtLater(() -> {
            active.ifPresent(this::openGame);
            // after opening the game, as a dialog belongs to the screen that was current when it was shown
            for (File file : unreadable) {
                FOptionPane.showErrorDialog("A saved game couldn't be read, so it wasn't resumed. Its file is kept at:\n" + file);
            }
        });
    }

    private void setMe(int row) {
        for (int i = 0; i < mes.length; i++) {
            mes[i].setSelected(i == row);
        }
    }

    private void swapWithRowAbove(int row) {
        String name = names[row].getText();
        names[row].setText(names[row - 1].getText());
        names[row - 1].setText(name);
        boolean me = mes[row].isSelected();
        mes[row].setSelected(mes[row - 1].isSelected());
        mes[row - 1].setSelected(me);
    }

    private void setPlayerCount(int count) {
        playerCount = count;
        for (int i = playerCount; i < mes.length; i++) {
            if (mes[i].isSelected()) {
                setMe(0); // a removed seat can't stay Me
            }
        }
        updateRows();
        revalidate();
    }

    private void updateRows() {
        for (int i = 0; i < CompanionState.MAX_PLAYERS; i++) {
            boolean visible = i < playerCount;
            names[i].setVisible(visible);
            mes[i].setVisible(visible);
            ups[i].setVisible(visible && i > 0);
        }
        btnFewer.setEnabled(playerCount > CompanionState.MIN_PLAYERS);
        btnMore.setEnabled(playerCount < CompanionState.MAX_PLAYERS);
    }

    @Override
    protected void doLayoutAboveBtnStart(float startY, float width, float height) {
        float rowHeight = FTextField.getDefaultHeight();
        float buttonWidth = rowHeight * 1.2f;
        float meWidth = rowHeight * 2.2f;
        float y = startY + PADDING;
        for (int i = 0; i < CompanionState.MAX_PLAYERS; i++) {
            names[i].setBounds(PADDING, y, width - meWidth - buttonWidth - 4 * PADDING, rowHeight);
            mes[i].setBounds(names[i].getRight() + PADDING, y, meWidth, rowHeight);
            ups[i].setBounds(mes[i].getRight() + PADDING, y, buttonWidth, rowHeight);
            if (i < playerCount) {
                y += rowHeight + PADDING;
            }
        }
        btnFewer.setBounds(PADDING, y, buttonWidth, rowHeight);
        btnMore.setBounds(btnFewer.getRight() + PADDING, y, buttonWidth, rowHeight);
    }

    @Override
    protected void startMatch() {
        List<CompanionAction.PlayerSetup> players = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            String name = names[i].getText().trim();
            if (name.isEmpty()) {
                FOptionPane.showErrorDialog("Every player needs a name.");
                return;
            }
            for (CompanionAction.PlayerSetup other : players) {
                if (other.name().equalsIgnoreCase(name)) {
                    FOptionPane.showErrorDialog("Two players are named " + name + ". Every player needs a different name.");
                    return;
                }
            }
            players.add(new CompanionAction.PlayerSetup(name, mes[i].isSelected()));
        }
        try {
            openGame(store.createGame(players));
        } catch (UncheckedIOException e) {
            FOptionPane.showErrorDialog("The game couldn't be saved, so it wasn't started.\n" + e.getMessage());
        }
    }

    private void openGame(CompanionGame game) {
        // replaces this screen, so going back from the game doesn't land here and bounce straight back in
        Forge.openScreen(new CompanionGameScreen(game), true);
    }
}
