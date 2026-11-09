package forge.screens.match;

import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.menu.FDropDownMenu;
import forge.menu.FMenuItem;

import java.util.List;

/**
 * What a game mode adds to, or changes in, the match screen for one match. Set after
 * {@link MatchController#hostMatch()}, which clears it, so it can't outlive that match. Every method has a default that
 * leaves the screen as it is.
 */
public interface MatchExtension {
    /** Adds items to the top of the game menu. */
    default void addGameMenuItems(FDropDownMenu menu) {
    }

    /**
     * Items for a card's menu, which a long press on a card in a player's zones opens (a right-click on desktop), with
     * the card's zoom as its last item. Asked on the UI thread, each time the menu opens; with no items, the press
     * shows the zoom as usual.
     */
    default List<FMenuItem> cardMenuItems(CardView card) {
        return List.of();
    }

    /**
     * Whether local players' hands are open when the match starts. A tracker for a paper game knows no hands, so its
     * rows would only show card backs.
     */
    default boolean showsHandsAtStart() {
        return true;
    }

    /** Whether a second local player gets a prompt at the top, upside down. One person answering for all says no. */
    default boolean showsTopPrompt() {
        return true;
    }

    /**
     * The label of the match's main action, shown as a button in the middle of the prompt bar with the prompt's message
     * moved above it, or null for the usual prompt bar.
     */
    default String promptActionLabel() {
        return null;
    }

    /**
     * The main action's label for {@code owner}'s prompt, asked on the UI thread each time that prompt's buttons change,
     * so the action can follow the prompt. Only a prompt that has the button (the bottom one) is relabelled.
     */
    default String promptActionLabel(PlayerView owner) {
        return promptActionLabel();
    }

    /** Runs the main action, on the UI thread. */
    default void runPromptAction() {
    }

    /**
     * A line added under the message of {@code player}'s prompt, or null for none. Asked on the UI thread each time a
     * prompt message is shown.
     */
    default String promptNote(PlayerView player) {
        return null;
    }

    /**
     * Handles a tap on a player's life total, on the UI thread.
     *
     * @return true if the mode handled the tap, so the usual player selection is skipped
     */
    default boolean tapLife(PlayerView player) {
        return false;
    }

    /**
     * Whether the game menu offers Forge's Concede, and Ctrl+Q concedes. A mode whose game a concede doesn't end, as
     * it has its own way out, says no.
     */
    default boolean offersConcede() {
        return true;
    }

    /**
     * Whether the match screen closes as soon as the game is over, without the results screen. Asked on the UI thread
     * when the game is over; a mode that ends the game itself to leave the match says yes.
     */
    default boolean leavesWhenGameOver() {
        return false;
    }

    /**
     * Whether the game menu shows only this mode's items, without Forge's own (Concede, Offer Draw, yields, Settings),
     * as for a game this device only watches.
     */
    default boolean replacesGameMenu() {
        return false;
    }

    /**
     * Whether this extension is for a game hosted on another device and only shown here, through the network. Such a
     * match has no {@link forge.gamemodes.match.HostedMatch} here to tell it apart from another game, so the extension
     * applies for as long as it says yes; it says no once its mode has left the game.
     */
    default boolean followsRemoteGame() {
        return false;
    }

    /**
     * Whether this mode tells of a lost connection and its reconnect itself, so net play's reconnect dialogs (which
     * count attempts and give up) don't show for it.
     */
    default boolean showsOwnReconnectState() {
        return false;
    }

    /**
     * Called on the UI thread when the match screen is left after its game ended, and when another match is hosted.
     * May be called more than once.
     */
    default void matchLeft() {
    }
}
