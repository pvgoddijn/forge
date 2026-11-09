package forge.gamemodes.net.client;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Net play's reconnect and connection timings, which Companion overrides through hooks (multi-phone slice 04): a plain
 * client keeps them as they were before the hooks.
 */
public class FGameClientDefaultsShould {
    private final FGameClient client = new FGameClient("Ann", null, "localhost", 1);

    @Test
    public void waitOneFiveFifteenFortyFiveAndSixtySecondsThenGiveUp() {
        assertThat(client.reconnectDelayMillis(0)).isEqualTo(1_000);
        assertThat(client.reconnectDelayMillis(1)).isEqualTo(5_000);
        assertThat(client.reconnectDelayMillis(2)).isEqualTo(15_000);
        assertThat(client.reconnectDelayMillis(3)).isEqualTo(45_000);
        assertThat(client.reconnectDelayMillis(4)).isEqualTo(60_000);
        assertThat(client.reconnectDelayMillis(5)).isEqualTo(FGameClient.GIVE_UP);
        assertThat(FGameClient.getTotalReconnectAttempts()).isEqualTo(5);
    }

    @Test
    public void keepNetPlaysTimeouts() {
        assertThat(client.readerIdleSeconds()).isEqualTo(45);
        assertThat(client.heartbeatIntervalSeconds()).isEqualTo(FGameClient.HEARTBEAT_INTERVAL_SECONDS);
        assertThat(client.connectTimeoutMillis()).isEqualTo(30_000); // Netty's own default
    }

    /** Net play keys a new tracker on each connection's first game view, and never takes the shown view's. */
    @Test
    public void keepNoTrackerAcrossConnections() {
        assertThat(client.keepsTrackerAcrossConnections()).isFalse();
        client.ownViewArrived();
        assertThat(client.reusesShownTracker()).isFalse();
    }

    @Test
    public void startNoReconnectForAClientThatWasNeverSeated() {
        client.startReconnecting(); // not seated: as for a refused login, nothing to reconnect to

        assertThat(client.getReconnectState()).isEqualTo(FGameClient.ReconnectState.CONNECTED);
        client.close();
    }
}
