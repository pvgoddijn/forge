package forge.screens.companion;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.share.ActiveGame;
import forge.screens.FScreen;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.toolbox.FOptionPane;
import forge.util.Localizer;
import forge.util.Utils;

import java.io.UncheckedIOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The games that haven't ended, most recently played first, each with when it was last played and its players: "Load
 * game". Several may be unfinished side by side. Tapping a game of this phone's own offers Continue or End game (it
 * then goes to the past games, after a confirm). A game followed from another phone is marked so, and offers "Join
 * again" or "Stop following" instead: it is never continued here, as loading its state would write to the copy that
 * must stay the host's. Listing writes nothing to a game.
 */
public class CompanionLoadGameScreen extends FScreen {
    private static final float PADDING = Utils.scale(5);
    private static final FSkinFont DATE_FONT = FSkinFont.get(11);
    private static final FSkinFont SEATS_FONT = FSkinFont.get(14);

    private final CompanionStore store;
    /** Continues a game of this phone's own: the setup screen's way of opening a game on the match screen. */
    private final BiConsumer<String, CompanionSetup> openGame;
    private final FList<ActiveGame> lstGames = add(new FList<>());
    private final FLabel lblEmpty = add(new FLabel.Builder().align(Align.center)
            .text(Forge.getLocalizer().getMessage("lblCompanionNoUnfinishedGames")).build());

    public CompanionLoadGameScreen(CompanionStore store, BiConsumer<String, CompanionSetup> openGame) {
        super(Forge.getLocalizer().getMessage("lblCompanionLoadGame"));
        this.store = store;
        this.openGame = openGame;
        lstGames.setListItemRenderer(new GameRenderer());
    }

    @Override
    public void onActivate() {
        super.onActivate();
        refresh();
    }

    /** Lists the unfinished games again, as one may have been ended, or set aside as damaged. */
    private void refresh() {
        // a game that can't be read is told of on the setup screen, which lists them too
        List<ActiveGame> games = ActiveGame.list(store);
        lstGames.setListData(games);
        lblEmpty.setVisible(games.isEmpty());
        revalidate();
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        lstGames.setBounds(0, startY, width, height - startY);
        lblEmpty.setBounds(PADDING, startY + PADDING, width - 2 * PADDING,
                lblEmpty.getAutoSizeBounds().height + 2 * PADDING);
    }

    private static String date(ActiveGame game) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(game.game().lastPlayed()));
    }

    /** The game's first line: when it was last played, and whether it's followed. */
    private static String heading(ActiveGame game) {
        return game.followed() ? Forge.getLocalizer().getMessage("lblCompanionFollowedMark", date(game)) : date(game);
    }

    private void choose(ActiveGame game) {
        if (game.followed()) {
            offerFollowed(game);
        } else {
            offerOwn(game);
        }
    }

    /** A game of this phone's own: Continue opens it; End game, once confirmed, makes it a past game. */
    private void offerOwn(ActiveGame game) {
        Localizer localizer = Forge.getLocalizer();
        FOptionPane.showOptionDialog(localizer.getMessage("lblCompanionUnfinishedGame"),
                String.join(", ", game.game().seatNames()), null,
                List.of(localizer.getMessage("lblCompanionEndGame"), localizer.getMessage("lblCompanionContinue")), 1,
                choice -> {
            if (choice == null) {
                return;
            }
            if (choice == 1) {
                openGame.accept(game.id(), game.game().setup());
                refresh(); // a save that didn't read is set aside by then
            } else {
                confirmEnd(game);
            }
        });
    }

    private void confirmEnd(ActiveGame game) {
        Localizer localizer = Forge.getLocalizer();
        FOptionPane.showConfirmDialog(localizer.getMessage("lblCompanionEndGameConfirm"),
                localizer.getMessage("lblCompanionEndGame"), localizer.getMessage("lblCompanionEndGame"),
                localizer.getMessage("lblCancel"), false, yes -> {
            if (!yes) {
                return;
            }
            try {
                game.end(store);
            } catch (UncheckedIOException e) {
                FOptionPane.showErrorDialog(localizer.getMessage("lblCompanionEndGameFailed", e.getMessage()));
            }
            refresh();
        });
    }

    /**
     * A game followed from another phone, left without ending (closed, killed, or Leave): "Stop following" makes it a
     * past game; "Join again" joins its host with the code it was joined with, asking only for what the copy lacks.
     * Never continued with {@code openGame}: loading its state would write to the host's copy.
     */
    private void offerFollowed(ActiveGame game) {
        Localizer localizer = Forge.getLocalizer();
        FOptionPane.showOptionDialog(localizer.getMessage("lblCompanionFollowedGame"),
                localizer.getMessage("lblCompanionJoinGame"), null,
                List.of(localizer.getMessage("lblCompanionStopFollowing"),
                        localizer.getMessage("lblCompanionJoinAgain")), 1, choice -> {
            if (choice == null) {
                return;
            }
            if (choice == 0) {
                try {
                    game.stopFollowing(store);
                } catch (UncheckedIOException e) {
                    FOptionPane.showErrorDialog(localizer.getMessage("lblCompanionStopFollowingFailed",
                            e.getMessage()));
                }
                refresh();
            } else {
                Forge.openScreen(new JoinGameScreen(store, game.id(), game.code().orElse(null)));
            }
        });
    }

    private class GameRenderer extends FList.ListItemRenderer<ActiveGame> {
        @Override
        public float getItemHeight() {
            return DATE_FONT.getLineHeight() + SEATS_FONT.getLineHeight() + 3 * FList.PADDING;
        }

        @Override
        public boolean tap(Integer index, ActiveGame value, float x, float y, int count) {
            choose(value);
            return true;
        }

        @Override
        public void drawValue(Graphics g, Integer index, ActiveGame value, FSkinFont font, FSkinColor foreColor,
                              FSkinColor backColor, boolean pressed, float x, float y, float w, float h) {
            float dateHeight = DATE_FONT.getLineHeight();
            g.drawText(heading(value), DATE_FONT, foreColor.alphaColor(0.75f), x, y, w, dateHeight, false,
                    Align.left, false);
            float seatsY = y + dateHeight + FList.PADDING;
            g.drawText(String.join(", ", value.game().seatNames()), SEATS_FONT, foreColor, x, seatsY, w,
                    h - (seatsY - y), false, Align.left, false);
        }
    }
}
