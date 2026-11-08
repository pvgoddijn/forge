package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionLogEntry;
import forge.gamemodes.companion.CompanionLogEntry.Kind;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionStore.Verbatim;
import forge.gamemodes.net.event.NetEvent;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The host's journal of a shared game (plan 3.3), fed by a real store in a temporary folder. */
public class ShareJournalShould {
    private File dir;
    private CompanionStore store;
    private String id;
    private CompanionSetup setup;

    @BeforeMethod
    public void createStore() throws IOException {
        dir = Files.createTempDirectory("companion-journal").toFile();
        dir.deleteOnExit();
        store = new CompanionStore(dir);
        setup = CompanionSetup.withDefaults(List.of(
                new CompanionSetup.Seat("Ann", true), new CompanionSetup.Seat("Bo", false)));
        id = store.create(setup);
    }

    private static CompanionLogEntry notice(String text) {
        return CompanionLogEntry.of(Kind.NOTICE, 0, 1, null, null, text);
    }

    private File log() {
        return new File(new File(dir, id), "log.txt");
    }

    /** Each message in short: "entries 1-2", "gap 3", "setup", "end". */
    private static List<String> describe(List<NetEvent> messages) {
        List<String> out = new ArrayList<>();
        for (NetEvent m : messages) {
            if (m instanceof ShareMessages.Entries e) {
                List<Verbatim> items = e.items();
                out.add("entries " + items.get(0).seq() + "-" + items.get(items.size() - 1).seq());
            } else if (m instanceof ShareMessages.Gap g) {
                out.add("gap " + g.seq());
            } else if (m instanceof ShareMessages.Setup) {
                out.add("setup");
            } else if (m instanceof ShareMessages.End) {
                out.add("end");
            } else {
                out.add(m.toString());
            }
        }
        return out;
    }

    /** The seqs the messages carry, entries and gaps, in the order they come. */
    private static List<Long> seqs(List<NetEvent> messages) {
        List<Long> out = new ArrayList<>();
        for (NetEvent m : messages) {
            if (m instanceof ShareMessages.Entries e) {
                e.items().forEach(v -> out.add(v.seq()));
            } else if (m instanceof ShareMessages.Gap g) {
                out.add(g.seq());
            }
        }
        return out;
    }

    private static List<Long> range(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().collect(Collectors.toList());
    }

    @Test
    public void giveASubscriberEachEntryTheSetupAndTheEndInWriteOrder() {
        ShareJournal journal = ShareJournal.install(store, id);
        ShareJournal.Subscription sub = journal.subscribe(0, () -> { });

        store.appendLog(id, notice("a"));
        store.saveSetup(id, setup.withLife(30));
        store.appendLog(id, notice("b"));
        store.appendLog(id, notice("c"));
        store.end(id);

        List<NetEvent> sent = sub.drain();
        assertThat(describe(sent)).containsExactly("entries 1-1", "setup", "entries 2-3", "end");
        assertThat(((ShareMessages.Setup) sent.get(1)).setup().life()).isEqualTo(30);
        assertThat(sub.drain()).isEmpty();
        assertThat(journal.setup().life()).isEqualTo(30);
        assertThat(journal.ended()).isTrue();
    }

    @Test
    public void carryEachLineExactlyAsWritten() throws IOException {
        ShareJournal journal = ShareJournal.install(store, id);
        store.appendLog(id, notice("a"));
        store.appendLog(id, CompanionLogEntry.undo(0, List.of(1), 1, "Ann", "undo"));

        List<String> lines = new ArrayList<>();
        for (NetEvent m : journal.catchUp(0)) {
            ((ShareMessages.Entries) m).items().forEach(v -> lines.add(v.line()));
        }

        String onDisk = new String(Files.readAllBytes(log().toPath()), StandardCharsets.UTF_8);
        assertThat(String.join("\n", lines) + "\n").isEqualTo(onDisk);
    }

    @Test
    public void rebuildFromTheStoreWithAGapForASeqMissingOnDisk() throws IOException {
        for (String text : List.of("one", "two", "three", "four")) {
            store.appendLog(id, notice(text));
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(log().toPath(), StandardCharsets.UTF_8));
        lines.set(0, "a plain line from before entries"); // reads as seq 1
        lines.remove(1); // seq 2 is lost
        Files.write(log().toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));

