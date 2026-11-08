package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionLogEntry;
import forge.gamemodes.companion.CompanionLogEntry.Kind;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The resume guard: a followed game is never resumed as the phone's own (slice 02), with a real store on disk. */
public class ActiveGameShould {
    private CompanionStore phone;
    private CompanionSetup setup;
    private JoinCode code;

    @BeforeMethod
    public void createStores() throws IOException {
        phone = new CompanionStore(Files.createTempDirectory("companion-phone").toFile());
        setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false)));
        code = new JoinCode((Inet4Address) InetAddress.getByName("192.168.1.20"), 36744, 12345);
    }

    /** A game followed from a host, as the client leaves it: the host's id, setup, two log lines, share.json. */
    private String followed() throws IOException {
        CompanionStore host = new CompanionStore(Files.createTempDirectory("companion-host").toFile());
        host.createWithId("hostgame", setup);
        host.appendLog("hostgame", CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "one"));
        host.appendLog("hostgame", CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "two"));
        phone.createWithId("hostgame", setup);
        phone.appendVerbatim("hostgame", host.loadLogLines("hostgame"));
        ShareState.joined(code).write(phone, "hostgame");
        return "hostgame";
    }

    /** Every file under the store, with its contents: what must not change. */
    private Map<String, String> files() {
        Path root = phone.gameDir("x").getParentFile().toPath();
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(p).toString(), new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    @Test
    public void resumeAGameOfThePhonesOwn() {
        String id = phone.create(setup);

        ActiveGame active = ActiveGame.find(phone).orElseThrow();

        assertThat(active.saved().id()).isEqualTo(id);
        assertThat(active.followed()).isFalse();
        assertThat(active.code()).isEmpty();
    }

    @Test
    public void neverResumeAFollowedGameAndWriteNothingFindingIt() throws IOException {
        String id = followed();
        Map<String, String> before = files();

        ActiveGame active = ActiveGame.find(phone).orElseThrow();

        assertThat(active.saved().id()).isEqualTo(id);
        assertThat(active.followed()).isTrue();
        assertThat(active.code()).contains(code);
        assertThat(files()).isEqualTo(before);
    }

    @Test
    public void stillGuardAFollowedGameWhoseShareStateIsDamaged() throws IOException {
        String id = followed();
        Files.writeString(new File(phone.gameDir(id), ShareState.FILE).toPath(), "{not json");

        ActiveGame active = ActiveGame.find(phone).orElseThrow();

        assertThat(active.followed()).isTrue();
        assertThat(active.code()).as("the code is asked for instead").isEmpty();
    }

    @Test
    public void stopFollowingMakesItAPastGameReadWithoutWriting() throws IOException {
        String id = followed();
        List<CompanionLogEntry> log = phone.loadLog(id);

        ActiveGame.find(phone).orElseThrow().stopFollowing(phone);

        assertThat(phone.loadActive()).isEmpty();
        assertThat(phone.listEnded()).extracting(CompanionStore.Ended::id).containsExactly(id);
        Map<String, String> ended = files();
        // the past games screen's read-only path
        assertThat(phone.loadFinalLog(id)).isEqualTo(log);
        assertThat(phone.loadFinalState(id)).isEmpty();
        assertThat(files()).isEqualTo(ended);
    }

    /**
     * Following a game writes its share state before its setup, so following cut short (a kill, a failed write) never
     * leaves a folder that looks like the phone's own game. Here the share state's write fails.
     */
    @Test
    public void neverResumeAFollowedGameWhoseShareStateWasntWritten() {
        File game = phone.gameDir("hostgame");
        assertThat(new File(game, ShareState.FILE + ".tmp").mkdirs()).isTrue(); // the share state can't be written

        assertThatThrownBy(() -> CompanionClient.startCopy(phone, "hostgame", setup, code))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(ActiveGame.find(phone)).isEmpty();
    }

    /** What a kill between the share state and the setup leaves: not resumed, and finding it writes nothing. */
    @Test
    public void neverResumeOrWriteToAFollowedGameWithNoSetupYet() {
        File game = phone.gameDir("hostgame");
        assertThat(game.mkdirs()).isTrue();
        ShareState.joined(code).write(phone, "hostgame");
        Map<String, String> before = files();

        assertThat(ActiveGame.find(phone)).isEmpty();
        assertThat(phone.loadActive()).isEmpty();

        assertThat(files()).isEqualTo(before);
        assertThat(game).isDirectory();
    }

    @Test
    public void followAGameWithItsShareStateAndSetup() {
        CompanionClient.startCopy(phone, "hostgame", setup, code);

        ActiveGame active = ActiveGame.find(phone).orElseThrow();
        assertThat(active.followed()).isTrue();
        assertThat(active.code()).contains(code);
        assertThat(phone.loadSetup("hostgame")).isEqualTo(setup);
    }
}
