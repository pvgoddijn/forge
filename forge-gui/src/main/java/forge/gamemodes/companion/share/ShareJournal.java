package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionLogEntry;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionStore.Verbatim;
import forge.gamemodes.net.event.NetEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A shared game's data as the host's phones get it (plan 3.3): every seq of its log, each an entry exactly as written
 * or a {@link Verbatim#gap gap} for a seq used up with nothing written, then its latest setup, and whether it ended.
 * The host's store tells it of each write ({@link CompanionStore.Journal}), in write order, on the thread that writes.
 * It is {@link #rebuild rebuilt} from the store when sharing starts: a seq missing on disk becomes a gap, as does a seq
 * a later entry skips over.
 * <p>
 * A phone that joins {@link #subscribe subscribes}: it gets the catch-up from the last seq it holds, then every later
 * item live, in write order, each once, however the threads that write and send interleave. With no subscriber the
 * journal only fills memory, and the store writes exactly what it would unshared (AC-114).
 */
public final class ShareJournal implements CompanionStore.Journal {
    /** The most log lines one {@link ShareMessages.Entries} carries, so a long catch-up goes in bounded frames. */
    public static final int MAX_LINES_PER_MESSAGE = 256;

    /** The queued item for the game's end. */
    private static final Object END = new Object();

    private final String gameId;
    /** Seq {@code i + 1} at index {@code i}: an entry, or a gap. Guarded by {@code this}, as is everything below. */
    private final List<Verbatim> log = new ArrayList<>();
    private CompanionSetup setup;
    private boolean ended;
    private final List<Subscription> subscribers = new ArrayList<>();

    private ShareJournal(String gameId, CompanionSetup setup) {
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
     * setup, and its end. Not installed: the store tells it of nothing ({@link #install} does both).
     */
    public static ShareJournal rebuild(CompanionStore store, String gameId) {
        ShareJournal journal = new ShareJournal(gameId, store.loadSetup(gameId));
        synchronized (journal) {
            for (Verbatim line : store.loadLogLines(gameId)) {
                journal.add(line);
            }
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
     * item written since, in write order: nothing is lost or given twice between the two.
     *
     * @param wakeup run after each item queued for this subscriber (not for the catch-up), on the writing thread, while
     *               the store holds its lock: it must not block, and should only arrange a {@link Subscription#drain}
     *               (on the peer's own thread or executor)
     */
    public synchronized Subscription subscribe(long have, Runnable wakeup) {
        Subscription subscription = new Subscription(setup, log.size(), wakeup);
        subscription.queue.addAll(catchUpItems(have));
        subscribers.add(subscription);
        return subscription;
    }

    /** One phone's place in the journal: what it has yet to be sent. */
    public final class Subscription implements AutoCloseable {
        private final CompanionSetup setup;
        private final long latestSeq;
        private final Runnable wakeup;
        /** Entries, gaps, setups and {@link #END}, in write order. Guarded by the journal. */
        private final List<Object> queue = new ArrayList<>();

        private Subscription(CompanionSetup setup, long latestSeq, Runnable wakeup) {
            this.setup = setup;
            this.latestSeq = latestSeq;
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