        ShareJournal journal = ShareJournal.rebuild(store, id);

        assertThat(describe(journal.catchUp(0))).containsExactly("entries 1-1", "gap 2", "entries 3-4");
        assertThat(journal.latestSeq()).isEqualTo(4);
        assertThat(journal.setup()).isEqualTo(setup);
        assertThat(journal.ended()).isFalse();
    }

    @Test
    public void rebuildAnEndedGameWithItsEnd() {
        store.appendLog(id, notice("a"));
        store.end(id);

        assertThat(describe(ShareJournal.rebuild(store, id).catchUp(0))).containsExactly("entries 1-1", "end");
    }

    @Test
    public void catchUpFromTheLastSeqThePhoneHolds() {
        ShareJournal journal = ShareJournal.install(store, id);
        for (int i = 0; i < 5; i++) {
            store.appendLog(id, notice("n" + i));
        }

        assertThat(describe(journal.catchUp(3))).containsExactly("entries 4-5");
        assertThat(describe(journal.catchUp(0))).containsExactly("entries 1-5");
        assertThat(journal.catchUp(5)).isEmpty();
        assertThat(journal.catchUp(9)).isEmpty();
    }

    @Test
    public void sendALongCatchUpInBoundedMessages() {
        ShareJournal journal = ShareJournal.install(store, id);
        int n = ShareJournal.MAX_LINES_PER_MESSAGE * 2 + 7;
        for (int i = 0; i < n; i++) {
            store.appendLog(id, notice("n" + i));
        }

        List<NetEvent> messages = journal.catchUp(0);

        assertThat(messages).hasSize(3);
        assertThat(messages).allSatisfy(m ->
                assertThat(((ShareMessages.Entries) m).items()).hasSizeLessThanOrEqualTo(
                        ShareJournal.MAX_LINES_PER_MESSAGE));
        assertThat(seqs(messages)).isEqualTo(range(1, n));
    }

    @Test
    public void sendAGapForAFailedAppend() throws IOException {
        ShareJournal journal = ShareJournal.install(store, id);
        ShareJournal.Subscription sub = journal.subscribe(0, () -> { });
        store.appendLog(id, notice("a"));
        File kept = new File(dir, "kept-log.txt");
        Files.move(log().toPath(), kept.toPath());
        assertThat(log().mkdir()).isTrue(); // the log can't be written now
        store.appendLog(id, notice("b"));
        assertThat(log().delete()).isTrue();
        Files.move(kept.toPath(), log().toPath());
        store.appendLog(id, notice("c"));

        assertThat(describe(sub.drain())).containsExactly("entries 1-1", "gap 2", "entries 3-3");
        assertThat(describe(journal.catchUp(0))).containsExactly("entries 1-1", "gap 2", "entries 3-3");
    }

    /** A write that failed before the journal was installed leaves a hole the next entry shows. */
    @Test
    public void fillAHoleBeforeALaterEntryWithAGap() throws IOException {
        store.appendLog(id, notice("a"));
        File kept = new File(dir, "kept-log.txt");
        Files.move(log().toPath(), kept.toPath());
        assertThat(log().mkdir()).isTrue();
        store.appendLog(id, notice("b")); // seq 2, lost, with no journal to tell
        assertThat(log().delete()).isTrue();
        Files.move(kept.toPath(), log().toPath());

        ShareJournal journal = ShareJournal.install(store, id);
        assertThat(journal.latestSeq()).isEqualTo(1);
        store.appendLog(id, notice("c"));

        assertThat(describe(journal.catchUp(0))).containsExactly("entries 1-1", "gap 2", "entries 3-3");
    }

    @Test
    public void giveTheWelcomeTheSetupAndLatestSeqAtTheMomentOfSubscribing() {
        ShareJournal journal = ShareJournal.install(store, id);
        store.appendLog(id, notice("a"));
        store.appendLog(id, notice("b"));

        ShareJournal.Subscription sub = journal.subscribe(1, () -> { });
        store.saveSetup(id, setup.withLife(20));
        store.appendLog(id, notice("c"));

        assertThat(sub.latestSeq()).isEqualTo(2);
        assertThat(sub.setup()).isEqualTo(setup);
        assertThat(describe(sub.drain())).containsExactly("entries 2-2", "setup", "entries 3-3");
    }

    @Test
    public void wakeASubscriberForEachLiveItemAndStopAfterClose() {
        ShareJournal journal = ShareJournal.install(store, id);
        AtomicInteger wakes = new AtomicInteger();
        ShareJournal.Subscription sub = journal.subscribe(0, wakes::incrementAndGet);

        store.appendLog(id, notice("a"));
        store.appendLog(id, notice("b"));
        assertThat(wakes.get()).isEqualTo(2);

        sub.close();
        store.appendLog(id, notice("c"));
        assertThat(wakes.get()).isEqualTo(2);
        assertThat(sub.drain()).isEmpty();
        assertThat(journal.latestSeq()).isEqualTo(3);
    }

    @Test
    public void ignoreOtherGames() {
        ShareJournal journal = ShareJournal.install(store, id);
        String other = store.create(setup);

        store.appendLog(other, notice("elsewhere"));
        store.end(other);

        assertThat(journal.catchUp(0)).isEmpty();
        assertThat(journal.ended()).isFalse();
    }

    /**
     * Plan 3.3, order of writes: entries are appended on one thread while phones subscribe and drain on others. Each
     * subscriber gets every seq after its {@code have} exactly once, in order.
     */
    @Test
    public void loseAndRepeatNothingBetweenTheCatchUpAndTheLiveItems() throws Exception {
        ShareJournal journal = ShareJournal.install(store, id);
        int total = 600;
        CountDownLatch started = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            started.countDown();
            for (int i = 0; i < total; i++) {
                store.appendLog(id, notice("n" + i));
            }
        });
        writer.start();
        started.await();

        List<ShareJournal.Subscription> subs = new ArrayList<>();
        List<Long> haves = new ArrayList<>();
        List<List<Long>> received = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean();
        List<Thread> drainers = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            long have = k % 2 == 0 ? 0 : journal.latestSeq() / 2;
            ShareJournal.Subscription sub = journal.subscribe(have, () -> { });
            List<Long> got = new ArrayList<>();
            subs.add(sub);
            haves.add(have);
            received.add(got);
            Thread drainer = new Thread(() -> {
                while (!done.get()) {
                    got.addAll(seqs(sub.drain()));
                    Thread.yield();
                }
                got.addAll(seqs(sub.drain()));
            });
            drainer.start();
            drainers.add(drainer);
            Thread.sleep(1);
        }
        writer.join();
        done.set(true);
        for (Thread drainer : drainers) {
            drainer.join();
        }

        assertThat(subs).as("a phone subscribed while entries were being written")
                .anySatisfy(sub -> assertThat(sub.latestSeq()).isBetween(1L, total - 1L));
        for (int k = 0; k < subs.size(); k++) {
            assertThat(received.get(k)).as("subscriber %d, have %d", k, haves.get(k))
                    .isEqualTo(range(haves.get(k) + 1, total));
        }
    }

    @Test
    public void sendMessagesThatSurviveJavaSerialization() throws Exception {
        store.appendLog(id, notice("a"));
        store.appendLog(id, notice("b"));
        List<Verbatim> lines = store.loadLogLines(id);

        ShareMessages.Entries entries = roundTrip(new ShareMessages.Entries(lines));
        ShareMessages.Gap gap = roundTrip(new ShareMessages.Gap(6));
        ShareMessages.Setup changed = roundTrip(new ShareMessages.Setup(setup.withLife(25)));
        ShareMessages.End end = roundTrip(new ShareMessages.End());
        ShareMessages.Hello hello = roundTrip(new ShareMessages.Hello(7, "g", 5));
        ShareMessages.Welcome welcome = roundTrip(new ShareMessages.Welcome("g", setup, 9));

        assertThat(entries.items()).isEqualTo(lines);
        assertThat(gap.seq()).isEqualTo(6);
        assertThat(changed.setup()).isEqualTo(setup.withLife(25));
        assertThat(end).isNotNull();
        assertThat(hello.have()).isEqualTo(5);
        assertThat(hello.token()).isEqualTo(7);
        assertThat(welcome.setup()).isEqualTo(setup);
        assertThat(welcome.latestSeq()).isEqualTo(9);
        assertThat(welcome.game()).isEqualTo("g");
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T message) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(message);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
