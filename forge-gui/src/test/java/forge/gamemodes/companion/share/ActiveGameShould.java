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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Load game's list of unfinished games, and the resume guard: a followed game is never resumed as the phone's own
 * (slice 02), with a real store on disk.
 */
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

    /** The one unfinished game the store lists. */
    private ActiveGame single() {
        List<ActiveGame> games = ActiveGame.list(phone);
        assertThat(games).hasSize(1);
        return games.get(0);
    }

    /** Makes the game {@code id} look last played at {@code millis}: its folder, steps and log. */
    private void playedAt(String id, long millis) {
        File game = phone.gameDir(id);
        for (File f : new File[] {game, new File(game, "states"), new File(game, "log.txt")}) {
            if (f.exists()) {
                assertThat(f.setLastModified(millis)).isTrue();
            }
        }
    }

    private CompanionSetup seats(String... names) {
        List<CompanionSetup.Seat> seats = new ArrayList<>();
        for (String name : names) {
            seats.add(new CompanionSetup.Seat(name, seats.isEmpty()));
        }
        return CompanionSetup.withDefaults(seats);
    }

    @Test
    public void listSeveralUnfinishedGamesNewestFirstWithFollowedOnesMarked() throws IOException {
        String oldest = phone.create(seats("Cy", "Di"));
        phone.saveState(oldest, "turn=1");
        String followed = followed();
        String newest = phone.create(seats("Ed", "Flo", "Gus"));
        String ended = phone.create(seats("Hal", "Ivy"));
        phone.end(ended);
        playedAt(oldest, 1_000_000L);
        playedAt(followed, 2_000_000L);
        playedAt(newest, 3_000_000L);
        Map<String, String> before = files();

        List<ActiveGame> games = ActiveGame.list(phone);

        assertThat(games).extracting(ActiveGame::id).containsExactly(newest, followed, oldest);
        assertThat(games).extracting(ActiveGame::followed).containsExactly(false, true, false);
        assertThat(games.get(0).game().seatNames()).containsExactly("Ed", "Flo", "Gus");
        assertThat(games.get(0).game().lastPlayed()).isEqualTo(3_000_000L);
        assertThat(games.get(1).code()).contains(code);
        assertThat(games.get(2).game().seatNames()).containsExactly("Cy", "Di");
        assertThat(files()).as("listing writes nothing").isEqualTo(before);
        assertThat(phone.unreadableFiles()).isEmpty();
    }

    /** A game this device shares has a share.json too, as host: it's still its own, continued, not followed. */
    @Test
    public void listAGameThisDeviceHostsAsItsOwn() {
        String id = phone.create(seats("Ann", "Bo"));
        ShareState.hosting(36744, 99, List.of((Inet4Address) code.address())).write(phone, id);

        ActiveGame active = single();

        assertThat(active.followed()).isFalse();
        assertThat(active.code()).isEmpty();
        assertThat(ShareState.hosted(phone, id)).map(ShareState::token).contains(99);
    }

    @Test
    public void putAGameFirstOnceItsPlayedAgain() {
        String a = phone.create(seats("Ann", "Bo"));
        String b = phone.create(seats("Cy", "Di"));
        playedAt(a, 1_000_000L);
        playedAt(b, 2_000_000L);
        assertThat(ActiveGame.list(phone)).extracting(ActiveGame::id).containsExactly(b, a);

        phone.saveState(a, "turn=2"); // continued and played: a new step, now

        assertThat(ActiveGame.list(phone)).extracting(ActiveGame::id).containsExactly(a, b);
    }

    @Test
    public void countALogEntryAsPlayed() {
        String a = phone.create(seats("Ann", "Bo"));
        String b = phone.create(seats("Cy", "Di"));
        playedAt(a, 1_000_000L);
        playedAt(b, 2_000_000L);

        phone.appendLog(a, CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "a note"));

        assertThat(ActiveGame.list(phone)).extracting(ActiveGame::id).containsExactly(a, b);
    }

    @Test
    public void leaveTheOtherUnfinishedGamesAsTheyAreWhenANewOneStarts() throws IOException {
        String own = phone.create(seats("Ann", "Bo"));
        phone.saveState(own, "turn=4");
        String followed = followed();
        Map<String, String> before = files();

        String started = phone.create(seats("Cy", "Di"));

        Map<String, String> after = files();
        after.keySet().removeIf(path -> path.startsWith(started));
        assertThat(after).isEqualTo(before);
        assertThat(ActiveGame.list(phone)).extracting(ActiveGame::id)
                .containsExactlyInAnyOrder(own, followed, started);
    }

    @Test
    public void endOneGameAndKeepListingTheOthers() throws IOException {
        String a = phone.create(seats("Ann", "Bo"));
        String b = phone.create(seats("Cy", "Di"));
        String followed = followed();
        playedAt(a, 1_000_000L);
        playedAt(b, 2_000_000L);
        playedAt(followed, 3_000_000L);

        ActiveGame.list(phone).get(1).end(phone);

        assertThat(ActiveGame.list(phone)).extracting(ActiveGame::id).containsExactly(followed, a);
        assertThat(phone.listEnded()).extracting(CompanionStore.Ended::id).containsExactly(b);
    }

    @Test
    public void neverEndAFollowedGameAsThePhonesOwn() throws IOException {
        followed();
        Map<String, String> before = files();

        assertThatThrownBy(() -> single().end(phone)).isInstanceOf(IllegalStateException.class);

        assertThat(files()).isEqualTo(before);
    }

    @Test
    public void setADamagedGameAsideAndStillListTheOthers() throws IOException {
        String good = phone.create(seats("Ann", "Bo"));
        String damaged = phone.create(seats("Cy", "Di"));
        Files.write(new File(phone.gameDir(damaged), "setup.json").toPath(),
                "{not json".getBytes(StandardCharsets.UTF_8));
        String followed = followed();

        List<ActiveGame> games = ActiveGame.list(phone);

        assertThat(games).extracting(ActiveGame::id).containsExactlyInAnyOrder(good, followed);
        File aside = new File(phone.gameDir(damaged).getParentFile(), damaged + ".damaged");
        assertThat(phone.unreadableFiles()).containsExactly(aside);
        assertThat(new File(aside, "setup.json")).hasContent("{not json");
        assertThat(phone.gameDir(damaged)).doesNotExist();
        assertThat(ActiveGame.list(phone)).as("not listed again").hasSize(2);
        assertThat(phone.unreadableFiles()).as("and not reported again").isEmpty();
    }

    @Test
    public void leaveAGameThatCantBeReadWhereItIsAndStillListTheOthers() {
        String good = phone.create(seats("Ann", "Bo"));
        File unreadable = phone.gameDir("nosetup");
        assertThat(unreadable.mkdirs()).isTrue();

        List<ActiveGame> games = ActiveGame.list(phone);

        assertThat(games).extracting(ActiveGame::id).containsExactly(good);
        assertThat(phone.unreadableFiles()).containsExactly(unreadable);
        assertThat(unreadable).isDirectory();
    }

    @Test
    public void listNothingWithNoUnfinishedGame() {
        String id = phone.create(seats("Ann", "Bo"));
        phone.end(id);

        assertThat(ActiveGame.list(phone)).isEmpty();
        assertThat(phone.loadActive()).isEmpty();
    }

    @Test
    public void resumeAGameOfThePhonesOwn() {
        String id = phone.create(setup);

        ActiveGame active = single();

        assertThat(active.id()).isEqualTo(id);
        assertThat(active.followed()).isFalse();
        assertThat(active.code()).isEmpty();
    }

    @Test
    public void neverResumeAFollowedGameAndWriteNothingFindingIt() throws IOException {
        String id = followed();
        Map<String, String> before = files();

        ActiveGame active = single();

        assertThat(active.id()).isEqualTo(id);
        assertThat(active.followed()).isTrue();
        assertThat(active.code()).contains(code);
        assertThat(files()).isEqualTo(before);
    }

    @Test
    public void stillGuardAFollowedGameWhoseShareStateIsDamaged() throws IOException {
        String id = followed();
        Files.writeString(new File(phone.gameDir(id), ShareState.FILE).toPath(), "{not json");

        ActiveGame active = single();

        assertThat(active.followed()).isTrue();
        assertThat(active.code()).as("the code is asked for instead").isEmpty();
    }

    @Test
    public void stopFollowingMakesItAPastGameReadWithoutWriting() throws IOException {
        String id = followed();
        List<CompanionLogEntry> log = phone.loadLog(id);

        single().stopFollowing(phone);

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

        assertThat(ActiveGame.list(phone)).isEmpty();
    }

    /** What a kill between the share state and the setup leaves: not resumed, and finding it writes nothing. */
    @Test
    public void neverResumeOrWriteToAFollowedGameWithNoSetupYet() {
        File game = phone.gameDir("hostgame");
        assertThat(game.mkdirs()).isTrue();
        ShareState.joined(code).write(phone, "hostgame");
        Map<String, String> before = files();

        assertThat(ActiveGame.list(phone)).isEmpty();
        assertThat(phone.loadActive()).isEmpty();

        assertThat(files()).isEqualTo(before);
        assertThat(game).isDirectory();
    }

    @Test
    public void followAGameWithItsShareStateAndSetup() {
        CompanionClient.startCopy(phone, "hostgame", setup, code);

        ActiveGame active = single();
        assertThat(active.followed()).isTrue();
        assertThat(active.code()).contains(code);
        assertThat(phone.loadSetup("hostgame")).isEqualTo(setup);
    }
}
