package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.share.CompanionClient.Link;
import forge.gamemodes.companion.share.CompanionResume.Pick;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 04, as the owner decided (2026-11-09): an unfinished game is carried on from Load game, a followed one by
 * rejoining without a code; until the host's board arrives the phone shows N6. A real store on disk.
 */
public class CompanionResumeShould {
    private CompanionStore store;
    private JoinCode code;
    private String id;

    @BeforeMethod
    public void createStore() throws IOException {
        store = new CompanionStore(Files.createTempDirectory("companion-resume").toFile());
        code = new JoinCode((Inet4Address) InetAddress.getByName("192.168.1.20"), 36744, 12345);
        id = store.create(CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false))));
    }

    private Pick pick() {
        List<ActiveGame> games = ActiveGame.list(store);
        assertThat(games).hasSize(1);
        return CompanionResume.pick(store, games.get(0));
    }

    @Test
    public void continueAGameNeverShared() {
        assertThat(pick()).isEqualTo(Pick.CONTINUE);
    }

    @Test
    public void continueAndShareAgainAGameThisDeviceHosts() {
        ShareState.hosting(36745, 99, List.of((Inet4Address) code.address())).write(store, id);

        assertThat(pick()).isEqualTo(Pick.CONTINUE_SHARING);
    }

    /** "Stop sharing" deletes the share state ({@link CompanionShareHost#stopForGood}): no share on Continue then. */
    @Test
    public void continueWithoutSharingOnceTheShareStateIsGone() {
        ShareState.hosting(36745, 99, List.of((Inet4Address) code.address())).write(store, id);
        ShareState.delete(store, id);

        assertThat(pick()).isEqualTo(Pick.CONTINUE);
    }

    @Test
    public void rejoinAFollowedGameWithTheCodeItWasJoinedWith() {
        ShareState.joined(code).write(store, id);

        assertThat(pick()).isEqualTo(Pick.REJOIN);
        assertThat(ActiveGame.list(store).get(0).code()).contains(code);
    }

    @Test
    public void askForTheCodeOfAFollowedGameWhoseShareStateIsDamaged() throws IOException {
        Files.write(new File(store.gameDir(id), ShareState.FILE).toPath(), "{not json".getBytes(StandardCharsets.UTF_8));

        assertThat(pick()).isEqualTo(Pick.JOIN_WITH_CODE);
    }

    @Test
    public void showTheNoticeOverTheBoardWhileItReconnects() {
        assertThat(CompanionResume.showsReconnectNotice(Link.RECONNECTING, true)).isTrue();
        assertThat(CompanionResume.showsReconnectNotice(Link.CONNECTED, true)).isFalse();
        assertThat(CompanionResume.showsReconnectNotice(Link.RECONNECTING, false)).as("N6 says so itself").isFalse();
    }

    @Test
    public void showNoNoticeOnceTheGameEndedOrWasLeft() {
        assertThat(CompanionResume.showsReconnectNotice(Link.ENDED, true)).isFalse();
        assertThat(CompanionResume.showsReconnectNotice(Link.CLOSED, true)).isFalse();
    }

    /** It never says it gave up while it's still trying. */
    @Test
    public void sayItsStillTryingWhileTheHostIsAway() {
        assertThat(CompanionResume.statusKey(Link.RECONNECTING)).isEqualTo("lblCompanionHostAway");
        assertThat(CompanionResume.statusKey(Link.CONNECTING)).isEqualTo("lblCompanionRejoinConnecting");
        assertThat(CompanionResume.statusKey(Link.ENDED)).isEqualTo("lblCompanionHostEnded");
    }

    @Test
    public void haveAnEnglishTextForEachStatus() throws IOException {
        Properties english = new Properties();
        try (Reader in = Files.newBufferedReader(new File("res/languages/en-US.properties").toPath(),
                StandardCharsets.UTF_8)) {
            english.load(in);
        }
        for (Link link : Link.values()) {
            assertThat(english.getProperty(CompanionResume.statusKey(link))).as(link.name()).isNotBlank();
        }
    }
}
