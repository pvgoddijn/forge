package forge.screens.companion;

import forge.Forge;
import forge.assets.FSkinImage;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CardCameras;
import forge.gamemodes.companion.CompanionCards;
import forge.gamemodes.companion.CompanionPreferences;
import forge.gamemodes.companion.CompanionPreferences.CPref;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.companion.TableEditor;
import forge.gamemodes.companion.share.CompanionShareHost;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.InputPayMana;
import forge.gui.FThreads;
import forge.interfaces.IGameController;
import forge.menu.FDropDownMenu;
import forge.menu.FMenuItem;
import forge.screens.match.MatchController;
import forge.screens.match.MatchExtension;
import forge.toolbox.FOptionPane;
import forge.toolbox.GuiChoose;
import forge.util.ThreadUtil;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * What a tracked game adds to the match screen: the table's actions in the game menu, "Move…" on a card's menu, a
 * tap on a life total to set it, the prompt bar's Add card button (Paid while a mana cost is asked for), and leaving
 * the match screen when the operator ends the game.
 */
class CompanionMatchExtension implements MatchExtension {
    private final CompanionTable table;
    private final CompanionStore store;
    private final HostedMatch match;

    CompanionMatchExtension(CompanionTable table, CompanionStore store, HostedMatch match) {
        this.table = table;
        this.store = store;
        this.match = match;
        stopEndedShare(); // a new Companion game on this device: the last one's phones have had their end
    }

    /** Set once the game is marked ended, so its game over closes the match screen. */
    private volatile boolean ending;

    @Override
    public void addGameMenuItems(FDropDownMenu menu) {
        menu.addItem(new FMenuItem("Add card by name", FSkinImage.PLUS,
                e -> CardSearchDialog.addByName(table, MatchController.instance.getGameView())));
        // as of the prompt the menu was opened from; a refusal still shows its message if the game moved on since
        menu.addItem(new FMenuItem("Undo", e -> table.undo(), table.canUndo()));
        // the cards added this turn, as of that prompt too; disabled when there's nothing to correct
        menu.addItem(new FMenuItem("Wrong player…",
                e -> WrongPlayerDialog.show(table, MatchController.instance.getGameView()),
                !table.addedThisTurn().isEmpty()));
        // the log as of that prompt too, published by the game thread: nothing here reads the game or the store
        menu.addItem(new FMenuItem(Forge.getLocalizer().getMessage("lblCompanionHistory"), e -> showHistory()));
        menu.addItem(new FMenuItem(Forge.getLocalizer().getMessage("lblCompanionShareGame"), e -> share()));
        // the menu is built each time it opens, so the labels show the current settings
        boolean everyTurn = table.engineCombat();
        if (!everyTurn) {
            boolean thisTurn = table.engineCombatThisTurn();
            menu.addItem(new FMenuItem(thisTurn ? "Combat this turn: on" : "Combat this turn: off",
                    FSkinImage.ATTACK, e -> {
                        table.setEngineCombatThisTurn(!thisTurn);
                        combatSwitched(!thisTurn);
                    }));
        }
        menu.addItem(new FMenuItem(everyTurn ? "Combat every turn: on" : "Combat every turn: off",
                FSkinImage.ATTACK, e -> {
                    table.setEngineCombat(!everyTurn);
                    combatSwitched(!everyTurn);
                }));
        // AC-43: switched off, a card outside a commander's colour identity is added as asked
        boolean commanderMode = table.commanderMode();
        menu.addItem(new FMenuItem(commanderMode ? "Commander mode: on" : "Commander mode: off",
                e -> table.setCommanderMode(!commanderMode)));
        // AC-10: confirm mode by default; auto mode adds a confident photo at once, with an undo prompt
        CompanionPreferences prefs = CompanionPreferences.get();
        boolean autoAdd = prefs.getPrefBoolean(CPref.AUTO_ADD);
        menu.addItem(new FMenuItem(autoAdd ? "Auto-add photos: on" : "Auto-add photos: off", e -> {
            prefs.setPref(CPref.AUTO_ADD, !autoAdd);
            prefs.save();
        }));
        // slice 8, only where photos are taken: keeps each photo, labelled with the card picked, for the OCR
        // benchmark; the one exception to AC-7
        if (CardCameras.get() != null && CardCameras.get().canPhotograph()) {
            boolean keepPhotos = prefs.getPrefBoolean(CPref.KEEP_PHOTOS);
            menu.addItem(new FMenuItem(keepPhotos ? "Keep photos for training: on" : "Keep photos for training: off",
                    e -> {
                        prefs.setPref(CPref.KEEP_PHOTOS, !keepPhotos);
                        prefs.save();
                    }));
        }
        // read from the views on the UI thread; the op re-checks each card on the game thread
        List<CardView> sick = new ArrayList<>();
        for (PlayerView p : MatchController.instance.getGameView().getPlayers()) {
            for (CardView c : p.getBattlefield()) {
                if (c.isSick()) {
                    sick.add(c);
                }
            }
        }
        if (!sick.isEmpty()) {
            menu.addItem(new FMenuItem("Ready creatures…", FSkinImage.ATTACK,
                    e -> chooseToReady(READY_PICK, sick)));
        }
        menu.addItem(new FMenuItem("End game…", FSkinImage.CONCEDE, e -> confirmEnd()));
    }

