package forge.screens.companion;

import forge.game.card.CardView;
import forge.game.player.PlayerView;
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
import java.util.function.Consumer;

/**
 * Receives taps from the match panels while no match runs. Only tapping a card or a player means anything to a
 * tracker, so everything else does nothing. Tap callbacks arrive on the game thread.
 */
class CompanionController implements IGameController {
    private final Consumer<CardView> onCard;
    private final Consumer<PlayerView> onPlayer;

    CompanionController(Consumer<CardView> onCard0, Consumer<PlayerView> onPlayer0) {
        onCard = onCard0;
        onPlayer = onPlayer0;
    }

    @Override
    public boolean selectCard(CardView cardView, List<CardView> otherCardViewsToSelect, ITriggerEvent triggerEvent) {
        onCard.accept(cardView);
        return true;
    }

    @Override
    public void selectPlayer(PlayerView playerView, ITriggerEvent triggerEvent) {
        onPlayer.accept(playerView);
    }

    @Override
    public String getActivateDescription(CardView card) {
        return "Move";
    }

    @Override public boolean mayLookAtAllCards() { return true; }
    @Override public boolean canPlayUnlimitedLands() { return false; }
    @Override public void concede() { }
    @Override public void drawOfferAction(DrawOfferMessage.Action action) { }
    @Override public void alphaStrike() { }
    @Override public void useMana(byte color) { }
    @Override public void selectButtonOk() { }
    @Override public void selectButtonCancel() { }
    @Override public void selectAbility(SpellAbilityView sa) { }
    @Override public void selectStackItem(StackItemView item) { }
    @Override public void undoLastAction() { }
    @Override public IDevModeCheats cheat() { return null; }
    @Override public IMacroSystem macros() { return null; }
    @Override public void nextGameDecision(NextGameDecision decision) { }
    @Override public void reorderHand(CardView card, int index) { }
    @Override public void requestResync() { }
    @Override public boolean shouldAutoYield(String key) { return false; }
    @Override public void setShouldAutoYield(String key, boolean autoYield, boolean isAbilityScope) { }
    @Override public void setDisableAutoYields(boolean disable) { }
    @Override public TriggerDecision getTriggerDecision(String key) { return null; }
    @Override public void setTriggerDecision(String key, TriggerDecision decision, boolean isAbilityScope) { }
    @Override public void setDisableAutoTriggers(boolean disable) { }
    @Override public void applyYieldUpdate(YieldUpdate update) { }
    @Override public YieldController getYieldController() { return null; }
    @Override public void setYieldPref(FPref pref, String value) { }
}
