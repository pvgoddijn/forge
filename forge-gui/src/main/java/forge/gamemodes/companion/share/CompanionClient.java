package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionLogEntry;
import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.CompanionStore;
import forge.gamemodes.companion.CompanionStore.Verbatim;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IDeviceAdapter;
import forge.interfaces.ILobbyListener;
import forge.util.LogSafe;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A joined phone's connection to a shared Companion game (plan 2.2): net play's client, with Companion's admission and
 * data messages on top, as {@code ReplayClient} reuses it for a recording. A raw preamble with the code's token goes
 * first (the host's {@link TokenGate} reads it before deserializing anything), then net play's login, then
 * {@link ShareMessages.Hello} with the token again; the host answers {@link ShareMessages.Welcome}, then sends the view
 * through net play's own protocol handler to {@code gui}: the board, every seat ({@code openView}), deltas. Every seat
 * being the phone's, the client builds a controller per seat, and the host drops whatever they send (M1).
 *
 * <p>The heartbeat is every 5 s, as the host counts a phone silent for 15 s as gone (plan 3.1), and the phone counts
 * the host gone after 15 s too. Reconnect is net play's, with Companion's backoff: 0.5 s doubling to 5 s, and it never
 * gives up (slice 04). Each reconnect is a fresh join, in the join's order: login, hello with what the copy holds,
 * welcome (which counts as the reconnect's answer), want, the steps, the entries, a fresh attach, then the epoch. A
 * phone that reconnects to an ended game gets the end, and stops following. {@link #rejoin} follows a game this phone
 * holds a copy of again, and keeps trying from the start, even when the host can't be reached at first (a restarted
 * phone). {@link LinkListener}s hear whether it's connected or trying to.
 *
 * <p>With a device adapter, each connection's socket comes from {@link IDeviceAdapter#openLocalNetworkSocket}, bound to
 * the Wi-Fi network, so a phone with mobile data on still reaches a host on a Wi-Fi without internet; nothing else in
 * the app is bound. The client holds the local network ({@link IDeviceAdapter#holdLocalNetwork}) from its first connect
 * and lets it go on every way out: {@link #close} (also when it comes during a connect), a first connect that fails, a
 * connection closed with no reconnect to follow (refused, dropped before the game, the game never arrived), and net
 * play's reconnect giving up or losing the seat.
 *
 * <p>The data stream (plan 3.3): the client keeps a copy of the followed game in its own {@link CompanionStore}, in a
 * folder under the host's game id, byte for byte as the host's log. On the welcome it makes the folder and writes
 * {@link ShareState} ({@code share.json}), drops its steps past the host's latest, and answers
 * {@link ShareMessages.Want} with the steps of the welcome's index it lacks or holds with another hash. Each
 * {@link ShareMessages.Step} is written once its hash checks ({@link CompanionStore#writeStep}); each
 * {@link ShareMessages.Entries} and {@link ShareMessages.Gap} is appended ({@link CompanionStore#appendVerbatim}), an
 * {@code UNDO} entry after the welcome's latest seq dropping the steps it took back as the host did; each
 * {@link ShareMessages.Setup} is saved, and {@link ShareMessages.End} marks the game ended. All of it is written on one
 * thread of its own, in the order it came, and nothing else is done with it: the {@link CopyListener}s hear of it once
 * it's on disk. Each hello says which game the phone holds and the seq of its last entry, so the host sends only
 * what's missing. The copy never calls {@link CompanionStore#loadState}, which would write to it.
 *
 * <p>On each {@link ShareMessages.Epoch} (after every full view the host sends) the tracker's stale card views go:
 * see {@link FGameClient#resetTracker} for why that runs only once the full view is applied.
 */
public class CompanionClient extends FGameClient {
    static final int HEARTBEAT_SECONDS = 5;
    static final int READER_IDLE_SECONDS = 15;
    /** How long after the welcome the game may take to arrive ({@code openView}) before the client gives up. */
    static final int ATTACH_SECONDS = 30;
    /** The reconnect backoff: the first wait, doubling up to {@link #MAX_BACKOFF_MILLIS}, without end. */
    static final long FIRST_BACKOFF_MILLIS = 500;
    static final long MAX_BACKOFF_MILLIS = 5_000;
    /** A host on the local network answers a connect at once, or is gone: no need for Netty's 30 s. */
    static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** How this client's link to the host is, for the screens (chunk B's N6). */
    public enum Link {
        /** The first connect is under way. */
        CONNECTING,
        /** The host welcomed this phone on the current connection. */
        CONNECTED,
        /** The connection was lost, or the host couldn't be reached: trying again, without end. */
        RECONNECTING,
        /** The host's game has ended: nothing more follows, and no reconnect. */
        ENDED,
        /** Over without the game's end: closed, or refused or dropped before it was following, so not retried. */
        CLOSED
    }

    /** Told each time the {@link Link} changes. Netty's, the reconnect's or the caller's thread. */
    public interface LinkListener {
        void linkChanged(Link link);
    }

    private final List<LinkListener> linkListeners = new CopyOnWriteArrayList<>();
    private Link link = Link.CONNECTING;
    /** Whether the host sent the game's end. */
    private volatile boolean ended;

    private final int token;
    private volatile String welcomedTo;
    private volatile int epoch;
    /** True from a connection's {@code channelActive} to its {@code channelInactive}. */
    private volatile boolean connectionOpen;
    /** Whether the lobby listeners heard of the current connection's close. */
    private volatile boolean listenersTold;
    private volatile boolean left;
    private volatile boolean viewArrived;
    private volatile boolean attachTimedOut;
    /** The listeners added from outside, for a close net play's handler didn't count (see the data handler). */
    private final List<ILobbyListener> listeners = new CopyOnWriteArrayList<>();
    /** Holds the local network while joined and makes its sockets; null: the default route (desktop, tests). */
    private final IDeviceAdapter network;
    private final AtomicBoolean held = new AtomicBoolean();
    /** The newest connection whose {@code channelActive} ran: only its close counts (see the data handler). */
    private volatile Channel newestChannel;

    /** Told of what the copy of the followed game has written, once it's on disk. On the copy's thread. */
    public interface CopyListener {
        /**
         * The host sent the entries {@code fromSeq} to {@code toSeq} of the game {@code game} (or tombstones for them),
         * and the copy now holds every entry up to {@code toSeq}.
         */
        default void written(String game, long fromSeq, long toSeq) { }

        /** The game's setup changed, and was written. */
        default void setupChanged(String game, CompanionSetup setup) { }

        /** The host ended the game: it's marked ended in the store, and nothing of it follows. */
        default void ended(String game) { }

        /** The copy asked the host for the steps {@code steps} of {@code game} (none: it holds them all). */
        default void wanted(String game, int[] steps) { }

        /** The host's step {@code n} of {@code game} was written. */
        default void stepWritten(String game, int n) { }

        /** What the host sent couldn't be written: the copy is behind, or doesn't match the host's. */
        default void failed(String game, RuntimeException e) { }
    }

    private final JoinCode code;
    /** Where the copy of the followed game is kept. Written on {@link #copier} only. */
    private final CompanionStore store;
    /** Writes the data messages to the store, one at a time, in the order they came. */
    private final ExecutorService copier = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Companion-Copy");
        t.setDaemon(true);
        return t;
    });
    private final List<CopyListener> copyListeners = new CopyOnWriteArrayList<>();
    /** The game this phone holds a copy of, or null before the first welcome; said in each hello. */
    private volatile String followed;
    /** The seq of the last entry of {@link #followed} written: the hello's {@code have}. */
    private volatile long heldSeq = ShareMessages.Hello.NOTHING;
    /**
     * The host's latest seq at this connection's welcome. An {@code UNDO} entry up to it is already reflected in the
     * welcome's step index, so only those after it drop steps. The copy's thread only.
     */
    private long welcomeSeq = Long.MAX_VALUE;

    /** Joins with no device adapter (desktop, tests), keeping the copy of the game in {@code store}. */
    public CompanionClient(String username, IGuiGame gui, JoinCode code, CompanionStore store) {
        this(username, gui, code, null, store);
    }

    /**
     * {@code network} holds the local network and binds each connection's socket to it (Android); null for none.
     * {@code store} keeps the copy of the followed game.
     */
    public CompanionClient(String username, IGuiGame gui, JoinCode code, IDeviceAdapter network,
                           CompanionStore store) {
        super(username, gui, code.address().getHostAddress(), code.port());
        this.code = code;
        this.token = code.token();
        this.network = network;
        this.store = store;
        super.addLobbyListener(new ILobbyListener() {
            @Override
            public void message(String source, String message, ChatMessage.MessageType type) { }

            @Override
            public void update(GameLobby.GameLobbyData state, int slot) { }

            @Override
            public void close() {
                listenersTold = true;
            }

            @Override
            public ClientGameLobby getLobby() {
                return null;
            }
        });
    }

    /**
     * True while this client still follows the host: not closed, and connected or in net play's reconnect. False once
     * it was closed, the host refused or dropped it before the game, or the reconnect gave up.
     */
    public boolean isActive() {
        if (left) {
            return false;
        }
        ReconnectState state = getReconnectState();
        return state == ReconnectState.RECONNECTING || state == ReconnectState.CONNECTED && connectionOpen;
    }

    @Override
    public void addLobbyListener(ILobbyListener listener) {
        listeners.add(listener);
        super.addLobbyListener(listener);
    }

    @Override
    public void close() {
        left = true;
        super.close();
        holdLocalNetwork(false);
        copier.shutdown(); // what came before the close is still written
        setLink(Link.CLOSED);
    }

    /** Tells {@code listener} of each change of the link from now on. */
    public void addLinkListener(LinkListener listener) {
        linkListeners.add(listener);
    }

    /** The link to the host now. */
    public Link link() {
        synchronized (linkListeners) {
            return link;
        }
    }

    /** Whether the host sent the game's end: nothing more follows. */
    public boolean hostEnded() {
        return ended;
    }

    /**
     * Sets the link and tells the listeners, outside the lock, when it changed. {@link Link#ENDED} and
     * {@link Link#CLOSED} are final: an ended game stays ended for the screens, though the client is closed after.
     */
    private void setLink(Link to) {
        synchronized (linkListeners) {
            if (link == to || link == Link.CLOSED || link == Link.ENDED) {
                return;
            }
            link = to;
        }
        netLog.info("[Companion] Link: {}", to);
        for (LinkListener listener : linkListeners) {
            listener.linkChanged(to);
        }
    }

    /**
     * Follows again the game {@code game} this phone holds a copy of, from its {@code share.json} (Load game, a
     * restarted phone): like {@link #followAgain} then {@link #connect}, but as a phone that was following, so it
     * keeps trying, without end, from the first connect on, even when the host can't be reached now. Blocks for the
     * first connect, at most {@link #CONNECT_TIMEOUT_MILLIS}; never throws for an unreachable host. Throws as
     * {@link #followAgain} does when the copy can't be read.
     */
    public void rejoin(String game) {
        followAgain(game);
        markSeated();
        try {
            connect();
        } catch (Exception e) { // Netty rethrows a refused connect as a checked ConnectException it doesn't declare
            netLog.info("[Companion] Can't reach the host of {} now, trying again: {}", game, e.toString());
            if (!left) {
                startReconnecting();
            }
        }
    }

    /**
     * Follows again the game {@code game} this phone holds a copy of ("Join again"): the hello asks only for the
     * entries after the last one in the copy. Before {@link #connect}.
     */
    public void followAgain(String game) {
        long last = ShareMessages.Hello.NOTHING;
        for (Verbatim line : store.loadLogLines(game)) {
            last = Math.max(last, line.seq());
        }
        heldSeq = last;
        followed = game;
    }

    /** Tells {@code listener} of what the copy writes from now on, on the copy's thread. */
    public void addCopyListener(CopyListener listener) {
        copyListeners.add(listener);
    }

    /** The seq of the copy's last entry written, {@link ShareMessages.Hello#NOTHING} for none. */
    public long heldSeq() {
        return heldSeq;
    }

    /**
     * Net play's connect, on the local network. A reconnect attempt comes through here too, and holds it again; it
     * first lets go of the attempt before it (a welcome that didn't come in time): it's no longer the newest, so a
     * welcome that still comes on it counts for nothing, and it's closed if it's still open, so a host never holds two
     * connections of one phone.
     */
    @Override
    public void connect() {
        Channel stale = newestChannel;
        if (stale != null && getReconnectState() == ReconnectState.RECONNECTING) {
            newestChannel = null; // before the close: a welcome read on it meanwhile is a stale one's
            connectionOpen = false;
            if (stale.isOpen()) {
                stale.close();
            }
        }
        holdLocalNetwork(true);
        boolean connected = false;
        try {
            super.connect();
            connected = true;
        } finally {
            // Netty rethrows a refused connect as a checked ConnectException it doesn't declare, so not in a catch.
            // Let go when the first connect failed (no join, no retry), and when close() came while this connect was
            // under way: close() let go before this connect held again
            if (left || !connected && getReconnectState() != ReconnectState.RECONNECTING) {
                holdLocalNetwork(false);
            }
        }
    }

    @Override
    protected void onReconnectStateChanged(ReconnectState state) {
        if (state == ReconnectState.FAILED || state == ReconnectState.SEAT_LOST) {
            holdLocalNetwork(false); // a manual rejoin connects, and so holds, again
            setLink(Link.CLOSED); // never with Companion's backoff
        } else if (state == ReconnectState.RECONNECTING) {
            setLink(Link.RECONNECTING);
        } else if (state == ReconnectState.CONNECTED && connectionOpen) {
            setLink(Link.CONNECTED);
        }
    }

    /** 0.5 s, doubling up to 5 s, and never giving up: a joined phone follows as long as it's in the game. */
    @Override
    protected long reconnectDelayMillis(int attemptIndex) {
        return backoffMillis(attemptIndex);
    }

    static long backoffMillis(int attemptIndex) {
        long delay = FIRST_BACKOFF_MILLIS;
        for (int i = 0; i < attemptIndex && delay < MAX_BACKOFF_MILLIS; i++) {
            delay *= 2;
        }
        return Math.min(delay, MAX_BACKOFF_MILLIS);
    }

    /**
     * Each reconnect's attach sends the whole board into the view still shown, and its epoch then drops the stale
     * card views: the same tracker throughout, as after an undo.
     */
    @Override
    protected boolean keepsTrackerAcrossConnections() {
        return true;
    }

    @Override
    protected int connectTimeoutMillis() {
        return CONNECT_TIMEOUT_MILLIS;
    }

    /** Each connection's socket, bound to the Wi-Fi network when the device has one. */
    @Override
    protected ChannelFactory<? extends Channel> channelFactory() {
        if (network == null) {
            return super.channelFactory();
        }
        return () -> {
            SocketChannel socket = network.openLocalNetworkSocket();
            return socket == null ? new NioSocketChannel() : new NioSocketChannel(socket);
        };
    }

    /** Whether this client holds the local network now. */
    public boolean isHoldingLocalNetwork() {
        return held.get();
    }

    private void holdLocalNetwork(boolean hold) {
        if (network != null && held.compareAndSet(!hold, hold)) {
            netLog.info("[Companion] {} the local network", hold ? "Holding" : "Letting go of");
            network.holdLocalNetwork(hold);
        }
    }

    /**
     * The shared game's id once the host welcomed this phone and its copy was made, else null: a checked
     * {@link CompanionStore#isGameId game id}, set on the copy's thread.
     */
    public String welcomedTo() {
        return welcomedTo;
    }

    /**
     * True once the host welcomed this phone but its game didn't arrive within {@link #ATTACH_SECONDS}, so the client
     * closed the connection (the lobby listeners' {@code close()} follows).
     */
    public boolean attachTimedOut() {
        return attachTimedOut;
    }

    /** The full views the host has sent this phone so far (plan 3.2); 0 before the first. */
    public int epoch() {
        return epoch;
    }

    @Override
    protected ChannelHandler newExtraInboundHandler() {
        return new DataHandler();
    }

    @Override
    protected int readerIdleSeconds() {
        return READER_IDLE_SECONDS;
    }

    @Override
    protected int heartbeatIntervalSeconds() {
        return HEARTBEAT_SECONDS;
    }

    /**
     * Writes the raw token preamble first, says hello after the login, and takes Companion's messages off the view
     * stream. Netty's thread.
     */
    private final class DataHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            newestChannel = ctx.channel();
            connectionOpen = true;
            listenersTold = false;
            viewArrived = false; // each connection's attach is a fresh one
            // before the login: this handler's channelActive runs before the protocol handler's, which writes it, and
            // writes on the event loop go out in order, so the host's TokenGate reads these 8 bytes first
            ctx.channel().writeAndFlush(TokenGate.preamble(ctx.alloc(), token));
            super.channelActive(ctx); // the protocol handler after this one sends the login
            // read once each: what the copy holds now (anything still being written comes again, and is skipped)
            String game = followed;
            long have = game == null ? ShareMessages.Hello.NOTHING : heldSeq;
            ctx.channel().writeAndFlush(new ShareMessages.Hello(token, game, have));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // an older connection closing late, after a newer one opened, says nothing about the newer one. Not net
            // play's isActiveChannel: connect() sets that channel only after the connect, which a host closing at once
            // (a wrong token) can beat, and that close must count
            if (ctx.channel() != newestChannel) {
                super.channelInactive(ctx);
                return;
            }
            connectionOpen = false;
            // net play's handler, before this one, tells the listeners only when this is its current channel; a host
            // that closes at once (a wrong token) can beat connect() to setting it, and then no one would hear of it
            if (!listenersTold && !left && getReconnectState() == ReconnectState.CONNECTED) {
                netLog.info("[Companion] Connection closed before it was counted: telling the listeners");
                for (ILobbyListener listener : listeners) {
                    listener.close();
                }
            }
            // net play's handler before this one has decided: no reconnect follows, so this join is over
            if (getReconnectState() != ReconnectState.RECONNECTING) {
                holdLocalNetwork(false);
                setLink(ended ? Link.ENDED : Link.CLOSED);
            }
            super.channelInactive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ShareMessages.Welcome welcome) {
                netLog.info("[Companion] {}", welcome);
                Channel channel = ctx.channel();
                if (channel != newestChannel) {
                    // a late welcome on an attempt let go of by a newer one: not this phone's connection any more
                    netLog.info("[Companion] A welcome on a stale connection: closing it");
                    ctx.close();
                    return;
                }
                // the host answered: a reconnect attempt succeeded, though the view comes only at its next prompt
                resumeAnswered();
                setLink(Link.CONNECTED);
                copy(() -> follow(welcome, channel)); // sets welcomedTo once the id is checked and the copy made
                if (!viewArrived) {
                    ctx.executor().schedule(() -> {
                        if (!viewArrived && ctx.channel().isActive()) {
                            netLog.warn("[Companion] The host's game didn't arrive within {} s: closing",
                                    ATTACH_SECONDS);
                            attachTimedOut = true;
                            ctx.close();
                        }
                    }, ATTACH_SECONDS, TimeUnit.SECONDS);
                }
            } else if (msg instanceof ShareMessages.Step step) {
                copy(() -> writeStep(step));
            } else if (msg instanceof ShareMessages.Entries entries) {
                copy(() -> append(entries.items()));
            } else if (msg instanceof ShareMessages.Gap gap) {
                copy(() -> append(List.of(Verbatim.gap(gap.seq()))));
            } else if (msg instanceof ShareMessages.Setup setup) {
                copy(() -> saveSetup(setup.setup()));
            } else if (msg instanceof ShareMessages.End) {
                // nothing follows the end: no reconnect when the host lets go of this phone
                ended = true;
                beginShutdown();
                setLink(Link.ENDED);
                copy(CompanionClient.this::end);
            } else if (msg instanceof ShareMessages.Epoch e) {
                // the host sends it right after a full view, on the same connection: that view was read just before
                // on this thread, and its apply is queued on the GUI thread, which the reset waits behind
                resetTracker(ctx.channel());
                epoch = e.n();
                netLog.info("[Companion] {}", e);
            } else {
                if (msg instanceof GuiGameEvent event && event.getMethod() == ProtocolMethod.openView) {
                    viewArrived = true;
                }
                super.channelRead(ctx, msg);
            }
        }
    }

    /** Queues {@code write} for the copy's thread. Netty's thread; after {@link #close}, nothing more is written. */
    private void copy(Runnable write) {
        try {
            copier.execute(() -> {
                String game = followed;
                try {
                    write.run();
                } catch (RuntimeException e) {
                    netLog.error(e, "[Companion] Can't write the copy of {}", game);
                    for (CopyListener listener : copyListeners) {
                        listener.failed(game, e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            netLog.info("[Companion] Closed: not writing what the host sent");
        }
    }

    /**
     * The welcome: the folder of the host's game, its share state and its setup; the steps it wants, said on
     * {@code channel}; then {@link #welcomedTo}. An id that isn't a {@link CompanionStore#isGameId game id} (it came
     * from the network) is refused before anything is written or remembered, and the copy listeners hear it
     * {@code failed}; no want is sent, so the host lets the phone go. The copy's thread.
     */
    private void follow(ShareMessages.Welcome welcome, Channel channel) {
        String game = welcome.game();
        if (!CompanionStore.isGameId(game)) {
            throw new IllegalArgumentException("The host sent a game id that isn't one: " + LogSafe.forDisplay(
                    String.valueOf(game), 80));
        }
        startCopy(store, game, welcome.setup(), code);
        if (!game.equals(followed)) {
            followAgain(game); // a folder from an earlier join keeps its log: count what it holds
        }
        welcomeSeq = welcome.latestSeq();
        int[] wanted = wantedSteps(store, game, welcome.latestStep(), welcome.stepIndex());
        ShareMessages.Want want = new ShareMessages.Want(wanted);
        netLog.info("[Companion] {} of {}", want, game);
        beforeWant(game);
        channel.writeAndFlush(want);
        for (CopyListener listener : copyListeners) {
            listener.wanted(game, wanted.clone());
        }
        welcomedTo = game;
    }

    /**
     * A test's hook: runs on the copy's thread once the want for {@code game} is known, just before it's sent, so a
     * test can hold it back while the host writes (the host sends nothing of its journal before the wanted steps).
     * Does nothing here.
     */
    protected void beforeWant(String game) { }

    /**
     * The catch-up rule for steps (plan 3.3): drops the steps of {@code game} past the host's latest
     * {@code hostLatest}, and returns the steps of the host's {@code index} that {@code store} lacks or holds with
     * another sha256, lowest first.
     */
    static int[] wantedSteps(CompanionStore store, String game, int hostLatest, Map<Integer, String> index) {
        store.discardAfter(game, hostLatest);
        Map<Integer, String> held = store.stepHashes(game);
        List<Integer> wanted = new ArrayList<>();
        for (Map.Entry<Integer, String> step : new TreeMap<>(index).entrySet()) {
            if (!step.getValue().equals(held.get(step.getKey()))) {
                wanted.add(step.getKey());
            }
        }
        return wanted.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The copy's thread. A step whose text doesn't have its hash isn't written. */
    private void writeStep(ShareMessages.Step step) {
        String game = following();
        if (!step.intact()) {
            throw new IllegalStateException("Step " + step.n() + " of " + game + " came damaged: not written");
        }
        store.writeStep(game, step.n(), step.text());
        for (CopyListener listener : copyListeners) {
            listener.stepWritten(game, step.n());
        }
    }

    /**
     * Makes the folder of the followed game {@code game} in {@code store}: its share state first, then its setup. A
     * folder with a setup and no share state is resumed as the phone's own, which writes to it; cut short in between
     * (a kill, a failed write), this leaves either a folder with no setup, which isn't resumed, or a followed game.
     */
    static void startCopy(CompanionStore store, String game, CompanionSetup setup, JoinCode code) {
        store.createDir(game); // refuses an id that isn't a plain folder name
        ShareState.joined(code).write(store, game);
        store.createWithId(game, setup);
    }

    /**
     * The copy's thread. An {@code UNDO} entry after the welcome's latest seq drops the steps after the one it went
     * back to, before it's appended, as the host did ({@code CompanionTable}: files first, then the log). One up to it
     * doesn't: the welcome's step index already has what it took back, and steps saved since (applying it would
     * delete steps the host still holds).
     */
    private void append(List<Verbatim> items) {
        String game = following();
        int from = 0;
        for (int i = 0; i < items.size(); i++) {
            int undoneTo = undoneTo(items.get(i));
            if (undoneTo != NOT_AN_UNDO) {
                if (i > from) {
                    store.appendVerbatim(game, items.subList(from, i));
                }
                store.discardAfter(game, undoneTo);
                from = i;
            }
        }
        store.appendVerbatim(game, items.subList(from, items.size()));
        long to = items.get(items.size() - 1).seq();
        heldSeq = Math.max(heldSeq, to);
        for (CopyListener listener : copyListeners) {
            listener.written(game, items.get(0).seq(), to);
        }
    }

    private static final int NOT_AN_UNDO = Integer.MIN_VALUE;

    /** The step an {@code UNDO} entry after the welcome went back to, else {@link #NOT_AN_UNDO}. The copy's thread. */
    private int undoneTo(Verbatim item) {
        if (item.seq() <= welcomeSeq) {
            return NOT_AN_UNDO;
        }
        CompanionLogEntry entry;
        try {
            entry = item.read().orElse(null);
        } catch (RuntimeException e) {
            // appended as the host wrote it all the same; an entry that doesn't read takes nothing back
            netLog.warn("[Companion] Entry {} of {} doesn't read: {}", item.seq(), followed, e.toString());
            return NOT_AN_UNDO;
        }
        return entry != null && entry.kind() == CompanionLogEntry.Kind.UNDO ? entry.step() : NOT_AN_UNDO;
    }

    /** The copy's thread. */
    private void saveSetup(CompanionSetup setup) {
        String game = following();
        store.saveSetup(game, setup);
        for (CopyListener listener : copyListeners) {
            listener.setupChanged(game, setup);
        }
    }

    /** The copy's thread. */
    private void end() {
        String game = following();
        store.end(game);
        for (CopyListener listener : copyListeners) {
            listener.ended(game);
        }
    }

    private String following() {
        String game = followed;
        if (game == null) {
            throw new IllegalStateException("Game data before a welcome was written");
        }
        return game;
    }
}
