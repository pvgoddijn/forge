package forge.screens.companion;

import forge.Forge;
import forge.assets.FSkinFont;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gamemodes.companion.CompanionGame;
import forge.gamemodes.companion.CompanionState;
import forge.gamemodes.companion.CompanionZone;
import forge.gui.FThreads;
import forge.screens.FScreen;
import forge.screens.home.NewGameMenu;
import forge.screens.match.CardFlightOverlay;
import forge.screens.match.MatchController;
import forge.screens.match.views.VCardDisplayArea;
import forge.screens.match.views.VPlayerPanel;
import forge.toolbox.FButton;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FScrollPane;
import forge.toolbox.FTextField;
import forge.toolbox.GuiChoose;
import forge.util.Utils;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Every player's board, drawn by Forge's own match panels from the tracked state. Tapping a card moves it. Tapping a
 * player's avatar or a hidden card changes that player's hidden hand count. No match is running.
 */
public class CompanionGameScreen extends FScreen {
    private static final float PADDING = Utils.scale(5);
    private static final float NAME_HEIGHT = Utils.scale(24);
    private static final float MIN_PANEL_HEIGHT = Utils.scale(120);
    private static final FSkinFont NAME_FONT = FSkinFont.get(14);
    private static final ZoneType[] REFRESHED_ZONES = {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command};

    private final CompanionGame game;
    private final CompanionBoard board;
    private final List<VPlayerPanel> panels = new ArrayList<>();
    private final List<FLabel> names = new ArrayList<>();
    private final PlayerList playerList = add(new PlayerList());
    private final FButton btnAddCard = add(new FButton("Add card"));
    private final CompanionController controller = new CompanionController(this::onCardTapped, this::onPlayerTapped);

    public CompanionGameScreen(CompanionGame game0) {
        super("Companion", NewGameMenu.getMenu());
        game = game0;
        board = new CompanionBoard(game.state());
        int playerCount = board.players().size();
        for (PlayerView player : board.players()) {
            FLabel name = playerList.add(new FLabel.Builder().font(NAME_FONT).opaque().build());
            names.add(name);
            VPlayerPanel panel = playerList.add(new VPlayerPanel(player, false, playerCount));
            panel.showOnlyZones(EnumSet.of(ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command));
            panels.add(panel);
        }
        btnAddCard.setCommand(e -> new CardSearchDialog(game, this::refresh).show());
        refresh();
    }

    @Override
    public void onActivate() {
        super.onActivate();
        // the panels send taps to the match controller: with no match running, it hands them to ours
        MatchController.instance.resetForNewMatch();
        MatchController.instance.setSpectator(controller);
        // a finished match leaves its card panels and animation settings behind
        VCardDisplayArea.CardAreaPanel.resetForNewGame();
        CardFlightOverlay.disable();
        refresh();
    }

    @Override
    public void onSwitchAway(Consumer<Boolean> canSwitchCallback) {
        MatchController.instance.setSpectator(null);
        super.onSwitchAway(canSwitchCallback);
    }

    private void refresh() {
        CompanionState state = game.state();
        board.sync(state);
        for (int i = 0; i < panels.size(); i++) {
            CompanionState.PlayerState player = state.players().get(i);
            names.get(i).setText(player.name() + (player.me() ? " (Me)" : "") + " - Hand: " + player.hiddenHand()
                    + " hidden, " + player.knownHand().size() + " known");
            for (ZoneType zone : REFRESHED_ZONES) {
                panels.get(i).updateZone(zone);
            }
        }
        playerList.revalidate();
    }

    private void onCardTapped(CardView card) {
        FThreads.invokeInEdtLater(() -> {
            if (CompanionBoard.isHidden(card)) {
                changeHand(board.ownerOf(card));
                return;
            }
            CompanionState state = game.state();
            for (int player = 0; player < state.players().size(); player++) {
                for (CompanionZone zone : CompanionZone.values()) {
                    for (CompanionState.GameCard gameCard : state.players().get(player).zone(zone)) {
                        if (gameCard.instanceId() == card.getId()) {
                            new CardMoveDialog(game, gameCard, player, zone, this::refresh).show();
                            return;
                        }
                    }
                }
            }
        });
    }

    private void onPlayerTapped(PlayerView player) {
        FThreads.invokeInEdtLater(() -> changeHand(board.players().indexOf(player)));
    }

    private void changeHand(int player) {
        String more = "Hand +1 (drew a card)";
        String less = "Hand -1 (discarded or played)";
        GuiChoose.oneOrNone(game.state().players().get(player).name(), Arrays.asList(more, less), choice -> {
            if (choice != null && save(() -> game.changeHandCount(player, choice.equals(more) ? 1 : -1))) {
                refresh();
            }
        });
    }

    /** Runs a change to the game, and tells the operator if it couldn't be saved. It then didn't happen. */
    static boolean save(Runnable change) {
        try {
            change.run();
            return true;
        } catch (UncheckedIOException e) {
            FOptionPane.showErrorDialog("That couldn't be saved, so it didn't happen.\n" + e.getMessage());
            return false;
        }
    }

    static String zoneName(CompanionZone zone) {
        switch (zone) {
            case HAND: return Forge.getLocalizer().getMessageorUseDefault("lblHand", "Hand");
            case BATTLEFIELD: return Forge.getLocalizer().getMessageorUseDefault("lblBattlefield", "Battlefield");
            case GRAVEYARD: return Forge.getLocalizer().getMessageorUseDefault("lblGraveyard", "Graveyard");
            case EXILE: return Forge.getLocalizer().getMessageorUseDefault("lblExile", "Exile");
            default: return "Command";
        }
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float buttonHeight = FTextField.getDefaultHeight() * 1.2f;
        btnAddCard.setBounds(PADDING, height - buttonHeight - PADDING, width - 2 * PADDING, buttonHeight);
        playerList.setBounds(0, startY, width, btnAddCard.getTop() - PADDING - startY);
    }

    /** The boards fill the screen if they fit at a usable size, and scroll if they don't. */
    private class PlayerList extends FScrollPane {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            float panelHeight = Math.max(MIN_PANEL_HEIGHT, visibleHeight / panels.size() - NAME_HEIGHT);
            float y = 0;
            for (int i = 0; i < panels.size(); i++) {
                FDisplayObject name = names.get(i);
                name.setBounds(0, y, visibleWidth, NAME_HEIGHT);
                y += NAME_HEIGHT;
                panels.get(i).setBounds(0, y, visibleWidth, panelHeight);
                y += panelHeight;
            }
            return new ScrollBounds(visibleWidth, y);
        }
    }
}
