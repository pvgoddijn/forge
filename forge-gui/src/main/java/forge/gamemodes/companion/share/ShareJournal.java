package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionLogEntry;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionStore.Verbatim;
import forge.gamemodes.net.event.NetEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A shared game's data as the host's phones get it (plan 3.3): every seq of its log, each an entry exactly as written
 * or a {@link Verbatim#gap gap} for a seq used up with nothing written, its steps, then its latest setup, and whether
 * it ended.
 * The host's store tells it of each write ({@link CompanionStore.Journal}), in write order, on the thread that writes.
 * It is {@link #rebuild rebuilt} from the store when sharing starts: a seq missing on disk becomes a gap, as does a seq
 * a later entry skips over.
 * <p>
 * A phone that joins {@link #subscribe subscribes}: it gets the catch-up from the last seq it holds, then every later
 * item live, in write order, each once, however the threads that write and send interleave. With no subscriber the
 * journal only fills memory, and the store writes exactly what it would unshared (AC-114).
 * <p>
 * <b>Steps.</b> The journal holds each step's sha256, not its text. A step saved goes to the subscribers live, as a
 * {@link ShareMessages.Step} in write order with the entries. Steps discarded send nothing: on the host they're
 * always followed by the {@code UNDO} entry that discards them ({@code CompanionTable}), and a phone applying that entry
 * runs {@code discardAfter(entry.step)} as the host did. A phone that joins gets the step index in its
 * {@link Subscription} (taken with the entries' catch-up), and the steps it wants are read from the store at send time
 * ({@link Subscription#steps}); see there why that's safe.
 */
public final class ShareJournal implements CompanionStore.Journal {
    /** The most log lines one {@link ShareMessages.Entries} carries, so a long catch-up goes in bounded frames. */
    public static final int MAX_LINES_PER_MESSAGE = 256;

    /** The queued item for the game's end. */
    private static final Object END = new Object();

    private final String gameId;
    /** Where wanted steps are read from. */
    private final CompanionStore store;
    /** Seq {@code i + 1} at index {@code i}: an entry, or a gap. Guarded by {@code this}, as is everything below. */
    private final List<Verbatim> log = new ArrayList<>();
    private CompanionSetup setup;
    private boolean ended;
    /** The sha256 of every step the store holds, by step number. */
    private final TreeMap<Integer, String> steps = new TreeMap<>();
    private final List<Subscription> subscribers = new ArrayList<>();

    private ShareJournal(CompanionStore store, String gameId, CompanionSetup setup) {
        this.store = store;
        this.gameId = gameId;
        this.setup = setup;
    }

    /**
     * Rebuilds the journal of {@code gameId} from {@code store} and installs it there, with no write in between: how
     * the host starts sharing a game.
     */
    public static ShareJournal install(CompanionStore store, String gameId) {
        return store.installJournal(s -> rebuild(s, gameId));
    }

    /**
     * The journal of {@code gameId} as {@code store} has it on disk: its log lines with a gap for each seq missing, its
     * steps' hashes (a game saved before steps has its {@code state.txt} as step 0), its setup, and its end. Not
     * installed: the store tells it of nothing ({@link #install} does both).
     */
    public static ShareJournal rebuild(CompanionStore store, String gameId) {
        ShareJournal journal = new ShareJournal(store, gameId, store.loadSetup(gameId));
        Map<Integer, String> hashes = store.stepHashes(gameId);
        synchronized (journal) {
            for (Verbatim line : store.loadLogLines(gameId)) {
                journal.add(line);
            }
            journal.steps.putAll(hashes);
            journal.ended = store.isEnded(gameId);
        }
        return journal;
    }

    /** The seq of the latest entry or gap, 0 for none. */
    public synchronized long latestSeq() {
        return log.size();
    }

    /** The game's setup, as last saved. */
    public synchronized CompanionSetup setup() {
        return setup;
    }

    /** Whether the game has ended. */
    public synchronized boolean ended() {
        return ended;
    }

    /** The latest step the store holds, or {@link CompanionStore#NO_STEP}. */
    public synchronized int latestStep() {
        return steps.isEmpty() ? CompanionStore.NO_STEP : steps.lastKey();
    }

    /** Every step the store holds, with its sha256, lowest first. */
    public synchronized SortedMap<Integer, String> stepIndex() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(steps));
    }

    /**
     * What a phone holding the entries up to {@code have} lacks: every entry after it as {@link ShareMessages.Entries}
     * and every gap as {@link ShareMessages.Gap}, in seq order, then {@link ShareMessages.End} if the game has ended.
     * Empty for a phone that holds everything.
     */
    public List<NetEvent> catchUp(long have) {
        List<Object> items;
        synchronized (this) {
            items = catchUpItems(have);
        }
        return toMessages(items);
    }

    /**
     * Starts sending this journal to a phone that holds the entries up to {@code have} ({@link ShareMessages.Hello#have}).
     * Its first {@link Subscription#drain} gives {@link #catchUp(long)} as it was at this moment, and later drains every
     * item written since, in write order: nothing is lost or given twice between the two. Its
     * {@link Subscription#stepIndex} is the steps at that same moment, and steps saved later come in the drains.
     *
     * @param wakeup run after each item queued for this subscriber (not for the catch-up), on the writing thread, while
     *               the store holds its lock: it must not block, and should only arrange a {@link Subscription#drain}
     *               (on the peer's own thread or executor)
     */
    public synchronized Subscription subscribe(long have, Runnable wakeup) {
        Subscription subscription = new Subscription(setup, log.size(), latestStep(), stepIndex(), wakeup);
        subscription.queue.addAll(catchUpItems(have));
        subscribers.add(subscription);
        return subscription;
    }

    /** One phone's place in the journal: what it has yet to be sent. */
    public final class Subscription implements AutoCloseable {
        private final CompanionSetup setup;
        private final long latestSeq;
        private final int latestStep;
        private final SortedMap<Integer, String> stepIndex;
        private final Runnable wakeup;
        /** Entries, gaps, setups and {@link #END}, in write order. Guarded by the journal. */
        private final List<Object> queue = new ArrayList<>();

        private Subscription(CompanionSetup setup, long latestSeq, int latestStep, SortedMap<Integer, String> stepIndex,
                Runnable wakeup) {
            this.setup = setup;
            this.latestSeq = latestSeq;
            this.latestStep = latestStep;
            this.stepIndex = stepIndex;
            this.wakeup = wakeup;
        }

        /** The setup when this subscription started, for {@link ShareMessages.Welcome}. */
        public CompanionSetup setup() {
            return setup;
        }

        /** The latest seq when this subscription started: the catch-up ends there, for {@link ShareMessages.Welcome}. */
        public long latestSeq() {
            return latestSeq;
        }

        /** The latest step when this subscription started, for {@link ShareMessages.Welcome}. */
        public int latestStep() {
            return latestStep;
        }

        /** The steps and their sha256 when this subscription started, for {@link ShareMessages.Welcome}. */
        public SortedMap<Integer, String> stepIndex() {
            return stepIndex;
        }

        /**
         * The steps {@code wanted} as they are on disk now, lowest first, each hashed as read; a step no longer on disk
         * is left out. Reads the store, so it must not be called while holding the journal's lock (the lock order is
         * store, then journal).
         * <p>
         * A step can be discarded or saved again between the index and this read. That's safe because the change is
         * also queued for this subscriber, after the catch-up: a step discarded comes with its {@code UNDO} entry, a
         * step saved again comes as a live {@link ShareMessages.Step}. So a step sent here, or left out, is at worst
         * stale until the drains that follow, which put the phone where the host is. Each step sent carries the hash of
         * the text it carries, so the phone's check never refuses a step for having changed on the host.
         */
        public List<ShareMessages.Step> steps(int... wanted) {
            int[] sorted = wanted.clone();
            Arrays.sort(sorted);
            List<ShareMessages.Step> found = new ArrayList<>();
            int last = Integer.MIN_VALUE;
            for (int n : sorted) {
                if (n != last && n >= 0) {
                    store.stepText(gameId, n).ifPresent(text -> found.add(new ShareMessages.Step(n, text)));
                }
                last = n;
            }
            return found;
        }

        /**
         * The messages queued since the last drain, oldest first, to send in this order: first the catch-up, then the
         * live items. Each item is given once. Empty when there's nothing new, or after {@link #close}.
         */
        public List<NetEvent> drain() {
            List<Object> items;
            synchronized (ShareJournal.this) {
                items = new ArrayList<>(queue);
                queue.clear();
            }
            return toMessages(items);
        }

        /** Stops queueing for this phone, and drops what it had yet to be sent. */
        @Override
        public void close() {
            synchronized (ShareJournal.this) {
                subscribers.remove(this);
                queue.clear();
            }
        }
    }

    @Override
    public void appended(String id, CompanionLogEntry entry, String line) {
        if (gameId.equals(id)) {
            List<Runnable> wake;
            synchronized (this) {
                wake = add(Verbatim.entry(entry.seq(), line));
            }
            wake.forEach(Runnable::run);
        }
    }

    @Override
    public void appendFailed(String id, long seq) {
        if (gameId.equals(id)) {
            List<Runnable> wake;
            synchronized (this) {
                wake = add(Verbatim.gap(seq));
            }
            wake.forEach(Runnable::run);
        }
    }

    @Override
    public void setupSaved(String id, CompanionSetup changed) {
        if (gameId.equals(id)) {
            List<Runnable> wake;
            synchronized (this) {
                setup = changed;
                wake = publish(List.of(changed));
            }
            wake.forEach(Runnable::run);
        }
    }

    @Override
    public void stepSaved(String id, int n, String text) {
        if (gameId.equals(id)) {
            ShareMessages.Step step = new ShareMessages.Step(n, text); // hashed before taking the lock
            List<Runnable> wake;
            synchronized (this) {
                steps.put(n, step.sha256());
                wake = publish(List.of(step));
            }
            wake.forEach(Runnable::run);
        }
    }

    @Override
    public void stepsDiscarded(String id, int afterN) {
        if (gameId.equals(id)) {
            synchronized (this) {
                // nothing to send: the UNDO entry that follows carries it
                steps.tailMap(afterN, false).clear();
            }
        }
    }

    @Override
    public void ended(String id) {
        if (gameId.equals(id)) {
            List<Runnable> wake;
            synchronized (this) {
                ended = true;
                wake = publish(List.of(END));
            }
            wake.forEach(Runnable::run);
        }
    }

    /**
     * Adds {@code item} at its seq, after a gap for each seq it skips over, and queues them for the subscribers. A seq
     * already held is left out: it can't be written again. Returns the wakeups to run once the lock is released.
     */
    private List<Runnable> add(Verbatim item) {
        if (item.seq() <= log.size()) {
            System.err.println("Companion journal of " + gameId + " already has seq " + item.seq() + ": left out");
            return List.of();
        }
        List<Object> added = new ArrayList<>();
        for (long seq = log.size() + 1; seq < item.seq(); seq++) {
            added.add(Verbatim.gap(seq));
        }
        added.add(item);
        for (Object each : added) {
            log.add((Verbatim) each);
        }
        return publish(added);
    }

    private List<Runnable> publish(List<Object> items) {
        List<Runnable> wake = new ArrayList<>(subscribers.size());
        for (Subscription subscription : subscribers) {
            subscription.queue.addAll(items);
            wake.add(subscription.wakeup);
        }
        return wake;
    }

    private List<Object> catchUpItems(long have) {
        int from = (int) Math.max(0, Math.min(have, log.size()));
        List<Object> items = new ArrayList<>(log.subList(from, log.size()));
        if (ended) {
            items.add(END);
        }
        return items;
    }

    /** The items as messages: consecutive entries together, up to {@link #MAX_LINES_PER_MESSAGE} a message. */
    private static List<NetEvent> toMessages(List<Object> items) {
        List<NetEvent> messages = new ArrayList<>();
        List<Verbatim> entries = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Verbatim v && !v.isGap()) {
                entries.add(v);
                if (entries.size() == MAX_LINES_PER_MESSAGE) {
                    messages.add(new ShareMessages.Entries(entries));
                    entries.clear();
                }
                continue;
            }
            if (!entries.isEmpty()) {
                messages.add(new ShareMessages.Entries(entries));
                entries.clear();
            }
            if (item instanceof Verbatim gap) {
                messages.add(new ShareMessages.Gap(gap.seq()));
            } else if (item instanceof CompanionSetup changed) {
                messages.add(new ShareMessages.Setup(changed));
            } else if (item instanceof ShareMessages.Step step) {
                messages.add(step);
            } else if (item == END) {
                messages.add(new ShareMessages.End());
            }
        }
        if (!entries.isEmpty()) {
            messages.add(new ShareMessages.Entries(entries));
        }
        return messages;
    }
}
