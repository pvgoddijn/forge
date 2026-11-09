package forge.gamemodes.companion;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CompanionStoreShould {
    private static final CompanionSetup SETUP = CompanionSetup.withDefaults(Arrays.asList(
            new CompanionSetup.Seat("Alice", true), new CompanionSetup.Seat("Bob", false)));

    private Path dir;
    private CompanionStore store;

    @BeforeMethod
    public void createStore() throws IOException {
        dir = Files.createTempDirectory("companion-store");
        store = new CompanionStore(dir.toFile());
    }

    @AfterMethod
    public void deleteStore() throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    @Test
    public void resumeACreatedGame() {
        String id = store.create(SETUP);

        assertThat(store.loadActive()).hasValueSatisfying(saved -> assertThat(saved.id()).isEqualTo(id));
    }

    @Test
    public void notResumeAnEndedGame() {
        String id = store.create(SETUP);
        store.saveState(id, "turn=3");

        store.end(id);

        assertThat(store.loadActive()).isEmpty();
        assertThat(store.unreadableFiles()).isEmpty();
    }

    @Test
    public void resumeANewGameAfterTheLastOneEnded() {
        String ended = store.create(SETUP);
        store.end(ended);

        String next = store.create(SETUP);

        assertThat(next).isNotEqualTo(ended);
        assertThat(store.loadActive()).hasValueSatisfying(saved -> assertThat(saved.id()).isEqualTo(next));
    }

    @Test
    public void reportAGameThatCantBeEnded() {
        assertThatThrownBy(() -> store.end("no-such-game")).isInstanceOf(UncheckedIOException.class);
    }
}
