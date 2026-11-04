package forge.screens.match;

import forge.game.player.PlayerView;
import forge.menu.FDropDownMenu;

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

    /** Runs the main action, on the UI thread. */
    default void runPromptAction() {
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
     * Whether the match screen closes as soon as the game is over, without the results screen. Asked on the UI thread
     * when the game is over; a mode that ends the game itself to leave the match says yes.
     */
    default boolean leavesWhenGameOver() {
        return false;
    }
}
