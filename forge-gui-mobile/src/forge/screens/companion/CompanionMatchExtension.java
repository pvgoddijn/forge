package forge.screens.companion;

import forge.assets.FSkinImage;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.companion.TableEditor;
import forge.gamemodes.match.HostedMatch;
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

/**
 * What a tracked game adds to the match screen: the table's actions in the game menu, a tap on a life total to set
 * it, the prompt bar's Add card button, and leaving the match screen when the operator ends the game.
 */
class CompanionMatchExtension implements MatchExtension {
    private final CompanionTable table;
    private final CompanionStore store;
    private final HostedMatch match;

    CompanionMatchExtension(CompanionTable table, CompanionStore store, HostedMatch match) {
        this.table = table;
        this.store = store;
        this.match = match;
    }

    /** Set once the game is marked ended, so its game over closes the match screen. */
    private volatile boolean ending;

    @Override
    public void addGameMenuItems(FDropDownMenu menu) {
        menu.addItem(new FMenuItem("Add card by name", FSkinImage.PLUS,
                e -> CardSearchDialog.addByName(table, MatchController.instance.getGameView())));
        // as of the prompt the menu was opened from; a refusal still shows its message if the game moved on since
        menu.addItem(new FMenuItem("Undo", e -> table.undo(), table.canUndo()));
        // the menu is built each time it opens, so the labels show the current settings
        boolean everyTurn = table.engineCombat();
        if (!everyTurn) {
            boolean thisTurn = table.engineCombatThisTurn();
            menu.addItem(new FMenuItem(thisTurn ? "Combat this turn: on" : "Combat this turn: off",
                    FSkinImage.ATTACK, e -> table.setEngineCombatThisTurn(!thisTurn)));
        }
        menu.addItem(new FMenuItem(everyTurn ? "Combat every turn: on" : "Combat every turn: off",
                FSkinImage.ATTACK, e -> table.setEngineCombat(!everyTurn)));
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
                    e -> GuiChoose.many("Ready creatures",
                            "Pick the ones that were on the table before this turn", 0, sick.size(), sick,
                            null, chosen -> {
                                if (chosen == null || chosen.isEmpty()) {
                                    return;
                                }
                                List<Integer> ids = chosen.stream().map(CardView::getId).toList();
                                table.submit(game -> TableEditor.readyCreatures(game, ids));
                            })));
        }
        menu.addItem(new FMenuItem("End game…", FSkinImage.CONCEDE, e -> confirmEnd()));
    }

    private void confirmEnd() {
        FOptionPane.showConfirmDialog("End this game? It can't be resumed.", "End game", "End game",
                "Cancel", false, yes -> {
                    if (!yes) {
                        return;
                    }
                    try {
                        store.end(table.id()); // first, so the setup screen we go back to doesn't resume it
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
    public boolean showsHandsAtStart() {
        return false; // a paper game's hands aren't known: the rows would only show card backs
    }

    @Override
    public boolean showsTopPrompt() {
        return false; // one operator answers for every seat, so one prompt the right way up
    }

    @Override
    public String promptActionLabel() {
        return "Add card"; // the main thing done at the table, so it sits where the thumb is
    }

    @Override
    public void runPromptAction() {
        CardSearchDialog.addByPhoto(table, MatchController.instance.getGameView());
    }
}
