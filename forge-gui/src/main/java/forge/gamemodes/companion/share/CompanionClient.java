package forge.gamemodes.companion.share;

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
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.channels.SocketChannel;
import java.util.List;
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
 * the host gone after 15 s too. Reconnect is net play's, which says hello again on each new connection (slice 04 makes
 * it unbounded).
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
 * {@link ShareState} ({@code share.json}); then each {@link ShareMessages.Entries} and {@link ShareMessages.Gap} is
 * appended ({@link CompanionStore#appendVerbatim}), each {@link ShareMessages.Setup} saved, and
 * {@link ShareMessages.End} marks the game ended. All of it is written on one thread of its own, in the order it came,
 * and nothing else is done with it: the {@link CopyListener}s hear of it once it's on disk. Each hello says which game
 * the phone holds and the seq of its last entry, so the host sends only what's missing.
 */
public class CompanionClient extends FGameClient {
    static final int HEARTBEAT_SECONDS = 5;
    static final int READER_IDLE_SECONDS = 15;
    /** How long after the welcome the game may take to arrive ({@code openView}) before the client gives up. */
    static final int ATTACH_SECONDS = 30;

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

    /** Net play's connect, on the local network. A reconnect attempt comes through here too, and holds it again. */
    @Override
    public void connect() {
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
        }
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

    /** The shared game's id once the host welcomed this phone, else null. */
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
            }
            super.channelInactive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ShareMessages.Welcome welcome) {
                welcomedTo = welcome.game();
                netLog.info("[Companion] {}", welcome);
                copy(() -> follow(welcome));
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
            } else if (msg instanceof ShareMessages.Entries entries) {
                copy(() -> append(entries.items()));
            } else if (msg instanceof ShareMessages.Gap gap) {
                copy(() -> append(List.of(Verbatim.gap(gap.seq()))));
            } else if (msg instanceof ShareMessages.Setup setup) {
                copy(() -> saveSetup(setup.setup()));
            } else if (msg instanceof ShareMessages.End) {
                copy(CompanionClient.this::end);
            } else if (msg instanceof ShareMessages.Epoch e) {
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

    /** The welcome: the folder of the host's game, its setup and its share state. The copy's thread. */
    private void follow(ShareMessages.Welcome welcome) {
        String game = welcome.game();
        store.createWithId(game, welcome.setup()); // refuses an id that isn't a plain folder name
        ShareState.joined(code).write(store, game);
        if (!game.equals(followed)) {
            followAgain(game); // a folder from an earlier join keeps its log: count what it holds
        }
    }

    /** The copy's thread. */
    private void append(List<Verbatim> items) {
        String game = following();
        store.appendVerbatim(game, items);
        long to = items.get(items.size() - 1).seq();
        heldSeq = Math.max(heldSeq, to);
        for (CopyListener listener : copyListeners) {
            listener.written(game, items.get(0).seq(), to);
        }
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
