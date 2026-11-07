package forge.screens.companion;

import com.badlogic.gdx.utils.Align;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gamemodes.companion.CardCamera;
import forge.gamemodes.companion.CardCameras;
import forge.gamemodes.companion.CardNameMatcher;
import forge.gamemodes.companion.CardNameReader;
import forge.gui.FThreads;
import forge.item.PaperCard;
import forge.toolbox.FButton;
import forge.toolbox.FDialog;
import forge.toolbox.FList;
import forge.toolbox.FOptionPane;
import forge.toolbox.FTextField;
import forge.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Picks a card by photo, falling back to typing (slice 1), and hands its name to a callback. {@link #pickByPhoto}
 * opens the camera first and shows the dialog only with what the photo gave; {@link #pickByName} opens it to type.
 * <p>
 * An optional filter (slice 6: only cards that can be a commander) keeps the suggestions, typed or photographed, to
 * cards in Forge's database that pass it, and then a name can't be picked as typed. Without one, text that matches
 * nothing can be picked as typed, which is how emblems get in, as Forge has no list of them.
 * <p>
 * {@link CardSearchDialog} is a picker with the player, from and mode fields above the search.
 */
public class CardPicker extends FDialog {
    protected static final float PADDING = Utils.scale(5);
    private static final float ITEM_HEIGHT = Utils.scale(26);
    private static final int MAX_MATCHES = 30;
    private static final int PICK = 0;
    private static final int CANCEL = 1;
    private static final int PHOTO = 2;
    private static CardNameMatcher matcher; // built once, on first use: it indexes every card name

    private final String title;
    private final String pickLabel;
    private final Predicate<PaperCard> filter; // null for every card
    private final FTextField txtSearch = add(new FTextField());
    private final FList<CardNameMatcher.Suggestion> lstMatches = add(new FList<>());
    private Consumer<String> onPick;
    private Runnable onCancel;
    private FButton btnSkip; // null without a skip
    private String selectedCard; // a name from the database; null while the text is only what was typed
    private boolean photographing; // a photo is being taken or read; UI thread only
    private boolean everShown; // false until the first photo comes back, so a hidden dialog wasn't closed by the operator
    private boolean fromPhoto; // whether the search shows a photo's read, not touched by typing since

    /**
     * @param title0  what is being picked, such as "Alice's commander"
     * @param filter0 the cards that can be picked, or null for any card, token or typed name
     * @param onPick0 given the picked name, on the UI thread
     */
    public CardPicker(String title0, Predicate<PaperCard> filter0, Consumer<String> onPick0) {
        this(title0, "Choose", filter0);
        onPick = onPick0;
    }

    /** For a subclass that handles the pick itself, in {@link #picked}. */
    protected CardPicker(String title0, String pickLabel0, Predicate<PaperCard> filter0) {
        super(title0, canPhotograph() ? 3 : 2);
        title = title0;
        pickLabel = pickLabel0;
        filter = filter0;

        txtSearch.setGhostText(filter == null ? "Card or token name" : "Card name");
        txtSearch.setLiveChangeEvents(true);
        txtSearch.setChangedHandler(e -> {
            fromPhoto = false; // only typing fires this
            updateMatches();
        });

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

        initButton(PICK, typedLabel(), e -> pick());
        setButtonEnabled(PICK, filter == null);
        initButton(CANCEL, "Cancel", e -> cancel());
        if (canPhotograph()) {
            initButton(PHOTO, "Photo", e -> photograph());
        }
    }

    /** Run when the operator cancels, with nothing picked. */
    public CardPicker withCancel(Runnable onCancel0) {
        onCancel = onCancel0;
        return this;
    }

    /** Adds a button below the list, such as "No commander", that closes the picker with nothing picked. */
    public CardPicker withSkip(String label, Runnable onSkip) {
        btnSkip = add(new FButton(label));
        btnSkip.setCommand(e -> {
            hide();
            onSkip.run();
        });
        return this;
    }

    /**
     * Photographs a card first and shows the dialog only once there is a photo: filled in with the read, so a confident
     * one takes one tap to pick (AC-8), or with the text read to finish by typing (AC-11). No photo, as when the camera
     * is closed or refused, opens it empty to type. A device without a camera opens it to type straight away.
     */
    public void pickByPhoto() {
        if (!canPhotograph()) {
            everShown = true;
            show();
            return;
        }
        photograph();
    }

    /** Opens the dialog to type a name, without the camera: for tokens and emblems, which have no card to photograph. */
    public void pickByName() {
        everShown = true;
        show();
    }

    /**
     * Whether the last pick came from a photo, so a refused card is retaken with the camera; otherwise it was typed,
     * or the read was changed by typing, and is edited in the search.
     */
    protected boolean pickedFromPhoto() {
        return fromPhoto && canPhotograph();
    }

    /**
     * After a pick was refused: a new photo, the dialog showing again once there is one, as {@link #pickByPhoto}
     * does. Whatever the subclass shows above the search stays as it was.
     */
    protected void retake() {
        if (!canPhotograph()) {
            edit();
            return;
        }
        everShown = false;
        photograph();
    }

    /** After a pick was refused: shows the dialog again as it was picked from, to change the search. */
    protected void edit() {
        everShown = true;
        show();
    }

    /** Whether this device can photograph a card and read its name (slice 1). Not on iOS, which has no reader. */
    protected static boolean canPhotograph() {
        CardCamera camera = CardCameras.get();
        return camera != null && camera.canPhotograph() && camera.reader() != null;
    }

    /** Called with the card chosen, or null when the choice is cleared, before the operator picks. */
    protected void onSelectionChanged(String card) {
    }

    /** Whether the rest of the dialog lets the pick go ahead; when not, the dialog stays open. */
    protected boolean canPick() {
        return true;
    }

    /** Handles the picked name, after the dialog is hidden. */
    protected void picked(String name) {
        onPick.accept(name);
    }

    /** Lays out what a subclass shows above the search box, from {@code y}, and returns where the search box goes. */
    protected float layoutAboveSearch(float x, float y, float width) {
        return y;
    }

    private String typedLabel() {
        return filter == null ? pickLabel + " as typed" : pickLabel;
    }

    private void cancel() {
        hide();
        if (onCancel != null) {
            onCancel.run();
        }
    }

    /**
     * Takes a photo and reads it off the UI thread (AC-6, AC-7). A confident read lists the best card and its
     * alternatives here, the best already chosen, so it takes one tap to pick (AC-8). Otherwise the text read is left
     * in the search box, to finish by typing (AC-11). No photo, as when the camera is refused, leaves the search as it
     * was.
     */
    private void photograph() {
        if (photographing) {
            return; // one photo at a time: a second one would overwrite the first one's suggestions
        }
        photographing = true;
        CardCamera camera = CardCameras.get();
        CardNameReader reader = camera.reader();
        camera.photograph(photo -> {
            if (photo == null) {
                FThreads.invokeInEdtLater(() -> {
                    photographing = false;
                    showIfHidden(); // the camera was the first step: without a photo, type instead
                });
                return;
            }
            FThreads.invokeInEdtLater(() -> {
                showIfHidden();
                getButton(PHOTO).setText("Reading...");
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

    private void showIfHidden() {
        if (!everShown) {
            everShown = true;
            show();
        }
    }

    private void showReading(CardNameReader.Reading reading) {
        photographing = false;
        getButton(PHOTO).setText("Photo");
        if (!isVisible()) {
            return; // the operator moved on while it was reading
        }
        fromPhoto = true;
        List<CardNameMatcher.Suggestion> suggestions = new ArrayList<>();
        for (String card : reading.cards()) {
            if (CardNameMatcher.passes(card, filter)) {
                suggestions.add(new CardNameMatcher.Suggestion(card, 1));
            }
        }
        if (reading.confident() && !suggestions.isEmpty()) {
            String best = suggestions.get(0).card();
            txtSearch.setText(best);
            lstMatches.setListData(suggestions);
            lstMatches.setScrollTop(0);
            lstMatches.revalidate();
            select(best);
            return;
        }
        txtSearch.setText(reading.textRead());
        updateMatches();
        if (reading.confident() && !reading.cards().isEmpty()) {
            FOptionPane.showMessageDialog(reading.cards().get(0) + " can't be chosen as " + title
                    + ". Try another photo, or type the name.");
        } else if (reading.textRead().isEmpty()) {
            FOptionPane.showMessageDialog("The card's name couldn't be read. Try another photo, or type the name.");
        }
    }

    private void updateMatches() {
        if (matcher == null) {
            matcher = CardNameMatcher.fromStaticData();
        }
        List<CardNameMatcher.Suggestion> matches = matcher.matchWhere(txtSearch.getText(), MAX_MATCHES, filter);
        lstMatches.setListData(matches);
        lstMatches.setScrollTop(0);
        lstMatches.revalidate();
        if (!matches.isEmpty() && matches.get(0).confidence() == 1) {
            select(matches.get(0).card()); // typed in full, so it needn't be tapped too
        } else if (selectedCard != null) {
            selectedCard = null;
            getButton(PICK).setText(typedLabel());
            setButtonEnabled(PICK, filter == null);
            onSelectionChanged(null);
        }
    }

    private void select(String card) {
        selectedCard = card;
        getButton(PICK).setText(pickLabel);
        setButtonEnabled(PICK, true);
        onSelectionChanged(card);
    }

    private void pick() {
        String name = selectedCard != null ? selectedCard : filter == null ? txtSearch.getText().trim() : "";
        if (name.isEmpty() || !canPick()) {
            return;
        }
        hide();
        picked(name);
    }

    @Override
    protected final float layoutAndGetHeight(float width, float maxHeight) {
        float rowHeight = FTextField.getDefaultHeight();
        float w = width - 2 * PADDING;
        float y = layoutAboveSearch(PADDING, PADDING, w);
        txtSearch.setBounds(PADDING, y, w, rowHeight);
        y += rowHeight + PADDING;
        float skipHeight = btnSkip == null ? 0 : rowHeight + PADDING;
        // tall enough that the drop-downs above, which open downward, stay on the screen
        float listHeight = Math.min(maxHeight - y - PADDING - skipHeight, ITEM_HEIGHT * 7);
        lstMatches.setBounds(PADDING, y, w, listHeight);
        y += listHeight + PADDING;
        if (btnSkip != null) {
            btnSkip.setBounds(PADDING, y, w, rowHeight);
            y += skipHeight;
        }
        return y;
    }
}