    /**
     * After an auto-add has run (AC-9), from its own result callback, so after any target or mode prompt the cast
     * asked: what was added, with OK and Undo. Undo is offered only when it would take back that add alone
     * ({@link CompanionTable.Ran#undoable}), and {@link CompanionTable#undoAdd} checks again when it runs; otherwise
     * the prompt says what was added, with OK, and points to the game menu's Undo. A sorcery that waited for a main
     * phase gets no prompt: it runs long after the photo, so a prompt then would surprise, and the table's own
     * notices already said it waits and its result says it was cast. An add that didn't happen, such as a cast
     * cancelled at its target prompt, has nothing to undo: the table's own result message says why. Game loop thread.
     */
    static void afterAutoAdd(CompanionTable table, CompanionTable.Ran ran) {
        if (!ran.result().done() || ran.waited()) {
            return;
        }
        if (!ran.undoable()) {
            String card = ran.result().card();
            String added = card == null ? ran.result().message() : "Added " + card + ".";
            FThreads.invokeInEdtLater(() -> FOptionPane.showOptionDialog(added + " Undo in the game menu takes back"
                    + " the latest action.", "Added", null, List.of("OK"), 0, null));
            return;
        }
        FThreads.invokeInEdtLater(() -> FOptionPane.showOptionDialog(ran.result().message(), "Added", null,
                List.of("OK", "Undo"), 0, choice -> {
                    if (choice != null && choice == 1) {
                        table.undoAdd(ran.seq());
                    }
                }));
    }

    /**
     * The share of this match's table, made the first time "Share game…" is picked (its {@code start} writes the
     * game's {@code share.json}), or when a game this device shared is continued; stopped from the dialog, or when the
     * match is left. Stopped for good from the dialog, it's dropped, so a later "Share game…" makes a new share with a
     * new token: the old code no longer gets in. UI thread.
     */
    private CompanionShareHost share;
    /** Set once the match is left, so a share again that comes late doesn't start. UI thread. */
    private boolean left;

    /**
     * The share of the last game that ended here, still answering its phones with the end (plan 4.1: an ended host
     * keeps answering {@code end} until the app leaves Companion), so a phone that was away when it ended learns it
     * when it comes back. Stopped once another Companion game opens on this device, or with the app. UI thread.
     *
     * <p>Known limit: until then it holds the ended game's table and {@code Game} in memory, and keeps its port, so
     * another share started meanwhile takes the next one. Leaving Companion for another Forge mode doesn't stop it (no
     * hook for that on mobile yet).
     */
    private static CompanionShareHost endedShare;

    private static void stopEndedShare() {
        if (endedShare != null) {
            endedShare.stopForGood(); // an ended game isn't continued, so its share state has nothing left to do
            endedShare = null;
        }
    }

    private void share() {
        if (share == null) {
            share = new CompanionShareHost(table);
        }
        CompanionShareHost shown = share;
        new ShareDialog(shown, () -> {
            if (share == shown) {
                share = null; // for good: Share game… again is a new share, with a new token
            }
        }).show();
    }

    /**
     * Continuing a game this device shared (its {@code share.json} says hosting): once its game has started, it's
     * shared again on the same port and token, so its phones come back by themselves. When that port is taken, the
     * code changes, and the host is told the new one. Call before the match starts.
     */
    void shareAgainWhenStarted() {
        table.addGameStartListener(new Consumer<Game>() {
            @Override
            public void accept(Game game) {
                table.removeGameStartListener(this); // once: a restart from the match screen keeps the share
                FThreads.invokeInEdtLater(CompanionMatchExtension.this::shareAgain);
            }
        });
    }

    private void shareAgain() {
        if (left || share != null) {
            return;
        }
        CompanionShareHost again = new CompanionShareHost(table);
        try {
            again.start();
        } catch (RuntimeException e) {
            // no free port, or the network refused to listen: the game goes on unshared; Share game… tries again
            FOptionPane.showErrorDialog(Forge.getLocalizer().getMessage("lblCompanionShareFailed", e.getMessage()));
            return;
        }
        share = again;
        if (again.codeChanged()) {
            List<String> codes = new ArrayList<>();
            for (CompanionShareHost.Code code : again.codes()) {
                codes.add(code.code().text());
            }
            FOptionPane.showMessageDialog(Forge.getLocalizer().getMessage("lblCompanionCodeChanged",
                    String.join("\n", codes)));
        }
    }

    /**
     * Leaving the match keeps the game's share state, so Continue in Load game shares it again on the same code; its
     * phones keep trying meanwhile. An ended game's share goes on answering with the end ({@link #endedShare}).
     */
    @Override
    public void matchLeft() {
        left = true;
        if (share != null) {
            if (ending) {
                stopEndedShare();
                endedShare = share; // the end goes out to the phones attached, and to those that come back
            } else {
                share.stop();
            }
            share = null;
        }
    }

