package forge.screens.companion;

import forge.Forge;
import forge.StaticData;
import forge.gamemodes.companion.CommanderLegality;
import forge.gamemodes.companion.CompanionGameState;
import forge.gamemodes.companion.CompanionMatch;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.match.HostedMatch;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.screens.LaunchScreen;
import forge.screens.LoadingOverlay;
import forge.screens.home.NewGameMenu;
import forge.screens.match.MatchController;
import forge.toolbox.FButton;
import forge.toolbox.FCheckBox;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FScrollPane;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.io.File;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Sets up a tracked game: players in seat order, and which one is Me. The game itself is a real match on Forge's match
 * screen, with every seat answered by the operator. Goes straight to the active game if there is one; with none, it's
 * a new game's setup, and Past games for the ones that have ended.
 */
public class CompanionSetupScreen extends LaunchScreen {
    private static final float PADDING = Utils.scale(5);
    /** Digits only, at most four, so parsing never overflows; starting life is at least 1, so no minus. */
    private static final Pattern LIFE_DIGITS = Pattern.compile("\\d{0,4}");

    private final CompanionStore store = new CompanionStore(new File(ForgeConstants.USER_DIR, "companion"));
    private final FTextField[] names = new FTextField[CompanionSetup.MAX_PLAYERS];
    private final FCheckBox[] mes = new FCheckBox[CompanionSetup.MAX_PLAYERS];
    private final FButton[] ups = new FButton[CompanionSetup.MAX_PLAYERS];
    /** Each seat's "Deck..." button, which shows the deck once one is loaded (AC-3). */
    private final FButton[] deckButtons = new FButton[CompanionSetup.MAX_PLAYERS];
    private final FButton[] clears = new FButton[CompanionSetup.MAX_PLAYERS];
    private final CompanionDeckDialog.Loaded[] decks = new CompanionDeckDialog.Loaded[CompanionSetup.MAX_PLAYERS];
    /** The form scrolls: with six players its rows don't fit above the buttons on a small screen. */
    private final FScrollPane form = add(new FScrollPane() {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            return new ScrollBounds(visibleWidth, layoutForm(visibleWidth));
        }
    });
    private final FButton btnFewer = form.add(new FButton("-"));
    private final FButton btnMore = form.add(new FButton("+"));
    private final FLabel lblLife = form.add(new FLabel.Builder().text("Starting life").build());
    private final FTextField txtLife = form.add(new FTextField(String.valueOf(CompanionSetup.DEFAULT_LIFE)));
    /** On by default (AC-43): Start then asks for each player's commander (AC-44). */
    private final FCheckBox chkCommanderMode = form.add(new FCheckBox("Commander mode", true));
    private final FButton btnPastGames = add(new FButton(Forge.getLocalizer().getMessage("lblCompanionPastGames")));
    private int playerCount = 4;

    public CompanionSetupScreen() {
        super(null, NewGameMenu.getMenu());

        for (int i = 0; i < CompanionSetup.MAX_PLAYERS; i++) {
            final int row = i;
            names[i] = form.add(new FTextField("Player " + (i + 1)));
            mes[i] = form.add(new FCheckBox("Me", i == 0));
            mes[i].setCommand(e -> setMe(row));
            ups[i] = form.add(new FButton("^"));
            ups[i].setCommand(e -> swapWithRowAbove(row));
            deckButtons[i] = form.add(new FButton("Deck..."));
            deckButtons[i].setCommand(e -> new CompanionDeckDialog(playerName(row), deck -> setDeck(row, deck)).show());
            clears[i] = form.add(new FButton("X"));
            clears[i].setCommand(e -> setDeck(row, null));
        }
        txtLife.setIsNumeric(true);
        txtLife.setLiveChangeEvents(true);
        txtLife.setChangedHandler(e -> {
            // a keyboard can type anything: keep the last text that was still digits, as LifeDialog does
            if (!LIFE_DIGITS.matcher(txtLife.getText()).matches()) {
                txtLife.setText(e.getArgs() instanceof String before ? before : "");
            }
        });
        btnFewer.setCommand(e -> setPlayerCount(playerCount - 1));
        btnMore.setCommand(e -> setPlayerCount(playerCount + 1));
        btnPastGames.setCommand(e -> Forge.openScreen(new CompanionPastGamesScreen(store)));
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
        CompanionDeckDialog.Loaded deck = decks[row];
        setDeck(row, decks[row - 1]);
        setDeck(row - 1, deck);
    }

    private String playerName(int row) {
        String name = names[row].getText().trim();
        return name.isEmpty() ? "Player " + (row + 1) : name;
    }

    /** Attaches {@code deck} to the seat in {@code row}, or clears it for null. */
    private void setDeck(int row, CompanionDeckDialog.Loaded deck) {
        decks[row] = deck;
        deckButtons[row].setText(deck == null ? "Deck..." : deck.summary());
        clears[row].setVisible(deck != null && row < playerCount);
        form.revalidate();
    }

    private void setPlayerCount(int count) {
        playerCount = count;
        for (int i = playerCount; i < mes.length; i++) {
            if (mes[i].isSelected()) {
                setMe(0); // a removed seat can't stay Me
            }
        }
        updateRows();
        form.revalidate(); // the form's size doesn't change, so the screen's layout wouldn't lay out its rows again
    }

    private void updateRows() {
        for (int i = 0; i < CompanionSetup.MAX_PLAYERS; i++) {
            boolean visible = i < playerCount;
            names[i].setVisible(visible);
            mes[i].setVisible(visible);
            ups[i].setVisible(visible && i > 0);
            deckButtons[i].setVisible(visible);
            clears[i].setVisible(visible && decks[i] != null);
        }
        btnFewer.setEnabled(playerCount > CompanionSetup.MIN_PLAYERS);
        btnMore.setEnabled(playerCount < CompanionSetup.MAX_PLAYERS);
    }

    @Override
    protected void doLayoutAboveBtnStart(float startY, float width, float height) {
        // Past games stays put above Start; the form above it scrolls
        float rowHeight = FTextField.getDefaultHeight();
        float pastGamesY = height - rowHeight - PADDING;
        btnPastGames.setBounds(PADDING, pastGamesY, width - 2 * PADDING, rowHeight);
        form.setBounds(0, startY, width, pastGamesY - PADDING - startY);
    }

    /** Lays out the form's rows in the scroll pane, and returns their height. */
    private float layoutForm(float width) {
        float rowHeight = FTextField.getDefaultHeight();
        float buttonWidth = rowHeight * 1.2f;
        float meWidth = rowHeight * 2.2f;
        float y = PADDING;
        for (int i = 0; i < CompanionSetup.MAX_PLAYERS; i++) {
            names[i].setBounds(PADDING, y, width - meWidth - buttonWidth - 4 * PADDING, rowHeight);
            mes[i].setBounds(names[i].getRight() + PADDING, y, meWidth, rowHeight);
            ups[i].setBounds(mes[i].getRight() + PADDING, y, buttonWidth, rowHeight);
            float deckY = y + rowHeight + PADDING;
            // the deck goes on a line of its own under the name, as a phone's row has no room for it
            float deckWidth = width - 2 * PADDING - (decks[i] == null ? 0 : buttonWidth + PADDING);
            deckButtons[i].setBounds(PADDING, deckY, deckWidth, rowHeight);
            clears[i].setBounds(deckButtons[i].getRight() + PADDING, deckY, buttonWidth, rowHeight);
            if (i < playerCount) {
                y = deckY + rowHeight + PADDING;
            }
        }
        btnFewer.setBounds(PADDING, y, buttonWidth, rowHeight);
        btnMore.setBounds(btnFewer.getRight() + PADDING, y, buttonWidth, rowHeight);
        y += rowHeight + PADDING;
        float lifeWidth = rowHeight * 2.2f;
        lblLife.setBounds(PADDING, y, width - lifeWidth - 3 * PADDING, rowHeight);
        txtLife.setBounds(lblLife.getRight() + PADDING, y, lifeWidth, rowHeight);
        y += rowHeight + PADDING;
        chkCommanderMode.setBounds(PADDING, y, width - 2 * PADDING, rowHeight);
        return y + rowHeight + PADDING;
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
            CompanionDeckDialog.Loaded deck = decks[i];
            seats.add(deck == null ? new CompanionSetup.Seat(name, mes[i].isSelected())
                    : new CompanionSetup.Seat(name, mes[i].isSelected(), deck.commanders(), deck.deckList()));
        }
        String lifeText = txtLife.getText().trim();
        // the field keeps to four digits, so this can't overflow; empty counts as 0
        int life = lifeText.isEmpty() ? 0 : Integer.parseInt(lifeText);
        if (life < 1) {
            FOptionPane.showErrorDialog("Starting life must be at least 1.");
            return;
        }
        CompanionSetup setup = CompanionSetup.withDefaults(seats).withLife(life)
                .withCommanderMode(chkCommanderMode.isSelected());
        if (!setup.commanderMode()) {
            // a deck's commanders aren't this game's: none in a Command zone, no Commander variant
            createAndOpen(setup.withoutCommanders());
            return;
        }
        chooseCommanders(new ArrayList<>(seats), 0, chosen -> createAndOpen(new CompanionSetup(chosen, setup.life(),
                setup.handSize(), setup.librarySize(), setup.engineCombat(), setup.commanderMode())));
    }

    /**
     * The commander step (AC-44): for each seat from {@code index} on, in seat order, a commander by photo, typing,
     * or "No commander", and a second one when the first can have a partner or a background. Then {@code done}, with
     * the seats and their commanders. A seat whose deck named its commanders is only asked to confirm them, without
     * the camera, or change them. Cancel at any point ends the step on this screen, with nothing started or saved;
     * Photo in the picker is the retake. UI thread only, and no game exists yet.
     */
    private void chooseCommanders(List<CompanionSetup.Seat> seats, int index, Consumer<List<CompanionSetup.Seat>> done) {
        if (index == seats.size()) {
            done.accept(seats);
            return;
        }
        CompanionSetup.Seat seat = seats.get(index);
        Consumer<List<String>> next = commanders -> {
            seats.set(index, seat.withCommanders(commanders));
            chooseCommanders(seats, index + 1, done);
        };
        if (!seat.commanders().isEmpty()) {
            // from the seat's deck (AC-3): only confirmed, without the camera; Change is the usual step
            String named = String.join(" and ", seat.commanders());
            FOptionPane.showOptionDialog(seat.name() + "'s deck has " + named + " as its commander"
                    + (seat.commanders().size() > 1 ? "s." : "."), seat.name() + "'s commander", null,
                    List.of("Confirm", "Change", "No commander"), 0, choice -> {
                if (choice == null) {
                    return; // dismissed: the step ends here, as Cancel in the picker does
                }
                if (choice == 0) {
                    next.accept(seat.commanders());
                } else if (choice == 2) {
                    next.accept(List.of());
                } else {
                    pickCommanders(seat, next);
                }
            });
            return;
        }
        pickCommanders(seat, next);
    }

    /** The commander step's picker for {@code seat}: photo first, a partner when allowed, or "No commander". */
    private void pickCommanders(CompanionSetup.Seat seat, Consumer<List<String>> next) {
        new CardPicker(seat.name() + "'s commander", CommanderLegality::canBeCommander, name -> {
            PaperCard first = StaticData.instance().getCommonCards().getUniqueByName(name);
            if (first == null || !CommanderLegality.canHaveSecondCommander(first)) {
                next.accept(List.of(name));
                return;
            }
            FOptionPane.showOptionDialog(name + " can have a second commander: a partner or a background.",
                    seat.name() + "'s commander", null, List.of("Add partner", "Next"), 0, choice -> {
                if (choice == null || choice != 0) {
                    next.accept(List.of(name));
                    return;
                }
                new CardPicker(seat.name() + "'s second commander", CommanderLegality.secondCommanderFor(first),
                        second -> next.accept(List.of(name, second)))
                        .withSkip("No second commander", () -> next.accept(List.of(name)))
                        .withPhotoPrompt(seat.name() + "'s second commander (" + name + "'s partner): photograph"
                                + " it, or Cancel to type its name or choose none.")
                        .pickByPhoto();
            });
        }).withSkip("No commander", () -> next.accept(List.of()))
                .withPhotoPrompt(seat.name() + "'s commander: photograph it, or Cancel to type its name or choose"
                        + " No commander.")
                .pickByPhoto();
    }

    private void createAndOpen(CompanionSetup setup) {
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
            MatchController.setExtension(new CompanionMatchExtension(table, store, match));
            CompanionMatch.start(match, GuiBase.getInterface().getNewGuiGame(), table);
        });
    }
}
