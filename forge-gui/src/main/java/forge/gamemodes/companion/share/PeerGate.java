package forge.gamemodes.companion.share;

import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.player.actions.PlayerAction;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.gamemodes.match.DrawOfferMessage;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.match.YieldController;
import forge.gamemodes.match.YieldUpdate;
import forge.interfaces.IDevModeCheats;
import forge.interfaces.IGameController;
import forge.interfaces.IMacroSystem;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.player.AutoYieldStore.TriggerDecision;
import forge.util.ITriggerEvent;

import java.util.List;

/**
 * What the host runs a joined phone's screen calls on. In M1 every phone is read-only (AC-108), so the gate drops
 * every call, concede, draw offers and undo included, and answers a question with null, as net play does for a
 * client whose controller is gone ({@code GameProtocolHandler}). A resync request is handled before the gate, by the
 * share host's handler. M2 replaces the drop with a forward to the current seat while the phone has the floor.
 */
public final class PeerGate implements IGameController {

    /** Runs nothing either: a phone's macro buttons reach the host's macro system through here. */
    private static final IMacroSystem NO_MACROS = new IMacroSystem() {
        @Override
        public void addRememberedAction(PlayerAction action) {
        }

        @Override
        public void setRememberedActions() {
        }

        @Override
        public void nextRememberedAction() {
        }

        @Override
        public String playbackText() {
            return null;
        }
    };

    @Override
    public boolean mayLookAtAllCards() {
        return false;
    }

    @Override
    public boolean canPlayUnlimitedLands() {
        return false;
    }

    @Override
    public void concede() {
    }

    @Override
    public void drawOfferAction(DrawOfferMessage.Action action) {
    }

    @Override
    public void alphaStrike() {
    }

    @Override
    public void useMana(byte color) {
    }

    @Override
    public void selectButtonOk() {
    }

    @Override
    public void selectButtonCancel() {
    }

    @Override
    public void selectPlayer(PlayerView playerView, ITriggerEvent triggerEvent) {
    }

    @Override
    public boolean selectCard(CardView cardView, List<CardView> otherCardViewsToSelect, ITriggerEvent triggerEvent) {
        return false;
    }

    @Override
    public void selectAbility(SpellAbilityView sa) {
    }

    @Override
    public void selectStackItem(StackItemView item) {
    }

    @Override
    public void undoLastAction() {
    }

    @Override
    public IDevModeCheats cheat() {
        return IDevModeCheats.NO_CHEAT;
    }

    @Override
    public IMacroSystem macros() {
        return NO_MACROS;
    }

    @Override
    public void nextGameDecision(NextGameDecision decision) {
    }

    @Override
    public String getActivateDescription(CardView card) {
        return null;
    }

    @Override
    public void reorderHand(CardView card, int index) {
    }

    @Override
    public void requestResync() {
        // the share host's handler marks the resync before the call reaches here
    }

    @Override
    public boolean shouldAutoYield(String key) {
        return false;
    }

    @Override
    public void setShouldAutoYield(String key, boolean autoYield, boolean isAbilityScope) {
    }

    @Override
    public void setDisableAutoYields(boolean disable) {
    }

    @Override
    public TriggerDecision getTriggerDecision(String key) {
        return null;
    }

    @Override
    public void setTriggerDecision(String key, TriggerDecision decision, boolean isAbilityScope) {
    }

    @Override
    public void setDisableAutoTriggers(boolean disable) {
    }

    @Override
    public void applyYieldUpdate(YieldUpdate update) {
    }

    @Override
    public void sendYieldUpdate(YieldUpdate update) {
    }

    @Override
    public YieldController getYieldController() {
        return null;
    }

    @Override
    public void setYieldPref(FPref pref, String value) {
    }
}
