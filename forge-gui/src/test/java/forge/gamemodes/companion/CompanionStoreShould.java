package forge.gamemodes.companion;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import forge.gamemodes.companion.CompanionLogEntry.FromHand;
import forge.gamemodes.companion.CompanionLogEntry.Kind;
import forge.gamemodes.companion.CompanionStore.Verbatim;

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

        CompanionLogEntry written = store.appendLog(id, add).entry();
        store.appendLog(id, undo);

        assertThat(written.seq()).isEqualTo(1);
        assertThat(new CompanionStore(dir).loadLog(id)).containsExactly(add.withSeq(1), undo.withSeq(2));
    }

    @Test
    public void numberEntriesOnFromTheLogAfterARestart() {
        store.appendLog(id, CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "one"));
        store.appendLog(id, CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "two"));

        CompanionLogEntry third = new CompanionStore(dir).appendLog(id,
                CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, "three")).entry();

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
        long first = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a"))
                .entry().seq();
        long second = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "b"))
                .entry().seq();
        long notice = store.appendLog(id, CompanionLogEntry.of(Kind.NOTICE, 2, 1, "Ann", List.of(), "c")).entry().seq();
        long undo = store.appendLog(id, CompanionLogEntry.undo(1, List.of(2), 1, "Ann", "undo")).entry().seq();
        long redone = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "d"))
                .entry().seq();

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
        long first = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a"))
                .entry().seq();
        store.saveState(id, "step 1");
        long second = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "b"))
                .entry().seq();
        store.saveState(id, "step 2");
        store.discardAfter(id, 1); // the undo's files; then the crash, so no undo entry

        CompanionStore restarted = new CompanionStore(dir);
        assertThat(restarted.loadState(id)).isEqualTo(lines("step 1"));
        long redone = restarted.appendLog(id, CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "c"))
                .entry().seq();
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
        long lost = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a")).entry().seq();

        new CompanionStore(dir).loadState(id);

        assertThat(CompanionLogEntry.undone(store.loadLog(id))).containsExactly(lost);
    }

    /**
     * Undo with ops parked: the undo entry, then the notice that they weren't added, under the next step. A notice
     * changes nothing, so a resume keeps it as it is and writes no undo entry for it.
     */
    @Test
    public void leaveANoticeAfterTheLastSaveAloneAtAResume() {
        store.saveState(id, "step 0");
        store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a"));
        store.saveState(id, "step 1");
        store.discardAfter(id, 0);
        store.appendLog(id, CompanionLogEntry.undo(0, List.of(1), 1, "Ann", "Undone: back to turn 1, Ann"));
        store.appendLog(id, CompanionLogEntry.of(Kind.NOTICE, 1, 1, "Ann", List.of("Ann"),
                "Undone: Opt and anything after it wasn't added"));
        List<CompanionLogEntry> before = store.loadLog(id);

        assertThat(new CompanionStore(dir).loadState(id)).isEqualTo(lines("step 0"));

        assertThat(store.loadLog(id)).isEqualTo(before);
    }

    @Test
    public void settleOnlyTheKindsThatChangeTheGame() {
        for (Kind kind : Kind.values()) {
            List<CompanionLogEntry> log = List.of(CompanionLogEntry.of(kind, 1, 1, "Ann", List.of("Ann"), "x")
                    .withSeq(1));
            boolean changes = kind != Kind.NOTICE && kind != Kind.UNDO;
            assertThat(CompanionLogEntry.settle(log, 0)).as(kind.name()).isEqualTo(changes
                    ? Optional.of(CompanionLogEntry.undo(0, List.of(1), 1, "Ann",
                            "Not kept when the game was interrupted: back to the last save"))
                    : Optional.empty());
        }
    }

    /** A log that can't be written keeps numbering on, so an entry kept in memory isn't given the next one's seq. */
    @Test
    public void takeTheSeqOfAnEntryItCouldntWrite() throws IOException {
        assertThat(store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 0, 1, "Ann", List.of("Ann"), "a")).entry().seq())
                .isEqualTo(1);
        File log = new File(new File(dir, id), "log.txt");
        Files.delete(log.toPath());
        assertThat(log.mkdir()).isTrue(); // the log can't be written now

        long unwritten = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 0, 1, "Ann", List.of("Ann"), "b"))
                .entry().seq();
        long next = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 0, 1, "Ann", List.of("Ann"), "c")).entry().seq();

        assertThat(unwritten).isEqualTo(2);
        assertThat(next).isEqualTo(3);
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

    /** AC-30: a past game is read-only, so reading its boards doesn't settle its log as a resume would. */
    @Test
    public void readAnEndedGamesFinalStateWithoutWritingItsLog() throws IOException {
        store.saveState(id, "step 0");
        store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a")); // step 1 never saved
        store.end(id);
        File log = new File(new File(dir, id), "log.txt");
        byte[] before = Files.readAllBytes(log.toPath());

        CompanionStore restarted = new CompanionStore(dir);
        String ended = restarted.listEnded().get(0).id();

        assertThat(restarted.loadFinalState(ended)).isEqualTo(lines("step 0"));
        assertThat(restarted.loadLog(ended)).hasSize(1);
        assertThat(Files.readAllBytes(log.toPath())).isEqualTo(before);
    }

    /**
     * AC-30: an action logged after the last save isn't on the final board, so a past game's history shows it undone,
     * settled in memory only.
     */
    @Test
    public void showAnEndedGamesUnsavedActionUndoneWithoutWritingItsLog() throws IOException {
        store.saveState(id, "step 0");
        long kept = store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a")).entry().seq();
        store.saveState(id, "step 1");
        long unsaved = store.appendLog(id, CompanionLogEntry.of(Kind.ACTION, 2, 1, "Ann", List.of("Ann"), "b"))
                .entry().seq();
        store.end(id);
        File log = new File(new File(dir, id), "log.txt");
        byte[] before = Files.readAllBytes(log.toPath());

        CompanionStore restarted = new CompanionStore(dir);
        String ended = restarted.listEnded().get(0).id();
        List<CompanionLogEntry> history = restarted.loadFinalLog(ended);

        assertThat(CompanionLogEntry.undone(history)).containsExactly(unsaved);
        assertThat(history.get(history.size() - 1).seq()).isEqualTo(unsaved + 1);
        assertThat(CompanionHistory.of(history).rows()).filteredOn(r -> r.text().equals("a"))
                .extracting(CompanionHistory.Row::undone).containsExactly(false);
        assertThat(kept).isLessThan(unsaved);
        assertThat(Files.readAllBytes(log.toPath())).isEqualTo(before);
    }

    /** Slice 6: the seats' commanders and decklists, and Commander mode, come back as they were saved. */
    @Test
    public void keepTheCommandersTheDeckListsAndCommanderModeInSetupVersion3() {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true, List.of("Krenko, Mob Boss"), "1 Sol Ring\n1 Mountain"),
                new CompanionSetup.Seat("Bo", false, List.of("Tymna the Weaver", "Thrasios, Triton Hero"), ""),
                new CompanionSetup.Seat("Cy", false))).withCommanderMode(false);

        String json = CompanionStore.toJson(setup);
        CompanionSetup read = CompanionStore.fromJson(json);

        assertThat(json).contains("\"version\":3");
        assertThat(read).isEqualTo(setup);
        assertThat(read.seats().get(1).commanders()).containsExactly("Tymna the Weaver", "Thrasios, Triton Hero");
        assertThat(read.seats().get(0).deckList()).isEqualTo("1 Sol Ring\n1 Mountain");
        assertThat(read.commanderMode()).isFalse();
        assertThat(CompanionStore.fromJson(CompanionStore.toJson(setup.withCommanderMode(true))).commanderMode())
                .isTrue();
    }

    /**
     * Chunk 12: a pasted decklist, with its Commander section filling the seat's commanders, is saved with the game
     * and comes back from the file as it was pasted, so the game doesn't depend on a saved deck that may change.
     */
    @Test
    public void keepAnImportedDeckAndItsCommandersInTheSavedGame() {
        String pasted = "Commander\r\n1 Tymna the Weaver (C16) 48\r\n1 Thrasios, Triton Hero\r\n\r\nDeck\r\n"
                + "1 Sol Ring\r\n\"quoted\" // and \\ odd\t\u00e9 characters\n";
        CompanionSetup.Seat bo = new CompanionSetup.Seat("Bo", false).withDeckList(pasted)
                .withCommanders(DeckListNames.commanderNames(pasted));
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(new CompanionSetup.Seat("Ann", true), bo));
        store.end(id);

        String created = store.create(setup);
        CompanionStore.Saved saved = new CompanionStore(dir).loadActive().orElseThrow();

        assertThat(saved.id()).isEqualTo(created);
        assertThat(saved.setup()).isEqualTo(setup);
        assertThat(saved.setup().seats().get(1).deckList()).isEqualTo(pasted);
        assertThat(saved.setup().seats().get(1).commanders())
                .containsExactly("Tymna the Weaver", "Thrasios, Triton Hero");
        assertThat(saved.setup().seats().get(0).deckList()).isEmpty();
        assertThat(saved.setup().seats().get(0).commanders()).isEmpty();
    }

    /** A game saved before version 3 started without commanders, so it reads with none and Commander mode off. */
    @Test
    public void readAVersion2SetupWithNoCommandersAndCommanderModeOff() {
        String version2 = "{\"version\":2,\"life\":40,\"hand\":7,\"library\":92,\"combat\":true,"
                + "\"seats\":[{\"name\":\"Ann\",\"me\":true},{\"name\":\"Bo\",\"me\":false}]}";

        CompanionSetup read = CompanionStore.fromJson(version2);

        assertThat(read.commanderMode()).isFalse();
        assertThat(read.engineCombat()).isTrue();
        assertThat(read.hasCommanders()).isFalse();
        assertThat(read.seats()).extracting(CompanionSetup.Seat::deckList).containsExactly("", "");
        assertThat(read.seats()).extracting(CompanionSetup.Seat::name).containsExactly("Ann", "Bo");
    }

    /** A new game has Commander mode on, as the setup screen starts it (AC-43). */
    @Test
    public void startANewSetupWithCommanderModeOn() {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false)));

        assertThat(setup.commanderMode()).isTrue();
        assertThat(store.loadActive()).get().extracting(s -> s.setup().commanderMode()).isEqualTo(true);
    }

    /**
     * The commanders are in each seat's Command zone at the start, in game order (p0 is Me), and each library is the
     * 100-card deck less its commanders and the hand.
     */
    @Test
    public void startEachCommanderInItsSeatsCommandZoneWithTheLibraryLessTheCommanders() {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", false, List.of("Krenko, Mob Boss"), ""),
                new CompanionSetup.Seat("Bo", true, List.of("Tymna the Weaver", "Thrasios, Triton Hero"), ""),
                new CompanionSetup.Seat("Cy", false)));

        List<String> state = setup.initialState();

        // game order starts at Me: Bo, Cy, Ann
        assertThat(state).contains("p0command=Tymna the Weaver|IsCommander;Thrasios, Triton Hero|IsCommander",
                "p2command=Krenko, Mob Boss|IsCommander");
        assertThat(state).noneMatch(line -> line.startsWith("p1command="));
        assertThat(libraryCount(state, 0)).isEqualTo(100 - 2 - 7);
        assertThat(libraryCount(state, 1)).isEqualTo(100 - 7);
        assertThat(libraryCount(state, 2)).isEqualTo(100 - 1 - 7).isEqualTo(CompanionSetup.DEFAULT_LIBRARY);
    }

    private static int libraryCount(List<String> state, int player) {
        String prefix = "p" + player + "library=";
        return state.stream().filter(line -> line.startsWith(prefix))
                .mapToInt(line -> line.substring(prefix.length()).split(";").length).findFirst().orElse(-1);
    }
    /** What the host's journal would send: each entry's line as written, a gap for each failed append. */
    private static class Recorder implements CompanionStore.Journal {
        final List<Verbatim> sent = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        @Override
        public void appended(String gameId, CompanionLogEntry entry, String line) {
            sent.add(Verbatim.entry(entry.seq(), line));
            events.add("entry " + entry.seq());
        }

        @Override
        public void appendFailed(String gameId, long seq) {
            sent.add(Verbatim.gap(seq));
            events.add("gap " + seq);
        }

        @Override
        public void setupSaved(String gameId, CompanionSetup setup) {
            events.add("setup");
        }

        @Override
        public void ended(String gameId) {
            events.add("end");
        }
    }

    private static CompanionLogEntry notice(String text) {
        return CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, text);
    }

    private String logText(File gameDir) throws IOException {
        return new String(Files.readAllBytes(new File(gameDir, "log.txt").toPath()), StandardCharsets.UTF_8);
    }

    /** A phone's store, following the game {@link #id} of {@link #store}. */
    private CompanionStore follower() throws IOException {
        File phoneDir = Files.createTempDirectory("companion-phone").toFile();
        phoneDir.deleteOnExit();
        CompanionStore phone = new CompanionStore(phoneDir);
        phone.createWithId(id, CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false))));
        return phone;
    }

    @Test
    public void tellTheJournalOfEachAppendTheSetupAndTheEndInWriteOrder() {
        Recorder journal = new Recorder();
        store.setJournal(journal);

        assertThat(store.appendLog(id, notice("one")).written()).isTrue();
        store.saveSetup(id, store.loadActive().orElseThrow().setup());
        store.appendLog(id, notice("two"));
        store.end(id);

        assertThat(journal.events).containsExactly("entry 1", "setup", "entry 2", "end");
    }

    @Test
    public void letTheJournalStampAnEntryBeforeItsNumbered() {
        store.setJournal(new Recorder() {
            @Override
            public CompanionLogEntry stamp(String gameId, CompanionLogEntry entry) {
                return entry.withOrigin(2, "host-device", "phone-2", "Bo's phone");
            }
        });

        CompanionLogEntry written = store.appendLog(id, notice("one")).entry();

        assertThat(written.seq()).isEqualTo(1);
        assertThat(store.loadLog(id)).containsExactly(written);
        assertThat(written.byName()).isEqualTo("Bo's phone");
    }

    /** AC-112: a failed write on the host becomes a tombstone on the phone, and later entries still append. */
    @Test
    public void turnAFailedAppendIntoATombstoneOnThePhoneAndKeepAppending() throws IOException {
        Recorder journal = new Recorder();
        store.setJournal(journal);
        store.appendLog(id, notice("a"));
        File log = log();
        File kept = new File(dir, "kept-log.txt");
        Files.move(log.toPath(), kept.toPath());
        assertThat(log.mkdir()).isTrue(); // the host's log can't be written now

        CompanionStore.Appended failed = store.appendLog(id, notice("b"));

        assertThat(failed.written()).isFalse();
        assertThat(failed.entry().seq()).isEqualTo(2);
        assertThat(journal.events).containsExactly("entry 1", "gap 2");
        assertThat(log.delete()).isTrue();
        Files.move(kept.toPath(), log.toPath());
        assertThat(store.appendLog(id, notice("c")).written()).isTrue();

        CompanionStore phone = follower();
        assertThat(phone.appendVerbatim(id, journal.sent)).isEqualTo(3);

        List<CompanionLogEntry> copy = phone.loadLog(id);
        assertThat(copy).extracting(CompanionLogEntry::seq).containsExactly(1L, 2L, 3L);
        assertThat(copy.get(1).kind()).isEqualTo(Kind.NOTICE);
        assertThat(copy.get(1).text()).isEqualTo(CompanionStore.LOST_ON_HOST);
        assertThat(copy).extracting(CompanionLogEntry::text).containsExactly("a", CompanionStore.LOST_ON_HOST, "c");
        // the entries the host has are the same bytes on the phone
        String[] hostLines = logText(new File(dir, id)).split("\n");
        String[] phoneLines = logText(new File(phone.dir(), id)).split("\n");
        assertThat(phoneLines[0]).isEqualTo(hostLines[0]);
        assertThat(phoneLines[2]).isEqualTo(hostLines[1]);

        store.appendLog(id, notice("d"));
        assertThat(phone.appendVerbatim(id, journal.sent.subList(3, 4))).isEqualTo(1);
        assertThat(phone.loadLog(id)).extracting(CompanionLogEntry::text).endsWith("c", "d");
    }

    @Test
    public void copyTheHostsLogByteForByte() throws IOException {
        Recorder journal = new Recorder();
        store.setJournal(journal);
        store.appendLog(id, CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a")
                .withCard("Opt", FromHand.KNOWN));
        store.appendLog(id, CompanionLogEntry.undo(0, List.of(1), 1, "Ann", "undo"));

        CompanionStore phone = follower();
        phone.appendVerbatim(id, journal.sent);

        assertThat(logText(new File(phone.dir(), id))).isEqualTo(logText(new File(dir, id)));
    }

    @Test
    public void skipEntriesThePhoneAlreadyHolds() throws IOException {
        Recorder journal = new Recorder();
        store.setJournal(journal);
        store.appendLog(id, notice("a"));
        store.appendLog(id, notice("b"));
        CompanionStore phone = follower();
        phone.appendVerbatim(id, journal.sent.subList(0, 1));
        String before = logText(new File(phone.dir(), id));

        assertThat(phone.appendVerbatim(id, journal.sent.subList(0, 1))).isZero();
        assertThat(logText(new File(phone.dir(), id))).isEqualTo(before);
        assertThat(phone.appendVerbatim(id, journal.sent)).isEqualTo(1);
        assertThat(logText(new File(phone.dir(), id))).isEqualTo(logText(new File(dir, id)));
    }

    @Test
    public void refuseADifferentEntryForASeqItHolds() throws IOException {
        CompanionStore phone = follower();
        phone.appendVerbatim(id, List.of(Verbatim.entry(1, notice("a").withSeq(1).toLine())));

        assertThatThrownBy(() -> phone.appendVerbatim(id,
                List.of(Verbatim.entry(1, notice("other").withSeq(1).toLine()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void refuseAGapNoTombstoneFillsAndWriteNothing() throws IOException {
        CompanionStore phone = follower();
        List<Verbatim> withGap = List.of(Verbatim.entry(1, notice("a").withSeq(1).toLine()),
                Verbatim.entry(3, notice("c").withSeq(3).toLine()));

        assertThatThrownBy(() -> phone.appendVerbatim(id, withGap)).isInstanceOf(IllegalArgumentException.class);
        assertThat(phone.loadLog(id)).isEmpty();
    }

    @Test
    public void refuseALineWhoseSeqIsntTheOneGiven() throws IOException {
        CompanionStore phone = follower();

        assertThatThrownBy(() -> phone.appendVerbatim(id, List.of(Verbatim.entry(1, notice("a").withSeq(2)
                .toLine())))).isInstanceOf(IllegalArgumentException.class);
    }

    /** After a takeover (M3) the phone logs on its own, numbered after what it copied. */
    @Test
    public void numberALaterAppendAfterTheCopiedEntries() throws IOException {
        CompanionStore phone = follower();
        phone.appendVerbatim(id, List.of(Verbatim.entry(1, notice("a").withSeq(1).toLine()), Verbatim.gap(2)));

        assertThat(phone.appendLog(id, notice("mine")).entry().seq()).isEqualTo(3);
    }

    @Test
    public void createAFollowedGameUnderTheHostsId() throws IOException {
        CompanionStore phone = follower();

        assertThat(new File(phone.dir(), id)).isDirectory();
        assertThat(phone.loadActive()).get().extracting(CompanionStore.Saved::id).isEqualTo(id);
        assertThatThrownBy(() -> phone.createWithId("../escape", phone.loadActive().orElseThrow().setup()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** With no journal, an append writes exactly what v1 wrote (AC-114). */
    @Test
    public void writeTheSameLogWithAJournalAsWithout() throws IOException {
        store.appendLog(id, notice("a"));
        String without = logText(new File(dir, id));
        String other = store.create(store.loadActive().orElseThrow().setup());
        store.setJournal(new Recorder());
        store.appendLog(other, notice("a"));

        assertThat(logText(new File(dir, other))).isEqualTo(without);
    }
}
