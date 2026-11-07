package forge.screens.companion;

import forge.StaticData;
import forge.game.GameView;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.AutoAdd;
import forge.gamemodes.companion.AutoAddPolicy;
import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.CommanderLegality;
import forge.gamemodes.companion.CompanionPreferences;
import forge.gamemodes.companion.CompanionPreferences.CPref;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.companion.DeckListNames;
import forge.gamemodes.companion.SeatOffer;
import forge.gamemodes.companion.TableEditor;
import forge.item.PaperCard;
import forge.toolbox.FComboBox;
import forge.toolbox.FOptionPane;
import forge.toolbox.FTextField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds a card by photo, falling back to typing: a {@link CardPicker} for any card, with the player and how it was
 * played above the search. {@link #addByPhoto} opens the camera first and shows this dialog only with what the photo
 * gave; {@link #addByName} opens it to type. Typing: type, tap a match, check the player and how it was played,
 * confirm. The game then does what the card does. Text that matches nothing can still be added as typed, which is how
 * emblems get in, as Forge has no list of them.
 * <p>
 * With Commander mode on, a card from hand outside the target's commanders' colour identity isn't added (AC-43): for
 * the active player it offers to move the turn ahead to the first player it fits (AC-45), otherwise it's refused
 * (AC-46), each with Retake (Edit for a typed card) and Cancel ({@link CommanderLegality#checkAdd}). Such cards show
 * greyed in the list, but stay in it.
 * <p>
 * In auto mode (slice 7, AC-9), a photo {@link AutoAdd#plan} is sure of is added at once with the defaults, the
 * active player, From hand and Cast it, without the dialog, and {@link CompanionMatchExtension#afterAutoAdd} then
 * offers to undo it. With Commander mode on, a card refused for the active player gets the same choice as tapping
 * Add would give, never an add.
 * <p>
 * The target player's deck, when one was loaded, ranks first, typed or photographed (AC-12); matches from the whole
 * card database still follow. Choosing another player ranks the list again for that player's deck.
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
    private final Map<String, Set<String>> decks = new HashMap<>(); // seat name to its deck's card names
    private final Map<String, Boolean> greyed = new HashMap<>(); // card name to greyed, for greyedFor
    private String greyedFor; // the target the greyed cache is for
    private String selectedCard; // the card chosen in the list; null while none is

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
                rerank(); // another player's deck (AC-12)
            }
        });
        playerBeforeShowAll = activePlayer;
        cbFrom.setItems(List.of(FROM_HAND, FROM_ELSEWHERE), FROM_HAND);
        cbFrom.setDropDownChangeHandler(e -> offerPlayers()); // only a card from hand is narrowed
        cbMode.setItems(List.of(CAST, PUT), CAST);
        withPhotoPrompt("Photograph a card for " + activePlayer);
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
        selectedCard = card;
        offerPlayers();
    }

    /**
     * Offers the seats {@link CompanionTable#offerSeats} works out for the card chosen ({@link SeatOffer}): for an
     * instant or a flash card from hand, only the seats that could have cast it (item 11), with Commander mode on
     * only those whose commanders allow it, but always the active player; always with "show all" then, as the mana
     * check is approximate and the operator must be able to pick anyone (AC-39). Otherwise every seat. The choice
     * already made stays when it's still offered; otherwise the active player if offered, else the first one.
     */
    private void offerPlayers() {
        boolean fromHand = FROM_HAND.equals(cbFrom.getSelectedItem());
        SeatOffer offer = table.offerSeats(selectedCard, fromHand, activePlayer, target());
        // the combo box compares items by identity: map back to the setup's own Strings
        List<String> items = new ArrayList<>();
        for (String seat : offer.seats()) {
            items.add(allPlayers.get(allPlayers.indexOf(seat)));
        }
        String pick = allPlayers.get(allPlayers.indexOf(offer.preselected()));
        if (offer.narrowed()) {
            items.add(SHOW_ALL);
        }
        cbPlayer.setItems(items, pick);
        playerBeforeShowAll = pick;
    }

    /** The deck of the player chosen, or of the one chosen before "show all"; none when no deck was loaded. */
    @Override
    protected Set<String> preferredCards() {
        String target = target();
        if (target == null) {
            return Set.of();
        }
        return decks.computeIfAbsent(target, seat -> table.setup().seats().stream()
                .filter(s -> s.name().equals(seat))
                .findFirst()
                .map(s -> DeckListNames.cardNames(s.deckList()))
                .orElse(Set.of()));
    }

    /**
     * With Commander mode on, a card from hand that the target's commanders don't allow (AC-43), as {@link #picked}
     * would refuse or move it. Asked for each row as it's drawn, so it follows the player and "from" chosen.
     */
    @Override
    protected boolean isGreyed(String card) {
        String target = target();
        if (target == null || !table.setup().commanderMode() || !FROM_HAND.equals(cbFrom.getSelectedItem())) {
            return false;
        }
        if (!target.equals(greyedFor)) {
            greyed.clear();
            greyedFor = target;
        }
        return greyed.computeIfAbsent(card, name -> {
            PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
            return paper != null && !CommanderLegality.isLegal(paper,
                    CommanderLegality.commanders(table.setup().commandersOf(target)));
        });
    }

    /** The player chosen, or the one chosen before "show all" while that is showing. */
    private String target() {
        String player = cbPlayer.getSelectedItem();
        return player == null || SHOW_ALL.equals(player) ? playerBeforeShowAll : player;
    }

    @Override
    protected boolean canPick() {
        String player = cbPlayer.getSelectedItem();
        return player != null && !SHOW_ALL.equals(player);
    }

    /**
     * Auto mode: with the preference on, a photo {@link AutoAdd#plan} is sure of goes to the active player, From hand,
     * Cast it, as tapping Add with the defaults would, through the same Commander check. The seats are offered as
     * the dialog would offer them, so an instant the active player couldn't have cast shows the dialog with the seat
     * it chooses instead. Anything else shows the dialog as in confirm mode.
     */
    @Override
    protected boolean handledWithoutDialog(CardNameReader.Reading reading) {
        boolean on = CompanionPreferences.get().getPrefBoolean(CPref.AUTO_ADD);
        // the active seat now, from the live view: the turn may have moved on since the dialog opened for the photo
        String live = activeSeat();
        String active = live != null && allPlayers.contains(live) ? live : activePlayer;
        AutoAdd.Plan plan = AutoAdd.plan(on, reading, AutoAddPolicy.THRESHOLD, active,
                card -> table.offerSeats(card, true, active, active),
                card -> check(card, true, active).verdict());
        switch (plan.action()) {
            case ADD:
                hide();
                table.submit(new CompanionTable.AddCard(active, plan.card(), true, TableEditor.Mode.CAST),
                        ran -> CompanionMatchExtension.afterAutoAdd(table, ran));
                return true;
            case COMMANDER_CHOICE:
                hide();
                addChecked(active, plan.card(), true, TableEditor.Mode.CAST);
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void picked(String name) {
        String player = cbPlayer.getSelectedItem();
        boolean fromHand = FROM_HAND.equals(cbFrom.getSelectedItem());
        TableEditor.Mode mode = PUT.equals(cbMode.getSelectedItem()) ? TableEditor.Mode.PUT_ONTO_BATTLEFIELD : TableEditor.Mode.CAST;
        addChecked(player, name, fromHand, mode);
    }

    /**
     * Slice 6's Commander check for {@code name} and {@code player}: the setup read now, as Commander mode can be
     * switched in the game menu; the turn from the view, on this thread.
     */
    private CommanderLegality.AddCheck check(String name, boolean fromHand, String player) {
        PaperCard card = StaticData.instance().getCommonCards().getUniqueByName(name);
        return CommanderLegality.checkAdd(card, name, fromHand, table.setup(), player, activeSeat(), liveSeats());
    }

    /** Adds {@code name} for {@code player} if the Commander check allows it; otherwise offers its choice. */
    private void addChecked(String player, String name, boolean fromHand, TableEditor.Mode mode) {
        CommanderLegality.AddCheck check = check(name, fromHand, player);
        switch (check.verdict()) {
            case SKIP:
                // the move, named for its seat, is the middle button: the only one sized to its text, so a long name
                // keeps the normal text size; the corner buttons fit one short word
                FOptionPane.showOptionDialog(check.message(), "Commander mode", null,
                        List.of(retakeLabel(), check.moveLabel(), "Cancel"), 1, choice -> {
                            if (choice == null) {
                                return; // dismissed: as Cancel
                            }
                            if (choice == 1) {
                                // the card goes to the seat moved to; nothing is parked before this (Check early)
                                String seat = check.skip().seat();
                                table.submit(new CompanionTable.AddCard(seat, name, true, mode, seat));
                            } else if (choice == 0) {
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

    /**
     * The seats still in the game, by name, from the view: the game's players leave out those who lost, so a turn
     * skip never moves to one of them. Every seat when there is no view. UI thread.
     */
    private Set<String> liveSeats() {
        if (gameView == null) {
            return new HashSet<>(allPlayers);
        }
        Set<String> live = new HashSet<>();
        for (PlayerView p : gameView.getPlayers()) {
            live.add(p.getName());
        }
        return live;
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
