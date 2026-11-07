package forge.screens.companion;

import forge.Forge;
import forge.deck.Deck;
import forge.gamemodes.companion.CommanderLegality;
import forge.gamemodes.companion.DeckListNames;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.toolbox.FButton;
import forge.toolbox.FDialog;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FTextArea;
import forge.toolbox.FTextField;
import forge.toolbox.GuiChoose;
import forge.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Attaches a deck to a player at setup (AC-3): a decklist pasted from the clipboard, or one of Forge's saved decks.
 * The deck's Commander section becomes the player's commanders, so the commander step only confirms them. The
 * decklist's text is what the game keeps, in {@code setup.json}, so it stays the same if the saved deck changes.
 * <p>
 * Forge mobile has no multi-line text field: as its own deck import ({@code FDeckImportDialog}), the list comes from
 * the clipboard, and is shown here to check before it is used. That dialog itself needs a deck editor's
 * configuration, so it isn't used.
 */
public class CompanionDeckDialog extends FDialog {
    private static final float PADDING = Utils.scale(5);
    private static final int USE = 0;

    /**
     * A deck attached to a player.
     *
     * @param deckList   the decklist's text, as saved with the game
     * @param commanders the commanders' card names from the deck, as Forge's card database spells them; none, one or
     *                   two
     */
    public record Loaded(String deckList, List<String> commanders) {
        public Loaded {
            commanders = List.copyOf(commanders);
        }

        /** How many cards the deck plays, commanders included. */
        public int cardCount() {
            return DeckListNames.cardCount(deckList);
        }

        /** What a player's row shows, such as "Deck: 100 cards, Krenko, Mob Boss". */
        public String summary() {
            String cards = "Deck: " + cardCount() + (cardCount() == 1 ? " card" : " cards");
            return commanders.isEmpty() ? cards : cards + ", " + String.join(" + ", commanders);
        }
    }

    private final Consumer<Loaded> onLoaded;
    private final FButton btnPaste = add(new FButton("Paste decklist"));
    private final FButton btnSaved = add(new FButton("Saved deck..."));
    private final FLabel lblSummary = add(new FLabel.Builder().text("Copy a decklist, then paste it here, or choose "
            + "a saved deck.").build());
    private final FTextArea txtList = add(new FTextArea(false));
    private Loaded loaded; // null until a deck with cards is pasted or chosen

    /**
     * @param seatName  whose deck it is, for the title
     * @param onLoaded0 given the deck when the operator uses it, on the UI thread
     */
    public CompanionDeckDialog(String seatName, Consumer<Loaded> onLoaded0) {
        super(seatName + "'s deck", 2);
        onLoaded = onLoaded0;
        btnPaste.setCommand(e -> paste());
        btnSaved.setCommand(e -> chooseSaved());
        initButton(USE, "Use", e -> {
            hide();
            onLoaded.accept(loaded);
        });
        initButton(1, "Cancel", e -> hide());
        setButtonEnabled(USE, false);
    }

    private void paste() {
        String text = Forge.getClipboard().getContents();
        if (text == null || DeckListNames.cardCount(text) == 0) {
            FOptionPane.showMessageDialog("The clipboard has no decklist. Copy one, as from a deck website or "
                    + "Forge's deck editor, then paste it here.");
            return;
        }
        preview(new Loaded(text, knownCommanders(DeckListNames.commanderNames(text))));
    }

    private void chooseSaved() {
        List<Deck> decks = new ArrayList<>();
        FModel.getDecks().getCommander().forEach(decks::add);
        FModel.getDecks().getConstructed().forEach(decks::add);
        if (decks.isEmpty()) {
            FOptionPane.showMessageDialog("There are no saved Commander or Constructed decks.");
            return;
        }
        GuiChoose.oneOrNone("Choose a saved deck", decks, deck -> {
            if (deck == null) {
                return;
            }
            List<String> commanders = new ArrayList<>();
            for (PaperCard card : deck.getCommanders()) {
                commanders.add(card.getName());
            }
            preview(new Loaded(deck.generateTextExport(), knownCommanders(commanders)));
        });
    }

    private void preview(Loaded deck) {
        loaded = deck;
        lblSummary.setText(deck.summary());
        txtList.setText(deck.deckList());
        txtList.setScrollTop(0);
        setButtonEnabled(USE, deck.cardCount() > 0);
    }

    /**
     * The names that are cards Forge has and that can be a commander, spelled as its database has them; at most two,
     * as a player has at most two commanders. A name it doesn't know is left out, for the commander step to ask.
     */
    private static List<String> knownCommanders(List<String> names) {
        List<String> known = new ArrayList<>();
        for (PaperCard card : CommanderLegality.commanders(names)) {
            if (known.size() < 2 && CommanderLegality.canBeCommander(card) && !known.contains(card.getName())) {
                known.add(card.getName());
            }
        }
        return known;
    }

    @Override
    protected float layoutAndGetHeight(float width, float maxHeight) {
        float rowHeight = FTextField.getDefaultHeight();
        float w = width - 2 * PADDING;
        float y = PADDING;
        float half = (w - PADDING) / 2;
        btnPaste.setBounds(PADDING, y, half, rowHeight);
        btnSaved.setBounds(btnPaste.getRight() + PADDING, y, half, rowHeight);
        y += rowHeight + PADDING;
        lblSummary.setBounds(PADDING, y, w, rowHeight);
        y += rowHeight + PADDING;
        float listHeight = Math.max(rowHeight, Math.min(maxHeight - y - PADDING, rowHeight * 8));
        txtList.setBounds(PADDING, y, w, listHeight);
        return y + listHeight + PADDING;
    }
}
