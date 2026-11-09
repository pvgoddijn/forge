package forge.gamemodes.net.client;

import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GameClientHandler#resetTracker}: the card views go, the rest stays. A handler only gets its tracker from a
 * game view off the wire, which needs a whole game, so the dropping is checked on a tracker, and the handler before its
 * first game view.
 */
public class GameClientHandlerShould {
    @Test
    public void dropEveryCardViewAndKeepThePlayers() {
        Tracker tracker = new Tracker();
        tracker.putObj(TrackableTypes.CardViewType, 1, new CardView(1, tracker));
        tracker.putObj(TrackableTypes.CardViewType, 2, new CardView(2, tracker));
        tracker.putObj(TrackableTypes.PlayerViewType, 1, new PlayerView(1, tracker));

        tracker.removeAll(TrackableTypes.CardViewType);

        assertThat(tracker.hasObj(TrackableTypes.CardViewType, 1)).isFalse();
        assertThat(tracker.hasObj(TrackableTypes.CardViewType, 2)).isFalse();
        assertThat(tracker.hasObj(TrackableTypes.PlayerViewType, 1)).isTrue();
        tracker.putObj(TrackableTypes.CardViewType, 1, new CardView(1, tracker));
        assertThat(tracker.hasObj(TrackableTypes.CardViewType, 1)).isTrue();
    }

    @Test
    public void resetNothingBeforeTheFirstGameView() {
        GameClientHandler handler = new GameClientHandler(new FGameClient("Ann", null, "localhost", 1));

        handler.resetTracker(); // no tracker yet: nothing to drop, and no failure
    }
}
