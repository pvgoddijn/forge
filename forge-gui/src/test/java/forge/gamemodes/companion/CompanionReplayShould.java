package forge.gamemodes.companion;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A game's state comes only from its log, so a saved game must come back exactly, even after a crash mid-write. */
public class CompanionReplayShould {
    private static final List<CompanionAction.PlayerSetup> PLAYERS = Arrays.asList(
            new CompanionAction.PlayerSetup("Ann", true),
            new CompanionAction.PlayerSetup("Bo \"the\" Björk", false));

    private File dir;
    private CompanionStore store;

    @BeforeMethod
    public void createDir() throws IOException {
        dir = Files.createTempDirectory("companion-test").toFile();
        store = new CompanionStore(dir);
    }

    @AfterMethod
    public void deleteDir() throws IOException {
        try (Stream<java.nio.file.Path> paths = Files.walk(dir.toPath())) {
            paths.sorted(Comparator.reverseOrder()).map(java.nio.file.Path::toFile).forEach(File::delete);
        }
    }

    @Test
    public void giveTheSameStateFromTheSameLog() {
        CompanionGame game = store.createGame(PLAYERS);
        game.addCard(1, "Sol Ring");
        game.addCard(1, "Sol Ring");
        game.addCard(0, "Forest");

        assertThat(CompanionState.replay(game.log())).isEqualTo(game.state());
        assertThat(game.state().players().get(1).battlefield()).extracting(CompanionState.GameCard::name)
                .containsExactly("Sol Ring", "Sol Ring");
    }

    @Test
    public void keepTwoCopiesDistinct() {
        CompanionGame game = store.createGame(PLAYERS);
        game.addCard(0, "Forest");
        game.addCard(0, "Forest");

        List<CompanionState.GameCard> cards = game.state().players().get(0).battlefield();
        assertThat(cards.get(0).instanceId()).isNotEqualTo(cards.get(1).instanceId());
    }

    @Test
    public void resumeASavedGameExactly() {
        CompanionGame game = store.createGame(PLAYERS);
        game.addCard(1, "Lim-Dûl's Vault");

        CompanionGame resumed = new CompanionStore(dir).loadActive().orElseThrow();

        assertThat(resumed.id()).isEqualTo(game.id());
        assertThat(resumed.log()).isEqualTo(game.log());
        assertThat(resumed.state()).isEqualTo(game.state());
    }

    @Test
    public void ignoreATornLastLineAndKeepGoing() throws IOException {
        CompanionGame game = store.createGame(PLAYERS);
        game.addCard(0, "Forest");
        byte[] torn = "{\"seq\":2,\"time\":1,\"type\":\"CardAd".getBytes(StandardCharsets.UTF_8);
        Files.write(fileOf(game), torn, StandardOpenOption.APPEND);

        CompanionGame resumed = store.loadActive().orElseThrow();
        assertThat(resumed.state()).isEqualTo(game.state());

        resumed.addCard(1, "Sol Ring");
        CompanionGame again = store.loadActive().orElseThrow();
        assertThat(again.state().players().get(1).battlefield()).hasSize(1);
        assertThat(again.state().players().get(0).battlefield()).hasSize(1);
    }

    @Test
    public void notResumeAnEndedGame() {
        CompanionGame game = store.createGame(PLAYERS);
        game.apply(new CompanionAction.GameEnded(game.log().size(), 1));

        assertThat(store.loadActive()).isEmpty();
    }

    @Test
    public void notSaveAnInvalidAction() throws IOException {
        CompanionGame game = store.createGame(PLAYERS);
        long sizeBefore = fileOf(game).toFile().length();

        assertThatThrownBy(() -> game.addCard(5, "Sol Ring")).isInstanceOf(IllegalArgumentException.class);

        assertThat(fileOf(game).toFile().length()).isEqualTo(sizeBefore);
        assertThat(game.log()).hasSize(1);
    }

    @Test
    public void requireTwoToSixPlayersAndExactlyOneMe() {
        CompanionAction.PlayerSetup other = new CompanionAction.PlayerSetup("Other", false);
        CompanionAction.PlayerSetup me = new CompanionAction.PlayerSetup("Me", true);

        assertThatThrownBy(() -> store.createGame(Arrays.asList(me))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createGame(Arrays.asList(other, other))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createGame(Arrays.asList(me, me))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createGame(Arrays.asList(me, other, other, other, other, other, other)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dir.list()).isEmpty();
    }

    private java.nio.file.Path fileOf(CompanionGame game) {
        return new File(dir, game.id() + ".jsonl").toPath();
    }
}
