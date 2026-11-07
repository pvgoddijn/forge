package forge.screens.companion;

import forge.StaticData;
import forge.game.GameView;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CommanderLegality;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.companion.TableEditor;
import forge.item.PaperCard;
import forge.toolbox.FComboBox;
import forge.toolbox.FOptionPane;
import forge.toolbox.FTextField;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a card by photo, falling back to typing: a {@link CardPicker} for any card, with the player and how it was
 * played above the search. {@link #addByPhoto} opens the camera first and shows this dialog only with what the photo
 * gave; {@link #addByName} opens it to type. Typing: type, tap a match, check the player and how it was played,
 * confirm. The game then does what the card does. Text that matches nothing can still be added as typed, which is how
 * emblems get in, as Forge has no list of them.
 * <p>
 * With Commander mode on, a card from hand outside the target's commanders' colour identity isn't added (AC-43): for
 * the active player it offers to move the turn ahead to the first player it fits (AC-45), otherwise it's refused
 * (AC-46), each with Retake (Edit for a typed card) and Cancel ({@link CommanderLegality#checkAdd}).
 */
public class CardSearchDialog extends CardPicker {
    private static final String FROM_HAND = "From hand";
    private static final String FROM_ELSEWHERE = "From elsewhere";
    private static final String CAST = "Cast it";
    private static final String PUT = "Put onto battlefield";
    private static final String SHOW_ALL = "Show all players";

    private final CompanionTable table;
    private final GameView gameView; // may be null
    private final FComboBox<String> cbPlayer = add(new FComboBox<>());
    private final FComboBox<String> cbFrom = add(new FComboBox<>());
    private final FComboBox<String> cbMode = add(new FComboBox<>());
    private final List<String> allPlayers = new ArrayList<>();
    private final String activePlayer; // the default target (AC-17)
    private String playerBeforeShowAll; // what "show all" keeps chosen

    /** @param gameView the running game, for the active player, who is the default target (AC-17); may be null */
    public CardSearchDialog(CompanionTable table0, GameView gameView0) {
        super("Add card", "Add", null);
        table = table0;
        gameView = gameView0;

        for (CompanionSetup.Seat seat : table.setup().gameOrder()) {
            allPlayers.add(seat.name());
        }
        PlayerView active = gameView0 == null ? null : gameView0.getPlayerTurn();
        activePlayer = active != null && allPlayers.contains(active.getName()) ? active.getName() : allPlayers.get(0);
        cbPlayer.setItems(allPlayers, activePlayer);
        cbPlayer.setDropDownChangeHandler(e -> {
            if (SHOW_ALL.equals(cbPlayer.getSelectedItem())) {
                cbPlayer.setItems(allPlayers, playerBeforeShowAll);
            } else {
                playerBeforeShowAll = cbPlayer.getSelectedItem();
            }
        });
        playerBeforeShowAll = activePlayer;
        cbFrom.setItems(List.of(FROM_HAND, FROM_ELSEWHERE), FROM_HAND);
        cbMode.setItems(List.of(CAST, PUT), CAST);
    }

    /**
     * Photographs a card first and shows the dialog only once there is a photo (see {@link CardPicker#pickByPhoto}).
     * A device without a camera opens it to type straight away.
     */
    public static void addByPhoto(CompanionTable table, GameView gameView) {
        new CardSearchDialog(table, gameView).pickByPhoto();
    }

    /** Opens the dialog to type a name, without the camera: for tokens and emblems, which have no card to photograph. */
    public static void addByName(CompanionTable table, GameView gameView) {
        new CardSearchDialog(table, gameView).pickByName();
    }

    @Override
    protected void onSelectionChanged(String card) {
        offerPlayers(card);
    }

    /**
     * For an instant or a flash card, offers only the seats that could have cast it (item 11), as the latest prompt
     * worked out on the game thread ({@link CompanionTable#couldHaveCast}), always with "show all", as the mana
     * check is approximate and the operator must be able to pick anyone (AC-39); a single one is chosen for the
     * operator. When nobody could have, or for any other card, every seat, as before (AC-39). The choice
     * already made stays when it's still offered; otherwise the active player if offered, else the first one.
     */
    private void offerPlayers(String card) {
        List<String> could = card == null ? List.of() : table.couldHaveCast(card);
        boolean narrowed = !could.isEmpty() && could.size() < allPlayers.size();
        // in the setup's order, and the setup's own Strings, as the combo box compares items by identity
        List<String> offered = narrowed ? allPlayers.stream().filter(could::contains).toList() : allPlayers;
        String current = cbPlayer.getSelectedItem();
        String pick = offered.contains(current) ? current : offered.contains(activePlayer) ? activePlayer : offered.get(0);
        List<String> items = new ArrayList<>(offered);
        if (narrowed) {
            items.add(SHOW_ALL);
        }
        cbPlayer.setItems(items, pick);
        playerBeforeShowAll = pick;
    }

    @Override
    protected boolean canPick() {
        String player = cbPlayer.getSelectedItem();
        return player != null && !SHOW_ALL.equals(player);
    }

    @Override
    protected void picked(String name) {
        String player = cbPlayer.getSelectedItem();
        boolean fromHand = FROM_HAND.equals(cbFrom.getSelectedItem());
        TableEditor.Mode mode = PUT.equals(cbMode.getSelectedItem()) ? TableEditor.Mode.PUT_ONTO_BATTLEFIELD : TableEditor.Mode.CAST;
        PaperCard card = StaticData.instance().getCommonCards().getUniqueByName(name);
        // the setup read now, as Commander mode can be switched in the game menu; the turn from the view, on this thread
        CommanderLegality.AddCheck check = CommanderLegality.checkAdd(card, name, fromHand, table.setup(), player,
                activeSeat());
        switch (check.verdict()) {
            case SKIP:
                FOptionPane.showOptionDialog(check.message(), "Commander mode", null,
                        List.of(check.moveLabel(), retakeLabel(), "Cancel"), 0, choice -> {
                            if (choice == null) {
                                return; // dismissed: as Cancel
                            }
                            if (choice == 0) {
                                // the card goes to the seat moved to; nothing is parked before this (Check early)
                                String seat = check.skip().seat();
                                table.submit(new CompanionTable.AddCard(seat, name, true, mode, seat));
                            } else if (choice == 1) {
                                retakeOrEdit();
                            }
                        });
                return;
            case NO_SEAT:
            case NOT_FOR_TARGET:
                FOptionPane.showOptionDialog(check.message(), "Commander mode", null,
                        List.of(retakeLabel(), "Cancel"), 0, choice -> {
                            if (choice != null && choice == 0) {
                                retakeOrEdit();
                            }
                        });
                return;
            default:
                // an op the table can park: the active player's sorcery waits for its main phase (AC-42)
                table.submit(new CompanionTable.AddCard(player, name, fromHand, mode));
        }
    }

    /** The seat whose turn it is, by setup name, from the view; null when it isn't known. UI thread. */
    private String activeSeat() {
        PlayerView active = gameView == null ? null : gameView.getPlayerTurn();
        return active == null ? null : active.getName();
    }

    private String retakeLabel() {
        return pickedFromPhoto() ? "Retake" : "Edit";
    }

    /** A new photo for a photographed card, the search again for a typed one; the player, from and mode stay. */
    private void retakeOrEdit() {
        if (pickedFromPhoto()) {
            retake();
        } else {
            edit();
        }
    }

    @Override
    protected float layoutAboveSearch(float x, float y, float width) {
        float rowHeight = FTextField.getDefaultHeight();
        cbPlayer.setBounds(x, y, width, rowHeight);
        y += rowHeight + PADDING;
        float half = (width - PADDING) / 2;
        cbFrom.setBounds(x, y, half, rowHeight);
        cbMode.setBounds(x + half + PADDING, y, half, rowHeight);
        return y + rowHeight + PADDING;
    }
}
