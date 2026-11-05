package forge.gamemodes.companion;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import forge.gamemodes.companion.CompanionLogEntry.FromHand;
import forge.gamemodes.companion.CompanionLogEntry.Kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The numbered steps undo reads back, and the log of entries, on real files in a temporary folder. */
public class CompanionStoreShould {
    private File dir;
    private CompanionStore store;
    private String id;

    @BeforeMethod
    public void createStore() throws IOException {
        dir = Files.createTempDirectory("companion-store").toFile();
        dir.deleteOnExit();
        store = new CompanionStore(dir);
        id = store.create(CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false))));
    }

    private static Optional<List<String>> lines(String... lines) {
        return Optional.of(List.of(lines));
    }

    private File states() {
        return new File(new File(dir, id), "states");
    }

    @Test
    public void haveNoStepBeforeTheFirstSave() {
        assertThat(store.latestStep(id)).isEqualTo(CompanionStore.NO_STEP);
        assertThat(store.loadState(id)).isEmpty();
        assertThat(store.loadStep(id, 0)).isEmpty();
    }

    @Test
    public void numberEachSaveAndLoadTheHighest() {
        assertThat(store.saveState(id, "turn=1\nlife=40")).isEqualTo(0);
        assertThat(store.saveState(id, "turn=1\nlife=38")).isEqualTo(1);
        assertThat(store.saveState(id, "turn=2\nlife=38")).isEqualTo(2);

        assertThat(store.latestStep(id)).isEqualTo(2);
        assertThat(store.loadState(id)).isEqualTo(lines("turn=2", "life=38"));
        assertThat(store.loadStep(id, 1)).isEqualTo(lines("turn=1", "life=38"));
        assertThat(states().list()).containsExactlyInAnyOrder("0.txt", "1.txt", "2.txt");
    }

    @Test
    public void readASlice0StateAsStepZeroAndMoveItInAtTheNextSave() throws IOException {
        Files.write(new File(new File(dir, id), "state.txt").toPath(), "turn=3".getBytes(StandardCharsets.UTF_8));

        assertThat(store.latestStep(id)).isEqualTo(0);
        assertThat(store.loadState(id)).isEqualTo(lines("turn=3"));
        assertThat(store.loadStep(id, 0)).isEqualTo(lines("turn=3"));

        assertThat(store.saveState(id, "turn=4")).isEqualTo(1);
        assertThat(new File(new File(dir, id), "state.txt")).doesNotExist();
        assertThat(store.loadStep(id, 0)).isEqualTo(lines("turn=3"));
        assertThat(store.loadState(id)).isEqualTo(lines("turn=4"));
    }

    @Test
    public void stillReadASlice0StateIfTheMoveWasCutShort() throws IOException {
        Files.write(new File(new File(dir, id), "state.txt").toPath(), "turn=3".getBytes(StandardCharsets.UTF_8));
        assertThat(states().mkdir()).isTrue(); // the folder was made, the state not moved yet

        assertThat(store.latestStep(id)).isEqualTo(0);
        assertThat(store.loadState(id)).isEqualTo(lines("turn=3"));
        assertThat(store.saveState(id, "turn=4")).isEqualTo(1);
        assertThat(store.loadStep(id, 0)).isEqualTo(lines("turn=3"));
    }

    @Test
    public void ignoreTheSideFileOfAnInterruptedWrite() throws IOException {
        store.saveState(id, "turn=1");
        Files.write(new File(states(), "1.txt.tmp").toPath(), "turn=2, half writ".getBytes(StandardCharsets.UTF_8));

        assertThat(store.latestStep(id)).isEqualTo(0);
        assertThat(store.loadState(id)).isEqualTo(lines("turn=1"));
        assertThat(store.saveState(id, "turn=2")).isEqualTo(1);
        assertThat(store.loadState(id)).isEqualTo(lines("turn=2"));
        assertThat(states().list()).containsExactlyInAnyOrder("0.txt", "1.txt");
    }

    @Test
    public void makeAnEarlierStepCurrentAgainForAnUndo() {
        store.saveState(id, "s0");
        store.saveState(id, "s1");
        store.saveState(id, "s2");

        store.discardAfter(id, 1);

        assertThat(store.latestStep(id)).isEqualTo(1);
        assertThat(store.loadState(id)).isEqualTo(lines("s1"));
        assertThat(store.loadStep(id, 2)).isEmpty();
        CompanionStore restarted = new CompanionStore(dir);
        assertThat(restarted.latestStep(id)).isEqualTo(1);
        assertThat(restarted.loadState(id)).isEqualTo(lines("s1"));
    }

    /** A new action after an undo writes the undone step's number again, and nothing later survives it. */
    @Test
    public void overwriteAnUndoneStepWithTheNextAction() {
        store.saveState(id, "s0");
        store.saveState(id, "s1");
        store.saveState(id, "s2");
        store.saveState(id, "s3");

        store.saveState(id, 2, "s2 again");

        assertThat(store.latestStep(id)).isEqualTo(2);
        assertThat(store.loadState(id)).isEqualTo(lines("s2 again"));
        assertThat(store.loadStep(id, 3)).isEmpty();
        assertThat(store.saveState(id, "s3 again")).isEqualTo(3);
    }

    @Test
    public void numberTheNextSaveAfterAnUndoAsTheUndoneStep() {
        store.saveState(id, "s0");
        store.saveState(id, "s1");
        store.saveState(id, "s2");
        store.discardAfter(id, 0);

        assertThat(store.saveState(id, "other s1")).isEqualTo(1);
        assertThat(store.loadState(id)).isEqualTo(lines("other s1"));
        assertThat(store.loadStep(id, 2)).isEmpty();
    }

    /** Every step is kept, so undo always reaches the game's start. */
    @Test
    public void keepEveryStep() {
        int last = 600;
        for (int i = 0; i <= last; i++) {
            store.saveState(id, "s" + i);
        }

        assertThat(store.latestStep(id)).isEqualTo(last);
        assertThat(states().list()).hasSize(last + 1);
        assertThat(store.loadStep(id, 0)).isEqualTo(lines("s0"));
    }

    @Test
    public void refuseANegativeStep() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.saveState(id, -1, "s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void stillFindTheActiveGameOnceItHasSteps() {
        store.saveState(id, "s0");
        assertThat(store.loadActive()).map(CompanionStore.Saved::id).contains(id);
    }

    private File log() {
        return new File(new File(dir, id), "log.txt");
    }

    private void writeLog(String text) throws IOException {
        Files.write(log().toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void readBackEveryFieldOfAnEntry() {
        CompanionLogEntry add = CompanionLogEntry.of(Kind.ADD, 3, 2, "Ann", List.of("Bo"), "Bo casts \"Llanowar\"\nElves")
                .withCard("Llanowar Elves", FromHand.PLACEHOLDER);
        CompanionLogEntry undo = CompanionLogEntry.undo(2, List.of(3), 2, "Ann", "Undid Llanowar Elves");

        CompanionLogEntry written = store.appendLog(id, add);
        store.appendLog(id, undo);

        assertThat(written.seq()).isEqualTo(1);
        assertThat(new CompanionStore(dir).loadLog(id)).containsExactly(add.withSeq(1), undo.withSeq(2));
    }

    @Test
    public void numberEntriesOnFromTheLogAfterARestart() {
        store.appendLog(id, CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "one"));
        store.appendLog(id, CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "two"));

        CompanionLogEntry third = new CompanionStore(dir).appendLog(id,
                CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "three"));

        assertThat(third.seq()).isEqualTo(3);
    }

    @Test
    public void readAPlainLineFromAnOlderLogAsANotice() throws IOException {
        writeLog("Ann makes Goblin\nBo gets the emblem Ajani\n");

        store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 0, 1, "Ann", List.of("Ann"), "Ann casts Opt"));

        List<CompanionLogEntry> log = store.loadLog(id);
        assertThat(log).extracting(CompanionLogEntry::seq).containsExactly(1L, 2L, 3L);
        assertThat(log.get(0)).isEqualTo(new CompanionLogEntry(1, CompanionStore.NO_STEP, 0, null, List.of(),
                Kind.NOTICE, "Ann makes Goblin", null, null, List.of()));
        assertThat(log.get(2).kind()).isEqualTo(Kind.ADD);
    }

    @Test
    public void ignoreALastLineCutShortByACrash() throws IOException {
        store.appendLog(id, CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "whole"));
        Files.write(log().toPath(), "{\"seq\":2,\"step\":0,\"te".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        CompanionStore restarted = new CompanionStore(dir);
        assertThat(restarted.loadLog(id)).extracting(CompanionLogEntry::text).containsExactly("whole");

        // the next entry starts a line of its own, so it isn't lost with the cut one
        restarted.appendLog(id, CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "after"));
        assertThat(restarted.loadLog(id)).extracting(CompanionLogEntry::text).containsExactly("whole", "after");
    }

    @Test
    public void readAKindFromALaterVersionAsANotice() throws IOException {
        writeLog("{\"seq\":1,\"step\":0,\"turn\":1,\"kind\":\"skip\",\"text\":\"Skipped\",\"extra\":true}\n");

        assertThat(store.loadLog(id)).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(Kind.NOTICE);
            assertThat(entry.text()).isEqualTo("Skipped");
        });
    }

    @Test
    public void startWithNoLog() {
        assertThat(store.loadLog(id)).isEmpty();
    }

    /**
     * An undo names the steps it took back; the entries of those steps written before it are undone, and the step's
     * new entries after it aren't.
     */
    @Test
    public void deriveUndoneEntriesFromLaterUndoEntries() {
        long first = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a")).seq();
        long second = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "b")).seq();
        long notice = store.appendLog(id, CompanionLogEntry.of(Kind.NOTICE, 2, 1, "Ann", List.of(), "c")).seq();
        long undo = store.appendLog(id, CompanionLogEntry.undo(1, List.of(2), 1, "Ann", "undo")).seq();
        long redone = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "d")).seq();

        Set<Long> undone = CompanionLogEntry.undone(new CompanionStore(dir).loadLog(id));

        assertThat(undone).containsExactlyInAnyOrder(second, notice);
        assertThat(undone).doesNotContain(first, undo, redone);

        store.appendLog(id, CompanionLogEntry.undo(0, List.of(2, 1), 1, "Ann", "undo twice"));
        assertThat(CompanionLogEntry.undone(store.loadLog(id))).containsExactlyInAnyOrder(first, second, notice, redone);
    }

    @Test
    public void resumeACreatedGame() {
        assertThat(store.loadActive()).hasValueSatisfying(saved -> assertThat(saved.id()).isEqualTo(id));
    }

    @Test
    public void notResumeAnEndedGame() {
        store.saveState(id, "turn=3");

        store.end(id);

        assertThat(store.loadActive()).isEmpty();
        assertThat(store.unreadableFiles()).isEmpty();
    }

    @Test
    public void resumeANewGameAfterTheLastOneEnded() {
        store.end(id);

        String next = store.create(CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false))));

        assertThat(next).isNotEqualTo(id);
        assertThat(store.loadActive()).hasValueSatisfying(saved -> assertThat(saved.id()).isEqualTo(next));
    }

    @Test
    public void reportAGameThatCantBeEnded() {
        assertThatThrownBy(() -> store.end("no-such-game")).isInstanceOf(UncheckedIOException.class);
    }

    /**
     * L2: a crash after an undo discarded step 2 but before its log entry was written. The resume marks step 2's
     * entries undone, so the next action, which writes step 2 again, doesn't hide them.
     */
    @Test
    public void markUndoneTheEntriesOfAStepAnInterruptedUndoDiscarded() {
        store.saveState(id, "step 0");
        long first = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a")).seq();
        store.saveState(id, "step 1");
        long second = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "b")).seq();
        store.saveState(id, "step 2");
        store.discardAfter(id, 1); // the undo's files; then the crash, so no undo entry

        CompanionStore restarted = new CompanionStore(dir);
        assertThat(restarted.loadState(id)).isEqualTo(lines("step 1"));
        long redone = restarted.appendLog(id, CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "c")).seq();
        restarted.saveState(id, "step 2 again");

        assertThat(CompanionLogEntry.undone(restarted.loadLog(id))).containsExactly(second);
        assertThat(CompanionHistory.of(restarted.loadLog(id)).rows()).extracting(CompanionHistory.Row::undone)
                .containsExactly(false, true, false);
        assertThat(List.of(first, redone)).doesNotContainAnyElementsOf(CompanionLogEntry.undone(store.loadLog(id)));

        // a second resume finds nothing more to do
        new CompanionStore(dir).loadState(id);
        assertThat(store.loadLog(id)).filteredOn(e -> e.kind() == Kind.UNDO).hasSize(1);
    }

    /** An action logged but not saved before a crash isn't on the resumed board, so it reads as undone too. */
    @Test
    public void markUndoneAnActionThatWasNeverSaved() {
        store.saveState(id, "step 0");
        long lost = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a")).seq();

        new CompanionStore(dir).loadState(id);

        assertThat(CompanionLogEntry.undone(store.loadLog(id))).containsExactly(lost);
    }

    @Test
    public void leaveTheLogAloneWhenItMatchesTheSteps() {
        store.saveState(id, "step 0");
        store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a"));
        store.saveState(id, "step 1");

        new CompanionStore(dir).loadState(id);

        assertThat(store.loadLog(id)).hasSize(1);
    }

    @Test
    public void listEndedGamesNewestFirstWithTheirDateAndSeats() {
        String older = store.create(CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Cy", true), new CompanionSetup.Seat("Di", false))));
        String newer = store.create(CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ed", false), new CompanionSetup.Seat("Flo", true))));
        store.end(older);
        store.end(newer);
        assertThat(new File(new File(dir, older), "ended").setLastModified(1_000_000L)).isTrue();
        assertThat(new File(new File(dir, newer), "ended").setLastModified(2_000_000L)).isTrue();

        List<CompanionStore.Ended> ended = store.listEnded();

        assertThat(ended).extracting(CompanionStore.Ended::id).containsExactly(newer, older); // not the active one
        assertThat(ended.get(0).endedAt()).isEqualTo(2_000_000L);
        assertThat(ended.get(0).seatNames()).containsExactly("Ed", "Flo");
        assertThat(ended.get(1).seatNames()).containsExactly("Cy", "Di");
        assertThat(store.loadActive()).hasValueSatisfying(saved -> assertThat(saved.id()).isEqualTo(id));
    }

    @Test
    public void listNoEndedGamesBeforeOneEnds() {
        assertThat(store.listEnded()).isEmpty();
    }

    @Test
    public void readAnEndedGamesLog() {
        store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 0, 1, "Ann", List.of("Ann"), "Ann casts Opt"));
        store.end(id);

        String ended = store.listEnded().get(0).id();

        assertThat(CompanionHistory.of(new CompanionStore(dir).loadLog(ended)).rows())
                .extracting(CompanionHistory.Row::text).containsExactly("Ann casts Opt");
    }
}
