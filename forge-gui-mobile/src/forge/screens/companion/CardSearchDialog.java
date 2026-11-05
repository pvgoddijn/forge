package forge.screens.companion;

import forge.game.GameView;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.companion.TableEditor;
import forge.toolbox.FComboBox;
import forge.toolbox.FTextField;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a card by photo, falling back to typing: a {@link CardPicker} for any card, with the player and how it was
 * played above the search. {@link #addByPhoto} opens the camera first and shows this dialog only with what the photo
 * gave; {@link #addByName} opens it to type. Typing: type, tap a match, check the player and how it was played,
 * confirm. The game then does what the card does. Text that matches nothing can still be added as typed, which is how
 * emblems get in, as Forge has no list of them.
 */
public class CardSearchDialog extends CardPicker {
    private static final String FROM_HAND = "From hand";
    private static final String FROM_ELSEWHERE = "From elsewhere";
    private static final String CAST = "Cast it";
    private static final String PUT = "Put onto battlefield";
    private static final String SHOW_ALL = "Show all players";

    private final CompanionTable table;
    private final FComboBox<String> cbPlayer = add(new FComboBox<>());
    private final FComboBox<String> cbFrom = add(new FComboBox<>());
    private final FComboBox<String> cbMode = add(new FComboBox<>());
    private final List<String> allPlayers = new ArrayList<>();
    private final String activePlayer; // the default target (AC-17)
    private String playerBeforeShowAll; // what "show all" keeps chosen

    /** @param gameView the running game, for the active player, who is the default target (AC-17); may be null */
    public CardSearchDialog(CompanionTable table0, GameView gameView) {
        super("Add card", "Add", null);
        table = table0;

        for (CompanionSetup.Seat seat : table.setup().gameOrder()) {
            allPlayers.add(seat.name());
        }
        PlayerView active = gameView == null ? null : gameView.getPlayerTurn();
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
        // an op the table can park: the active player's sorcery waits for its main phase (AC-42)
        table.submit(new CompanionTable.AddCard(player, name, fromHand, mode));
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
