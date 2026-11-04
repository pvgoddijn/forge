package forge.screens.companion;

import forge.assets.FSkinImage;
import forge.gamemodes.companion.CompanionGameState;
import forge.gamemodes.companion.CompanionMatch;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.match.HostedMatch;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.menu.FDropDownMenu;
import forge.menu.FMenuItem;
import forge.screens.LaunchScreen;
import forge.screens.LoadingOverlay;
import forge.screens.home.NewGameMenu;
import forge.screens.match.MatchController;
import forge.screens.match.MatchExtension;
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

/**
 * Sets up a tracked game: players in seat order, and which one is Me. The game itself is a real match on Forge's match
 * screen, with every seat answered by the operator. Goes straight to the active game if there is one.
 */
public class CompanionSetupScreen extends LaunchScreen {
    private static final float PADDING = Utils.scale(5);

    private final CompanionStore store = new CompanionStore(new File(ForgeConstants.USER_DIR, "companion"));
    private final FTextField[] names = new FTextField[CompanionSetup.MAX_PLAYERS];
    private final FCheckBox[] mes = new FCheckBox[CompanionSetup.MAX_PLAYERS];
    private final FButton[] ups = new FButton[CompanionSetup.MAX_PLAYERS];
    private final FButton btnFewer = add(new FButton("-"));
    private final FButton btnMore = add(new FButton("+"));
    private int playerCount = 4;

    public CompanionSetupScreen() {
        super(null, NewGameMenu.getMenu());

        for (int i = 0; i < CompanionSetup.MAX_PLAYERS; i++) {
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
        Optional<CompanionStore.Saved> active = store.loadActive();
        List<File> unreadable = store.unreadableFiles();
        // later, because this runs while the screen change that got us here is still happening
        FThreads.invokeInEdtLater(() -> {
            active.ifPresent(saved -> openGame(saved.id(), saved.setup()));
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
        for (int i = 0; i < CompanionSetup.MAX_PLAYERS; i++) {
            boolean visible = i < playerCount;
            names[i].setVisible(visible);
            mes[i].setVisible(visible);
            ups[i].setVisible(visible && i > 0);
        }
        btnFewer.setEnabled(playerCount > CompanionSetup.MIN_PLAYERS);
        btnMore.setEnabled(playerCount < CompanionSetup.MAX_PLAYERS);
    }

    @Override
    protected void doLayoutAboveBtnStart(float startY, float width, float height) {
        float rowHeight = FTextField.getDefaultHeight();
        float buttonWidth = rowHeight * 1.2f;
        float meWidth = rowHeight * 2.2f;
        float y = startY + PADDING;
        for (int i = 0; i < CompanionSetup.MAX_PLAYERS; i++) {
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
        List<CompanionSetup.Seat> seats = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            String name = names[i].getText().trim();
            if (name.isEmpty()) {
                FOptionPane.showErrorDialog("Every player needs a name.");
                return;
            }
            for (CompanionSetup.Seat other : seats) {
                if (other.name().equalsIgnoreCase(name)) {
                    FOptionPane.showErrorDialog("Two players are named " + name + ". Every player needs a different name.");
                    return;
                }
            }
            seats.add(new CompanionSetup.Seat(name, mes[i].isSelected()));
        }
        CompanionSetup setup = CompanionSetup.withDefaults(seats);
        String id;
        try {
            id = store.create(setup);
        } catch (UncheckedIOException e) {
            FOptionPane.showErrorDialog("The game couldn't be saved, so it wasn't started.\n" + e.getMessage());
            return;
        }
        openGame(id, setup);
    }

    private void openGame(String id, CompanionSetup setup) {
        CompanionTable table = new CompanionTable(id, setup, store);
        try {
            // check it here, where a bad save can be set aside, rather than in the starting game where it can't
            List<String> state = table.latestState();
            setup.checkState(state);
            CompanionGameState.of(state); // throws on bad numbers only; other problems are printed and skipped
        } catch (UncheckedIOException e) {
            FOptionPane.showErrorDialog("The saved game couldn't be read, so it wasn't resumed.\n" + e.getMessage());
            return;
        } catch (RuntimeException e) {
            File aside = store.setAside(id);
            FOptionPane.showErrorDialog("The saved game couldn't be read, so it wasn't resumed. It's kept at:\n" + aside
                    + "\n" + e.getMessage());
            return;
        }
        table.setOnResult(result -> {
            if (!result.done()) {
                FThreads.invokeInEdtLater(() -> FOptionPane.showErrorDialog(result.message()));
            }
        });
        // over the prompt the game shows next; dismissing it leaves that prompt as it was
        table.setOnNotice(notice -> FThreads.invokeInEdtLater(() -> FOptionPane.showMessageDialog(notice)));
        LoadingOverlay.show("Setting up the table", true, () -> {
            HostedMatch match = MatchController.hostMatch();
            MatchController.setExtension(new MatchExtension() {
                @Override
                public void addGameMenuItems(FDropDownMenu menu) {
                    menu.addItem(new FMenuItem("Add card by name", FSkinImage.PLUS,
                            e -> CardSearchDialog.addByName(table, MatchController.instance.getGameView())));
                    // the menu is built each time it opens, so the labels show the current settings
                    boolean everyTurn = table.engineCombat();
                    if (!everyTurn) {
                        boolean thisTurn = table.engineCombatThisTurn();
                        menu.addItem(new FMenuItem(thisTurn ? "Combat this turn: on" : "Combat this turn: off",
                                FSkinImage.ATTACK, e -> table.setEngineCombatThisTurn(!thisTurn)));
                    }
                    menu.addItem(new FMenuItem(everyTurn ? "Combat every turn: on" : "Combat every turn: off",
                            FSkinImage.ATTACK, e -> table.setEngineCombat(!everyTurn)));
                }

                @Override
                public boolean showsHandsAtStart() {
                    return false; // a paper game's hands aren't known: the rows would only show card backs
                }

                @Override
                public String promptActionLabel() {
                    return "Add card"; // the main thing done at the table, so it sits where the thumb is
                }

                @Override
                public void runPromptAction() {
                    CardSearchDialog.addByPhoto(table, MatchController.instance.getGameView());
                }
            });
            CompanionMatch.start(match, GuiBase.getInterface().getNewGuiGame(), table);
        });
    }
}