    private void showHistory() {
        List<String> seats = table.setup().seats().stream().map(CompanionSetup.Seat::name).collect(Collectors.toList()); // not Stream.toList (Android < 14)
        Forge.openScreen(new CompanionHistoryScreen(table.logAtPrompt(), seats));
    }

    private static final String READY_PICK = "Pick the ones that were on the table before this turn";

    /**
     * After combat is switched: the prompt shows it again with its combat line, and if it's now on and the active
     * seat has creatures that can't attack because they were logged this turn, the engine would skip combat without a
     * word, so "Ready creatures…" opens for them straight away. Which creatures comes from the latest prompt, worked
     * out on the game thread ({@link CompanionTable#sickBeforeCombat}).
     */
    private void combatSwitched(boolean on) {
        table.refreshPrompt();
        List<Integer> ids = table.sickBeforeCombat();
        if (!on || ids.isEmpty()) {
            return;
        }
        List<CardView> sick = new ArrayList<>();
        for (PlayerView p : MatchController.instance.getGameView().getPlayers()) {
            for (CardView c : p.getBattlefield()) {
                if (ids.contains(c.getId())) {
                    sick.add(c);
                }
            }
        }
        if (!sick.isEmpty()) {
            chooseToReady("Combat is on, but creatures logged this turn can't attack, so the game would skip combat. "
                    + READY_PICK + ".", sick);
        }
    }

    private void chooseToReady(String message, List<CardView> sick) {
        GuiChoose.many("Ready creatures", message, 0, sick.size(), sick, null, chosen -> {
            if (chosen == null || chosen.isEmpty()) {
                return;
            }
            List<Integer> ids = chosen.stream().map(CardView::getId).collect(Collectors.toList()); // not Stream.toList (Android < 14)
            table.submit(game -> TableEditor.readyCreatures(game, ids));
        });
    }

    /** Whether combat goes through the engine this turn, in the active seat's Main 1; not under a target prompt. */
    @Override
    public String promptNote(PlayerView player) {
        return table.atPriorityPrompt() ? table.promptNote() : null;
    }

    private void confirmEnd() {
        FOptionPane.showConfirmDialog("End this game? It can't be resumed.", "End game", "End game",
                "Cancel", false, yes -> {
                    if (!yes) {
                        return;
                    }
                    try {
                        store.end(table.id()); // first, so it's a past game whatever happens to the match after
                    } catch (UncheckedIOException e) {
                        FOptionPane.showErrorDialog("The game couldn't be ended.\n" + e.getMessage());
                        return;
                    }
                    ending = true;
                    // conceding every seat ends the game, so the game thread returns from its loop;
                    // the game over then closes the match screen (leavesWhenGameOver), as Quit would
                    ThreadUtil.invokeInGameThread(() -> {
                        for (IGameController seat : MatchController.instance.getOriginalGameControllers()) {
                            Game game = match.getGame();
                            if (game == null || game.isGameOver()) {
                                break; // the last seat has won; conceding it too would change nothing
                            }
                            seat.concede();
                        }
                    });
                });
    }

    /** A concede doesn't end the save, so the game would be back on the next launch: "End game…" is the way out. */
    @Override
    public boolean offersConcede() {
        return false;
    }

    @Override
    public boolean leavesWhenGameOver() {
        return ending;
    }

    @Override
    public boolean tapLife(PlayerView player) {
        if (!table.atPriorityPrompt()) {
            return false; // a target prompt is open: the tap targets the player as usual
        }
        new LifeDialog(table, player).show();
        return true;
    }

    @Override
    public List<FMenuItem> cardMenuItems(CardView card) {
        // only at a priority prompt, as for a tap on a life total; an unknown card the op would refuse
        if (!table.atPriorityPrompt() || CompanionCards.PLACEHOLDER_NAME.equals(card.getName())) {
            return List.of();
        }
        return List.of(new FMenuItem("Move…",
                e -> MoveDialog.show(table, MatchController.instance.getGameView(), card)));
    }

    @Override
    public boolean showsHandsAtStart() {
        return false; // a paper game's hands aren't known: the rows would only show card backs
    }

    @Override
    public boolean showsTopPrompt() {
        return false; // one operator answers for every seat, so one prompt the right way up
    }

    /** The payment prompt the button was last labelled for, so a tap does what the label said; null for Add card. */
    private InputPayMana labelledPayment;

    @Override
    public String promptActionLabel() {
        return promptActionLabel(null);
    }

    /**
     * The main thing done at the table, so it sits where the thumb is: adding a card, or while a mana cost is asked
     * for, beside Auto, taking it as paid at the table without tapping anything.
     */
    @Override
    public String promptActionLabel(PlayerView owner) {
        labelledPayment = table.paymentPrompt(owner);
        return Forge.getLocalizer().getMessage(labelledPayment != null ? "lblCompanionManaPaid" : "lblCompanionAddCard");
    }

    @Override
    public void runPromptAction() {
        if (labelledPayment != null) {
            table.acceptManaAsPaid(labelledPayment); // nothing if that prompt has closed since
            return;
        }
        CardSearchDialog.addByPhoto(table, MatchController.instance.getGameView());
    }
}
