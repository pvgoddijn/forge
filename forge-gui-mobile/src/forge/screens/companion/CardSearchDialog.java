package forge.screens.companion;

import com.badlogic.gdx.utils.Align;
import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.game.GameView;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CardNameMatcher;
import forge.gamemodes.companion.CardNameReader;
import forge.gui.FThreads;
import forge.interfaces.IDeviceAdapter;
import forge.toolbox.FOptionPane;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.companion.TableEditor;
import forge.toolbox.FComboBox;
import forge.toolbox.FDialog;
import forge.toolbox.FList;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a card by photo, falling back to typing. {@link #addByPhoto} opens the camera first and shows this dialog only
 * with what the photo gave; {@link #addByName} opens it to type. Typing: type, tap a match, check the player and how it was played, confirm. The game then does what
 * the card does. Text that matches nothing can still be added as typed, which is how emblems get in, as Forge has no
 * list of them.
 */
public class CardSearchDialog extends FDialog {
    private static final float PADDING = Utils.scale(5);
    private static final float ITEM_HEIGHT = Utils.scale(26);
    private static final int MAX_MATCHES = 30;
    private static final String FROM_HAND = "From hand";
    private static final String FROM_ELSEWHERE = "From elsewhere";
    private static final String CAST = "Cast it";
    private static final String PUT = "Put onto battlefield";
    private static final String SHOW_ALL = "Show all players";
    private static CardNameMatcher matcher; // built once, on first use: it indexes every card name

    private final CompanionTable table;
    private final FComboBox<String> cbPlayer = add(new FComboBox<>());
    private final FComboBox<String> cbFrom = add(new FComboBox<>());
    private final FComboBox<String> cbMode = add(new FComboBox<>());
    private final FTextField txtSearch = add(new FTextField());
    private final FList<CardNameMatcher.Suggestion> lstMatches = add(new FList<>());
    private final List<String> allPlayers = new ArrayList<>();
    private final String activePlayer; // the default target (AC-17)
    private String selectedCard; // a name from the database; null while the text is only what was typed
    private boolean photographing; // a photo is being taken or read; UI thread only

    /** @param gameView the running game, for the active player, who is the default target (AC-17); may be null */
    public CardSearchDialog(CompanionTable table0, GameView gameView) {
        super("Add card", canPhotograph() ? 3 : 2);
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

        txtSearch.setGhostText("Card or token name");
        txtSearch.setLiveChangeEvents(true);
        txtSearch.setChangedHandler(e -> updateMatches());

        lstMatches.setListItemRenderer(new FList.ListItemRenderer<>() {
            @Override
            public float getItemHeight() {
                return ITEM_HEIGHT;
            }

            @Override
            public boolean tap(Integer index, CardNameMatcher.Suggestion value, float x, float y, int count) {
                txtSearch.setText(value.card());
                select(value.card());
                return true;
            }

            @Override
            public void drawValue(Graphics g, Integer index, CardNameMatcher.Suggestion value, FSkinFont font,
                    FSkinColor foreColor, FSkinColor backColor, boolean pressed, float x, float y, float w, float h) {
                g.drawText(value.card(), font, foreColor, x, y, w, h, false, Align.left, true);
            }
        });

        initButton(0, "Add as typed", e -> addCard());
        initButton(1, "Cancel", e -> hide());
        if (canPhotograph()) {
            initButton(2, "Photo", e -> photograph());
        }
    }

    /**
     * Photographs a card first and shows the dialog only once there is a photo: filled in with the read, so a confident
     * one takes one tap to add (AC-8), or with the text read to finish by typing (AC-11). No photo, as when the camera
     * is closed or refused, opens it empty to type. A device without a camera opens it to type straight away.
     */
    public static void addByPhoto(CompanionTable table, GameView gameView) {
        CardSearchDialog dialog = new CardSearchDialog(table, gameView);
        if (!canPhotograph()) {
            dialog.show();
            return;
        }
        dialog.photograph();
    }

    /** Opens the dialog to type a name, without the camera: for tokens and emblems, which have no card to photograph. */
    public static void addByName(CompanionTable table, GameView gameView) {
        new CardSearchDialog(table, gameView).show();
    }

    /** Whether this device can photograph a card and read its name (slice 1). Not on iOS, which has no reader. */
    private static boolean canPhotograph() {
        IDeviceAdapter device = Forge.getDeviceAdapter();
        return device != null && device.canPhotographCards() && device.getCardNameReader() != null;
    }

    /**
     * Takes a photo and reads it off the UI thread (AC-6, AC-7). A confident read lists the best card and its
     * alternatives here, the best already chosen, so it takes one tap to add (AC-8). Otherwise the text read is left in
     * the search box, to finish by typing (AC-11). No photo, as when the camera is refused, leaves the search as it was.
     */
    private void photograph() {
        if (photographing) {
            return; // one photo at a time: a second one would overwrite the first one's suggestions
        }
        photographing = true;
        CardNameReader reader = Forge.getDeviceAdapter().getCardNameReader();
        Forge.getDeviceAdapter().photographCard(photo -> {
            if (photo == null) {
                FThreads.invokeInEdtLater(() -> {
                    photographing = false;
                    showIfHidden(); // the camera was the first step: without a photo, type instead
                });
                return;
            }
            FThreads.invokeInEdtLater(() -> {
                showIfHidden();
                getButton(2).setText("Reading...");
            });
            FThreads.invokeInBackgroundThread(() -> {
                CardNameReader.Reading reading;
                try {
                    reading = reader.read(photo);
                } catch (Throwable e) {
                    // Not only exceptions: native OCR that won't load, or running out of memory, throws errors. On
                    // this bare thread an uncaught one would end the app mid-game.
                    e.printStackTrace();
                    reading = CardNameReader.Reading.NOTHING;
                }
                CardNameReader.Reading read = reading;
                FThreads.invokeInEdtLater(() -> showReading(read));
            });
        });
    }

    private boolean everShown; // false until the first photo comes back, so a hidden dialog wasn't closed by the operator

    private void showIfHidden() {
        if (!everShown) {
            everShown = true;
            show();
        }
    }

    private void showReading(CardNameReader.Reading reading) {
        photographing = false;
        getButton(2).setText("Photo");
        if (!isVisible()) {
            return; // the operator moved on while it was reading
        }
        if (reading.confident() && !reading.cards().isEmpty()) {
            List<CardNameMatcher.Suggestion> suggestions = new ArrayList<>();
            for (String card : reading.cards()) {
                suggestions.add(new CardNameMatcher.Suggestion(card, 1));
            }
            txtSearch.setText(reading.cards().get(0));
            lstMatches.setListData(suggestions);
            lstMatches.setScrollTop(0);
            lstMatches.revalidate();
            select(reading.cards().get(0));
            return;
        }
        txtSearch.setText(reading.textRead());
        updateMatches();
        if (reading.textRead().isEmpty()) {
            FOptionPane.showMessageDialog("The card's name couldn't be read. Try another photo, or type the name.");
        }
    }

    private void updateMatches() {
        if (matcher == null) {
            matcher = CardNameMatcher.fromStaticData();
        }
        List<CardNameMatcher.Suggestion> matches = matcher.match(txtSearch.getText(), MAX_MATCHES);
        lstMatches.setListData(matches);
        lstMatches.setScrollTop(0);
        lstMatches.revalidate();
        if (!matches.isEmpty() && matches.get(0).confidence() == 1) {
            select(matches.get(0).card()); // typed in full, so it needn't be tapped too
        } else if (selectedCard != null) {
            selectedCard = null;
            getButton(0).setText("Add as typed");
            offerPlayers(null);
        }
    }

    private void select(String card) {
        selectedCard = card;
        getButton(0).setText("Add");
        offerPlayers(card);
    }

    private String playerBeforeShowAll; // what "show all" keeps chosen

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

    private void addCard() {
        String name = selectedCard != null ? selectedCard : txtSearch.getText().trim();
        if (name.isEmpty()) {
            return;
        }
        String player = cbPlayer.getSelectedItem();
        if (player == null || SHOW_ALL.equals(player)) {
            return;
        }
        boolean fromHand = FROM_HAND.equals(cbFrom.getSelectedItem());
        TableEditor.Mode mode = PUT.equals(cbMode.getSelectedItem()) ? TableEditor.Mode.PUT_ONTO_BATTLEFIELD : TableEditor.Mode.CAST;
        hide();
        // an op the table can park: the active player's sorcery waits for its main phase (AC-42)
        table.submit(new CompanionTable.AddCard(player, name, fromHand, mode));
    }

    @Override
    protected float layoutAndGetHeight(float width, float maxHeight) {
        float rowHeight = FTextField.getDefaultHeight();
        float w = width - 2 * PADDING;
        float y = PADDING;
        cbPlayer.setBounds(PADDING, y, w, rowHeight);
        y += rowHeight + PADDING;
        float half = (w - PADDING) / 2;
        cbFrom.setBounds(PADDING, y, half, rowHeight);
        cbMode.setBounds(PADDING + half + PADDING, y, half, rowHeight);
        y += rowHeight + PADDING;
        txtSearch.setBounds(PADDING, y, w, rowHeight);
        y += rowHeight + PADDING;
        // tall enough that the drop-downs above, which open downward, stay on the screen
        float listHeight = Math.min(maxHeight - y - PADDING, ITEM_HEIGHT * 7);
        lstMatches.setBounds(PADDING, y, w, listHeight);
        return y + listHeight + PADDING;
    }
}
