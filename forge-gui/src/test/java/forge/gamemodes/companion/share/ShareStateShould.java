package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code share.json} as slice 04 keeps it: hosted or followed, the token for the game, read back with a real store. */
public class ShareStateShould {
    private CompanionStore store;
    private Inet4Address wifi;
    private Inet4Address hotspot;

    @BeforeMethod
    public void createStore() throws IOException {
        store = new CompanionStore(Files.createTempDirectory("companion-share-state").toFile());
        store.createWithId("g", CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false))));
        wifi = (Inet4Address) InetAddress.getByName("192.168.1.20");
        hotspot = (Inet4Address) InetAddress.getByName("10.0.0.5");
    }

    private void writeRaw(String json) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(store.gameDir("g"), ShareState.FILE))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void readBackAHostedShareWithItsPortTokenAndAddresses() {
        ShareState.hosting(36745, -77, List.of(wifi, hotspot)).write(store, "g");

        ShareState state = ShareState.read(store, "g").orElseThrow();

        assertThat(state.role()).isEqualTo(ShareState.Role.HOSTING);
        assertThat(state.port()).isEqualTo(36745);
        assertThat(state.token()).isEqualTo(-77);
        assertThat(state.addresses()).containsExactly("192.168.1.20", "10.0.0.5");
        assertThat(state.code()).isEqualTo(new JoinCode(wifi, 36745, -77));
        assertThat(ShareState.hosted(store, "g")).contains(state);
        assertThat(ShareState.isFollowed(store, "g")).as("a hosted game is this device's own").isFalse();
    }

    @Test
    public void readBackAHostedShareWithNoAddress() {
        ShareState.hosting(36744, 5, List.of()).write(store, "g");

        ShareState state = ShareState.read(store, "g").orElseThrow();

        assertThat(state.hostAddress()).isNull();
        assertThat(state.addresses()).isEmpty();
        assertThat(state.token()).isEqualTo(5);
    }

    @Test
    public void readBackAFollowedShare() {
        JoinCode code = new JoinCode(wifi, 36744, 12345);
        ShareState.joined(code).write(store, "g");

        ShareState state = ShareState.read(store, "g").orElseThrow();

        assertThat(state.role()).isEqualTo(ShareState.Role.JOINED);
        assertThat(state.code()).isEqualTo(code);
        assertThat(state.addresses()).containsExactly("192.168.1.20");
        assertThat(ShareState.isFollowed(store, "g")).isTrue();
        assertThat(ShareState.hosted(store, "g")).isEmpty();
    }

    @Test
    public void readAFollowedShareWrittenBeforeSlice4() throws IOException {
        writeRaw("{\"role\":\"joined\",\"hostAddress\":\"192.168.1.20\",\"port\":36744,\"token\":12345}");

        ShareState state = ShareState.read(store, "g").orElseThrow();

        assertThat(state.code()).isEqualTo(new JoinCode(wifi, 36744, 12345));
        assertThat(state.addresses()).containsExactly("192.168.1.20");
        assertThat(ShareState.isFollowed(store, "g")).isTrue();
    }

    @Test
    public void countADamagedShareAsFollowedToBeSafe() throws IOException {
        writeRaw("{not json");

        assertThat(ShareState.isFollowed(store, "g")).isTrue();
        assertThat(ShareState.hosted(store, "g")).isEmpty();
    }

    @Test
    public void forgetAShareThatStoppedForGood() {
        ShareState.hosting(36744, 5, List.of(wifi)).write(store, "g");

        ShareState.delete(store, "g");

        assertThat(ShareState.exists(store, "g")).isFalse();
        assertThat(ShareState.isFollowed(store, "g")).isFalse();
        ShareState.delete(store, "g"); // none: nothing to do
    }
}
