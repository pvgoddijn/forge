package forge.gamemodes.net.client;

import com.google.common.collect.Lists;
import forge.game.player.PlayerView;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.NetworkLogConfig;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.event.*;
import forge.gui.interfaces.IDraftEventHandler;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.ILobbyListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class FGameClient implements IToServer, IHasForgeLog {

    public enum ReconnectState { CONNECTED, RECONNECTING, FAILED, SEAT_LOST }

    public enum DisconnectMode {
        /**
         * Simulates a hung client or a client-to-server network break. The client
         * stops sending heartbeats; the server's READER_IDLE fires, the server closes
         * the channel, and the client picks up the FIN via channelInactive and
         * reconnects. Reconnect is triggered by the channel event.
         */
        OUTBOUND,
        /**
         * Simulates a hung server or a server-to-client network break. The client
         * keeps sending heartbeats (so the server doesn't close), but nothing is
         * arriving back. The client's own READER_IDLE eventually fires and reconnect
         * is triggered by that timeout.
         */
        INBOUND
    }

    static final int HEARTBEAT_INTERVAL_SECONDS = Integer.getInteger("forge.net.heartbeatInterval", 15);
    static final int READER_IDLE_SECONDS = 45;
    private static final int[] BACKOFF_SECONDS = {1, 5, 15, 45, 60};
    private static final int RESUME_WATCH_SECONDS = 5;
    /** Netty's own default: how long one connect may take before it fails. */
    static final int CONNECT_TIMEOUT_MILLIS = 30_000;
    /** What {@link #reconnectDelayMillis} returns to give up. */
    protected static final long GIVE_UP = -1;
    private final IGuiGame clientGui;
    private final String hostname;
    private final Integer port;
    private final String username;
    private final List<ILobbyListener> lobbyListeners = Lists.newArrayList();
    private IDraftEventHandler draftHandler;
    private final ReplyPool replies = new ReplyPool();
    private volatile boolean disconnectSimulated;
    private volatile Channel channel;
    // A server refuses a login by closing the connection, so only a client that held a seat reconnects
    private volatile boolean seated;
    /** Whether a game view came on one of this client's own connections (see {@link #reusesShownTracker}). */
    private volatile boolean ownViewArrived;

    private ReconnectState reconnectState = ReconnectState.CONNECTED;
    private boolean shuttingDown = false;
    private final Object reconnectLock = new Object();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Forge-Reconnect");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> resumeWatch;
    private volatile ScheduledFuture<?> pendingAttempt;

    public FGameClient(String username, IGuiGame clientGui, String hostname, int port) {
        this.username = username;
        this.clientGui = clientGui;
        this.hostname = hostname;
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    final IGuiGame getGui() {
        return clientGui;
    }
    final ReplyPool getReplyPool() {
        return replies;
    }

    public void connect() {
        final EventLoopGroup group = new NioEventLoopGroup();
        boolean attached = false;
        try {
            final Bootstrap b = new Bootstrap()
             .group(group)
             .channelFactory(channelFactory())
             .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis())
             .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(final SocketChannel ch) throws Exception {
                    final ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(
                            new LoggingHandler(LogLevel.INFO),
                            new CompatibleObjectEncoder(null), // Client doesn't need byte tracking
                            new CompatibleObjectDecoder(9766*1024, ClassResolvers.cacheDisabled(null)),
                            new IdleStateHandler(readerIdleSeconds(), heartbeatIntervalSeconds(), 0, TimeUnit.SECONDS),
                            new MessageHandler(),
                            new LobbyUpdateHandler());
                    final ChannelHandler extra = newExtraInboundHandler();
                    if (extra != null) {
                        pipeline.addLast(extra);
                    }
                    pipeline.addLast(new GameClientHandler(FGameClient.this));
                }
             });

            // Start the connection attempt.
            final Channel newChannel = b.connect(this.hostname, this.port).sync().channel();
            synchronized (reconnectLock) {
                if (shuttingDown) {
                    newChannel.close();
                    return;
                }
                channel = newChannel;
            }
            final ChannelFuture ch = newChannel.closeFuture();
            attached = true;
            // A reconnect on a brand-new channel can't inherit the prior pipeline's
            // simulated-disconnect blockers, so clear the gate that would otherwise
            // keep dropping outbound writes after /simulatedisconnect.
            disconnectSimulated = false;
            new Thread(() -> {
                try {
                    ch.sync();
                } catch (final InterruptedException e) {
                    netLog.error(e, "Client channel interrupted");
                } finally {
                    group.shutdownGracefully();
                }
            }).start();
        } catch (final InterruptedException e) {
            netLog.error(e, "Client connect interrupted");
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            if (!attached) {
                group.shutdownGracefully();
            }
        }
    }

    /**
     * Makes each connection's channel, a reconnect's included: a mode can hand Netty a socket of its own (Companion's
     * join, bound to the Wi-Fi network). Called on the connecting thread. A plain {@link NioSocketChannel} by default.
     */
    protected ChannelFactory<? extends Channel> channelFactory() {
        return NioSocketChannel::new;
    }

    /**
     * An extra inbound handler, put just before the game protocol handler in each new connection's pipeline (so once
     * per connection, a reconnect's included), for a mode's own messages beside the view stream. Its
     * {@code channelActive} runs before the protocol handler sends the login. None by default.
     */
    protected ChannelHandler newExtraInboundHandler() {
        return null;
    }

    /**
     * Drops the stale card views of the connection {@code channel}'s tracker, once the full state just read from it is
     * applied (see {@code GameClientHandler.resetTracker}): for a mode that knows when the host renumbered the board.
     * On the channel's thread, right after that full state was read.
     */
    protected final void resetTracker(final Channel channel) {
        final GameClientHandler handler = channel.pipeline().get(GameClientHandler.class);
        if (handler != null) {
            handler.resetTracker();
        }
    }

    /** How long the client waits for anything from the server before it counts the connection as lost. */
    protected int readerIdleSeconds() {
        return READER_IDLE_SECONDS;
    }

    /** How long the client may stay silent before it sends a heartbeat. */
    protected int heartbeatIntervalSeconds() {
        return HEARTBEAT_INTERVAL_SECONDS;
    }

    /** How long one connect, a reconnect attempt's included, may take before it fails. Netty's default, 30 s. */
    protected int connectTimeoutMillis() {
        return CONNECT_TIMEOUT_MILLIS;
    }

    /**
     * The reconnect backoff: how long to wait before the reconnect attempt {@code attemptIndex} (0 for the first), in
     * milliseconds, or {@link #GIVE_UP} to stop trying, which fails the reconnect. Net play waits 1, 5, 15, 45 and 60 s,
     * then gives up; a mode may never give up.
     */
    protected long reconnectDelayMillis(final int attemptIndex) {
        return attemptIndex < BACKOFF_SECONDS.length ? TimeUnit.SECONDS.toMillis(BACKOFF_SECONDS[attemptIndex]) : GIVE_UP;
    }

    /**
     * Whether a new connection's first game view goes into the tracker of the view still shown, rather than a new
     * tracker: for a mode whose server resends the whole board on each connection and that drops stale card views
     * itself once it's applied ({@link #resetTracker}), so the board's card views stay in one tracker across a
     * reconnect. False by default: net play keys a new tracker on each connection's first game view.
     */
    protected boolean keepsTrackerAcrossConnections() {
        return false;
    }

    /**
     * Whether a new connection's first game view goes into the tracker of the view still shown: only for a mode that
     * {@link #keepsTrackerAcrossConnections keeps it}, and only once this client's own first view arrived. The view
     * still shown before that isn't this client's: the GUI may be reused from an earlier match (mobile's
     * {@code MatchController} is one for the app), so the first connection always starts with a tracker of its own.
     */
    final boolean reusesShownTracker() {
        return ownViewArrived && keepsTrackerAcrossConnections();
    }

    /** A game view came on one of this client's connections, and is in the tracker its handler chose. IO thread. */
    final void ownViewArrived() {
        ownViewArrived = true;
    }

    /**
     * Counts this client as holding a seat, so a lost connection is reconnected, before any {@code openView} or lobby
     * update said so: for a mode whose server admits it in its own way, or that resumes a game it held before.
     */
    protected final void markSeated() {
        seated = true;
    }

    /**
     * Starts the reconnect loop now, as a lost connection would, for a seated client ({@link #markSeated}) that isn't
     * reconnecting yet: such as one whose first connect failed. Does nothing otherwise.
     */
    protected final void startReconnecting() {
        onDisconnected();
    }

    /**
     * Counts what a mode's server just sent on the newest connection as the answer to a reconnect attempt, as a
     * resumed game view is for net play: the attempt succeeded. Does nothing when not reconnecting.
     */
    protected final void resumeAnswered() {
        onResumeResponseReceived();
    }

    public void close() {
        beginShutdown();
        if (channel != null)
            channel.close();
        reconnectScheduler.shutdownNow();
        NetworkLogConfig.deactivateNetworkLogging();
    }

    @Override
    public void send(final NetEvent event) {
        trySend(event);
    }

    /**
     * Returns false when the event was dropped rather than written. The channel
     * is null or inactive while a reconnect is in progress (and after FAILED /
     * SEAT_LOST), so UI actions taken during that window are discarded; the
     * server replays the current Input prompt on resume.
     */
    private boolean trySend(final NetEvent event) {
        if (disconnectSimulated) {
            return false;
        }
        // Read the volatile once: the reconnect thread can null it between uses
        final Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            netLog.warn("Client not connected (state={}), dropped {}", getReconnectState(), event);
            return false;
        }
        netLog.info("Client sent {}", event);
        final CompatibleObjectEncoder encoder = ch.pipeline().get(CompatibleObjectEncoder.class);
        if (encoder == null) {
            netLog.error("No encoder in client pipeline for {}", event);
            return false;
        }
        final ByteBuf encoded;
        try {
            encoded = encoder.encodeToBuf(event, ch.alloc());
        } catch (Exception e) {
            netLog.error(e, "Client encode error for {}", event);
            return false;
        }
        ch.writeAndFlush(encoded);
        return true;
    }

    /**
     * Test-only hook that silently drops traffic in one direction while keeping the
     * TCP connection open. Used by the {@code /simulatedisconnect} chat command to
     * exercise the two reconnect-detection paths. See {@link DisconnectMode}.
     */
    public void simulateDisconnect(final DisconnectMode mode) {
        netLog.info("[simulateDisconnect] mode={} - suspending network traffic", mode);
        // Pipeline modifications run on the event loop thread for atomicity
        channel.eventLoop().execute(() -> {
            if (mode == DisconnectMode.OUTBOUND) {
                // Gate user-initiated sends so they don't race past the pipeline writeBlocker
                disconnectSimulated = true;
                // Disable our own READER_IDLE so only the server's timer fires
                channel.pipeline().remove(IdleStateHandler.class);
                channel.pipeline().addFirst("writeBlocker", new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        netLog.info("[writeBlocker] Dropped: {}", msg.getClass().getSimpleName());
                        promise.setSuccess();
                    }
                });
            } else {
                // INBOUND: drop reads, keep writes so heartbeats still flow to the server
                channel.pipeline().addFirst("readBlocker", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        // Drop inbound messages; IdleStateEvents still propagate via userEventTriggered
                        // below so the client's READER_IDLE can still fire
                    }

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        if (evt instanceof IdleStateEvent) {
                            ctx.fireUserEventTriggered(evt);
                        }
                    }
                });
            }
            netLog.info("[simulateDisconnect] Pipeline modified (mode={})", mode);
        });
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) {
        replies.initialize(event.getId());

        if (!trySend(event)) {
            // No reply will ever arrive; unblock the caller instead of hanging it
            replies.complete(event.getId(), null);
        }

        // Wait for reply
        return replies.get(event.getId());
    }

    List<ILobbyListener> getLobbyListeners() {
        return lobbyListeners;
    }

    public void addLobbyListener(final ILobbyListener listener) {
        lobbyListeners.add(listener);
    }

    public void setDraftHandler(final IDraftEventHandler handler) {
        this.draftHandler = handler;
    }

    void setGameControllers(final Iterable<PlayerView> myPlayers) {
        // A client that takes back a parked seat gets its match without a lobby update
        seated = true;
        for (final PlayerView p : myPlayers) {
            NetGameController controller = new NetGameController(this);
            clientGui.setOriginalGameController(p, controller);
        }
    }

    public ReconnectState getReconnectState() {
        synchronized (reconnectLock) {
            return reconnectState;
        }
    }

    public static int getTotalReconnectAttempts() {
        return BACKOFF_SECONDS.length;
    }

    /**
     * True when {@code c} is the channel currently considered live by this
     * client. Used by handlers to filter out late callbacks arriving on a
     * channel that's been closed and replaced during reconnect — without this
     * gate, a buffered {@code setGameView} on the dying channel would flip
     * state to {@code CONNECTED} even though no real reconnect has happened.
     */
    boolean isActiveChannel(final Channel c) {
        return channel != null && channel == c;
    }

    /**
     * Entry point from {@link LobbyUpdateHandler#channelInactive}. Short-circuits
     * during deliberate shutdown so the intentional close doesn't kick off a
     * retry loop. Proactively closes the old channel so a FIN reaches the server
     * ASAP — this collapses the window where the server's view of the session
     * lags behind the client's, preventing the reconnect LoginEvent from being
     * rejected as a duplicate login.
     */
    void onDisconnected() {
        synchronized (reconnectLock) {
            if (shuttingDown || reconnectState != ReconnectState.CONNECTED || !seated) {
                return;
            }
            reconnectState = ReconnectState.RECONNECTING;
        }
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        channel = null;
        notifyReconnectState(ReconnectState.RECONNECTING, 0, delaySeconds(0));
        scheduleAttempt(0);
    }

    /** The wait before attempt {@code attemptIndex} in whole seconds, rounded up, for the banner; 0 when giving up. */
    private int delaySeconds(final int attemptIndex) {
        final long millis = reconnectDelayMillis(attemptIndex);
        return millis < 0 ? 0 : (int) ((millis + 999) / 1000);
    }

    public void beginShutdown() {
        synchronized (reconnectLock) {
            shuttingDown = true;
        }
    }

    private void scheduleAttempt(final int attemptIndex) {
        final long delay = reconnectDelayMillis(attemptIndex);
        if (delay < 0) {
            transitionToFailed();
            return;
        }
        pendingAttempt = reconnectScheduler.schedule(() -> attemptReconnect(attemptIndex), delay,
                TimeUnit.MILLISECONDS);
    }

    private void attemptReconnect(final int attemptIndex) {
        synchronized (reconnectLock) {
            if (shuttingDown || reconnectState != ReconnectState.RECONNECTING) {
                return;
            }
        }
        netLog.info("[Reconnect] Attempt {} - connecting to {}:{}", attemptIndex + 1, hostname, port);
        try {
            connect();
            awaitResumeOrLobby(attemptIndex);
        } catch (Exception e) {
            netLog.info("[Reconnect] Attempt {} failed: {}", attemptIndex + 1, e.getMessage());
            continueOrFail(attemptIndex);
        }
    }

    private void awaitResumeOrLobby(final int attemptIndex) {
        cancelResumeWatch();
        resumeWatch = reconnectScheduler.schedule(() -> {
            netLog.info("[Reconnect] Attempt {} - no response in {}s",
                    attemptIndex + 1, RESUME_WATCH_SECONDS);
            continueOrFail(attemptIndex);
        }, RESUME_WATCH_SECONDS, TimeUnit.SECONDS);
    }

    private void cancelResumeWatch() {
        if (resumeWatch != null) {
            resumeWatch.cancel(false);
            resumeWatch = null;
        }
    }

    private void continueOrFail(final int attemptIndex) {
        synchronized (reconnectLock) {
            if (shuttingDown || reconnectState != ReconnectState.RECONNECTING) return;
        }
        final int next = attemptIndex + 1;
        if (reconnectDelayMillis(next) < 0) {
            transitionToFailed();
            return;
        }
        notifyReconnectState(ReconnectState.RECONNECTING, next, delaySeconds(next));
        scheduleAttempt(next);
    }

    private void transitionToFailed() {
        synchronized (reconnectLock) {
            if (reconnectState != ReconnectState.RECONNECTING) return;
            reconnectState = ReconnectState.FAILED;
        }
        notifyReconnectState(ReconnectState.FAILED, -1, 0);
    }

    void onResumeResponseReceived() {
        synchronized (reconnectLock) {
            if (shuttingDown || reconnectState != ReconnectState.RECONNECTING) return;
            reconnectState = ReconnectState.CONNECTED;
        }
        cancelResumeWatch();
        notifyReconnectState(ReconnectState.CONNECTED, 0, 0);
    }

    void onSeatLost() {
        synchronized (reconnectLock) {
            if (shuttingDown || reconnectState != ReconnectState.RECONNECTING) return;
            reconnectState = ReconnectState.SEAT_LOST;
        }
        cancelResumeWatch();
        notifyReconnectState(ReconnectState.SEAT_LOST, -1, 0);
    }

    /**
     * User clicked "Cancel" on the reconnect banner. Stops the retry loop and
     * any pending resume-watch, and marks the session as shutting down so a
     * subsequent {@code channelInactive} doesn't restart the loop. The caller
     * is responsible for UI teardown (hiding the banner, navigating away).
     */
    public void cancelReconnect() {
        synchronized (reconnectLock) {
            if (reconnectState != ReconnectState.RECONNECTING) return;
            shuttingDown = true;
        }
        cancelResumeWatch();
        final ScheduledFuture<?> pending = pendingAttempt;
        if (pending != null) {
            pending.cancel(false);
            pendingAttempt = null;
        }
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        channel = null;
        netLog.info("[Reconnect] User cancelled - retry loop stopped.");
    }

    /**
     * Triggered by the "Attempt Rejoin" button on the FAILED modal. Starts the
     * full backoff schedule from attempt 0 — same behaviour as the automatic
     * retry loop that kicks in on channelInactive.
     */
    public void rejoinManually() {
        synchronized (reconnectLock) {
            if (reconnectState != ReconnectState.FAILED) return;
            reconnectState = ReconnectState.RECONNECTING;
            shuttingDown = false;
        }
        notifyReconnectState(ReconnectState.RECONNECTING, 0, 0);
        reconnectScheduler.execute(() -> attemptReconnect(0));
    }

    /** Called on each reconnect state change, on the thread that made it, before the GUI hears of it. None by default. */
    protected void onReconnectStateChanged(final ReconnectState state) {
    }

    /**
     * State-change hook for UI. Dispatches to {@link forge.gamemodes.net.NetworkGuiGame}
     * when the GUI supports it; always logs.
     */
    private void notifyReconnectState(final ReconnectState state, final int attemptIndex, final int nextDelaySeconds) {
        netLog.info("[Reconnect] state={} attempt={} nextDelay={}s", state, attemptIndex, nextDelaySeconds);
        onReconnectStateChanged(state);
        if (clientGui instanceof forge.gamemodes.net.NetworkGuiGame networkGui) {
            networkGui.onReconnectStateChanged(state, attemptIndex, nextDelaySeconds);
        }
    }

    private class MessageHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof HeartbeatEvent) {
                // Echo from server; its only purpose is to keep our READER_IDLE timer from firing
                return;
            }
            if (msg instanceof MessageEvent event) {
                for (final ILobbyListener listener : lobbyListeners) {
                    listener.message(event.getSource(), event.getMessage(), event.getType());
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    private class LobbyUpdateHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof SeatLostEvent) {
                if (isActiveChannel(ctx.channel())) {
                    onSeatLost();
                }
                return;
            }
            if (msg instanceof LobbyUpdateEvent event) {
                // Suppress lobby UI updates while reconnecting so the lobby screen doesn't flash
                // back over the in-game UI; the resume payload (setGameView) is what tells us
                // we're back in the match. Seat-lost is signaled explicitly via SeatLostEvent.
                if (isActiveChannel(ctx.channel()) && getReconnectState() == ReconnectState.RECONNECTING) {
                    return;
                }
                if (event.getSlot() >= 0) {
                    seated = true;
                }
                for (final ILobbyListener listener : lobbyListeners) {
                    listener.update(event.getState(), event.getSlot());
                }
            } else if (msg instanceof NetEvent netEvent && draftHandler != null
                    && draftHandler.dispatch(netEvent)) {
                return;
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            if (evt instanceof IdleStateEvent ise) {
                if (ise.state() == IdleState.WRITER_IDLE) {
                    ctx.writeAndFlush(new HeartbeatEvent());
                } else if (ise.state() == IdleState.READER_IDLE) {
                    ctx.close();
                }
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            netLog.info("[Disconnect] Channel became inactive, notifying {} listeners", lobbyListeners.size());
            netLog.info("[Disconnect] Remote address was: {}", ctx.channel().remoteAddress());
            if (isActiveChannel(ctx.channel())) {
                onDisconnected();
                final boolean notifyListeners;
                synchronized (reconnectLock) {
                    notifyListeners = !shuttingDown && reconnectState != ReconnectState.RECONNECTING;
                }
                if (notifyListeners) {
                    for (final ILobbyListener listener : lobbyListeners) {
                        listener.close();
                    }
                }
            }
            super.channelInactive(ctx);
        }
    }
}
