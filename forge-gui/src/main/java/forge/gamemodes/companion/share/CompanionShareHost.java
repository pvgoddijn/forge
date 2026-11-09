package forge.gamemodes.companion.share;

import forge.game.Game;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.GameProtocolHandler;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.NetworkByteTracker;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.HeartbeatEvent;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.ReplyEvent;
import forge.gamemodes.net.server.RemoteClient;
import forge.interfaces.IGameController;
import forge.util.IHasForgeLog;
import forge.util.LogSafe;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Shares one Companion table with phones on the local network (plan decision 2): a small server of its own, built from
 * net play's public pieces (the wire codec, heartbeat, {@link RemoteClient}, the proxy GUI, the protocol handler). It
 * never touches {@code FServerManager} or {@code HostingServer}, so no other mode sees it, and nothing it does changes
 * the table when no phone is connected (AC-114).
 *
 * <p>Admission: a raw preamble with this share's token, checked by {@link TokenGate} before anything is deserialized,
 * then net play's {@code LoginEvent}, then {@link ShareMessages.Hello} with the token again, answered by
 * {@link ShareMessages.Welcome}, then the phone is attached as a {@link CompanionPeer}. A wrong token, or anything else
 * first, closes the connection. Every phone is a read-only spectator in M1 (AC-108). Netty's threads never touch the
 * game or the UI: the attach and detach go to the game loop through {@link CompanionTable#runSystem}.
 *
 * <p>The data stream (plan 3.3): while shared, the table's store feeds a {@link ShareJournal}. A phone admitted
 * {@link ShareJournal#subscribe subscribes} with the {@code have} of its hello and gets the welcome (setup, latest seq,
 * latest step and step index). It answers {@link ShareMessages.Want} with the steps it lacks, and gets, in this order,
 * those steps, its catch-up of entries, then the attach, and then each item the store writes, live, in write order.
 * Steps first, so a phone cut off part way never holds entries that point at a step it doesn't have. A phone that
 * doesn't answer within {@link #WANT_SECONDS} is let go. Each phone's data is sent from its connection's event loop:
 * the store's wakeup only schedules a drain there, and a drain sends nothing before the wanted steps went, so the game
 * thread never waits for a phone and the order holds.
 *
 * <p>Lasting (slice 04): the share's port and token are kept in the game's {@link ShareState} ({@code share.json},
 * {@link ShareState.Role#HOSTING}), written when sharing starts, so a host restarted on the same game shares again on
 * the same code, and its phones come back by themselves; when that port is taken it takes the next free one, which
 * changes the code ({@link #codeChanged}). A phone whose connection drops is let go of at once: a view-only phone has
 * nothing to resume, its reconnect is a fresh join. Each new game the table starts (a restart from the match screen)
 * gets every attached phone a fresh attach. A phone joining an ended game gets the end and is let go, for as long as
 * the share runs.
 */
public final class CompanionShareHost implements IHasForgeLog {
    /** The Companion port, one above net play's 36743; the next free one up to {@link #LAST_PORT} if it's taken. */
    public static final int FIRST_PORT = 36744;
    public static final int LAST_PORT = 36753;
    /** A phone that sends nothing for this long is gone (its heartbeat is every 15 s; plan 3.1). */
    static final int READER_IDLE_SECONDS = 15;
    /** A connection that hasn't been admitted by then is closed. */
    static final int ADMISSION_SECONDS = 15;
    /**
     * A phone welcomed that hasn't said which steps it wants by then is closed. As long as the admission: well within
     * the phone's own wait for the game after the welcome ({@code CompanionClient.ATTACH_SECONDS}).
     */
    static final int WANT_SECONDS = ADMISSION_SECONDS;
    private static final int MAX_FRAME_BYTES = 9766 * 1024; // as net play's
    /** How long {@link #stop} waits for what the phones are still owed (such as the end) to be sent. */
    static final int STOP_FLUSH_MILLIS = 1000;

    /** A code to show, with the interface its address is on. */
    public record Code(String interfaceName, JoinCode code) { }

    private final CompanionTable table;
    private final int token;
    /** The share state of an earlier share of this game, as found when this share was made. */
    private final Optional<ShareState> earlier;
    /** Gives the attached phones a fresh attach to each new game the table starts. */
    private final Consumer<Game> onGameStart = this::gameStarted;
    private final Set<CompanionPeer> peers = ConcurrentHashMap.newKeySet();
    /** The admitted connections, each with its place in the journal; until their connection closes. */
    private final Set<Admission> admitted = ConcurrentHashMap.newKeySet();
    private final AtomicInteger refusedAtGate = new AtomicInteger();
    /** The measurement's logging (slice 01); null, and nothing measured, when it's off. */
    private final ShareMeter meter = ShareMeter.enabled() ? new ShareMeter() : null;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int port;
    /** The table's journal while shared, else null. */
    private volatile ShareJournal journal;

    /**
     * Shares {@code table} with the token of its earlier share, from its {@code share.json}, so the code stays the
     * same; with a fresh random one when it was never shared (or that file doesn't read).
     */
    public CompanionShareHost(CompanionTable table) {
        this(table, ShareState.hosted(table.store(), table.id()));
    }

    /**
     * Known limit: the token is 32 bits and lasts for the game (kept in {@code share.json} across restarts), and the
     * {@link TokenGate} refuses wrong ones without limit, so a device on the local network could try tokens for as
     * long as the share runs. A per-address backoff of refused connections is for later (slice 07, or M-later).
     */
    private CompanionShareHost(CompanionTable table, Optional<ShareState> earlier) {
        this(table, earlier.map(ShareState::token).orElseGet(() -> new SecureRandom().nextInt()), earlier);
    }

    CompanionShareHost(CompanionTable table, int token) {
        this(table, token, Optional.empty());
    }

    private CompanionShareHost(CompanionTable table, int token, Optional<ShareState> earlier) {
        this.table = table;
        this.token = token;
        this.earlier = earlier;
    }

    /**
     * Listens on every interface, on the earlier share's port when it's free, else on the first free port from
     * {@link #FIRST_PORT} to {@link #LAST_PORT}, and returns it; then writes the game's share state. Throws
     * {@link IllegalStateException} when none is free. Starting a started share returns its port.
     */
    public synchronized int start() {
        return start(FIRST_PORT, LAST_PORT);
    }

    synchronized int start(int first, int last) {
        if (serverChannel != null) {
            return port;
        }
        List<Integer> ports = new ArrayList<>();
        earlier.ifPresent(state -> ports.add(state.port())); // the same code, if it's free
        for (int p = first; p <= last; p++) {
            if (!ports.contains(p)) {
                ports.add(p);
            }
        }
        // before listening, so every phone finds it; with no phone it only fills memory (AC-114)
        journal = ShareJournal.install(table.store(), table.id());
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // a restarted share binds its earlier port again though the last share's connections may still be in
                // TIME_WAIT, which blocks a listener's bind on Linux and Android without it. Not needed on Windows,
                // where TIME_WAIT doesn't block a listener's bind (the JDK keeps its exclusive bind there). A port
                // another program listens on stays taken either way (codeChanged)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        NetworkByteTracker bytes = meter != null ? new NetworkByteTracker() : null;
                        Admission admission = new Admission(bytes);
                        ch.pipeline().addLast(
                                // first, so nothing is deserialized before the token is right
                                new TokenGate(t -> t == token, refusedAtGate::incrementAndGet, ADMISSION_SECONDS),
                                new CompatibleObjectEncoder(bytes),
                                new CompatibleObjectDecoder(MAX_FRAME_BYTES, ClassResolvers.cacheDisabled(null)),
                                new IdleStateHandler(READER_IDLE_SECONDS, 0, 0, TimeUnit.SECONDS),
                                admission,
                                new PeerHandler(admission));
                    }
                });
        for (int p : ports) {
            ChannelFuture bound = bootstrap.bind(p).awaitUninterruptibly();
            if (bound.isSuccess()) {
                serverChannel = bound.channel();
                port = p;
                netLog.info("[Companion] Sharing {} on port {}{}", table.id(), p,
                        codeChanged() ? " (the earlier port " + earlier.get().port() + " is taken: a new code)" : "");
                table.addGameStartListener(onGameStart);
                writeShareState();
                return p;
            }
            netLog.info("[Companion] Port {} is taken: {}", p, String.valueOf(bound.cause()));
        }
        stop();
        throw new IllegalStateException("No free port from " + first + " to " + last + " to share the game on");
    }

    /**
     * Stops listening and lets every phone go. What the journal still holds for a phone, such as the end of the game
     * just written, is sent first, and its connection closed once that's out, waiting at most
     * {@link #STOP_FLUSH_MILLIS} for all of them. Stopping a stopped share does nothing. Any thread but the share's
     * own event loops; it blocks the caller for up to {@link #STOP_FLUSH_MILLIS}, and that includes the UI thread when
     * it's called from there (the stop dialog, or {@code matchLeft} when the match is left).
     */
    public synchronized void stop() {
        table.removeGameStartListener(onGameStart);
        if (journal != null) {
            table.store().setJournal(null); // the store writes as unshared again
            journal = null;
        }
        List<ChannelFuture> closing = new ArrayList<>();
        for (Admission connection : admitted) {
            closing.add(connection.finish());
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_FLUSH_MILLIS);
        for (ChannelFuture closed : closing) {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !closed.awaitUninterruptibly(left, TimeUnit.NANOSECONDS)) {
                netLog.warn("[Companion] A phone's last data wasn't sent within {} ms: closing anyway",
                        STOP_FLUSH_MILLIS);
                break;
            }
        }
        for (CompanionPeer peer : peers) {
            peer.close();
        }
        peers.clear();
        if (meter != null) {
            meter.write();
        }
        if (serverChannel != null) {
            // waited for, so a share started again right after (a restart) can bind the same port
            serverChannel.close().awaitUninterruptibly(STOP_FLUSH_MILLIS);
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            bossGroup = null;
            workerGroup = null;
        }
    }

    public synchronized boolean isStarted() {
        return serverChannel != null;
    }

    /**
     * Stops sharing for good: {@link #stop}, and the game's share state goes, so resuming the game doesn't share it
     * again. Its phones keep their copies and keep trying until they stop following.
     */
    public synchronized void stopForGood() {
        stop();
        try {
            ShareState.delete(table.store(), table.id());
        } catch (UncheckedIOException e) {
            netLog.warn("[Companion] Can't delete the share state of {}: {}", table.id(), e.toString());
        }
    }

    /**
     * Whether this game was shared before on another port than this share's, which was taken: the code changed, and
     * phones that followed it must be given the new one. Once started.
     */
    public synchronized boolean codeChanged() {
        return serverChannel != null && earlier.isPresent() && earlier.get().port() != port;
    }

    /** A new game started on the table: each attached phone gets it afresh at its first prompt. Its loop thread. */
    private void gameStarted(Game game) {
        netLog.info("[Companion] A new game of {} started: attaching {} phones afresh", table.id(), peers.size());
        for (CompanionPeer peer : peers) {
            table.runSystem(peer::attachToNewGame);
        }
    }

    /** Writes the game's share state: hosted, on this port and token, at this device's addresses now. */
    private void writeShareState() {
        List<Inet4Address> addresses = new ArrayList<>();
        for (LocalAddresses.Usable usable : LocalAddresses.find()) {
            addresses.add(usable.address());
        }
        try {
            ShareState.hosting(port, token, addresses).write(table.store(), table.id());
        } catch (UncheckedIOException e) {
            // the share works; only a restart can't share again on the same code
            netLog.warn("[Companion] Can't write the share state of {}: {}", table.id(), e.toString());
        }
    }

    /** The port shared on, once started. */
    public synchronized int port() {
        return port;
    }

    /** One code per usable local address (none when this device is on no local network). Once started. */
    public List<Code> codes() {
        int p = port();
        List<Code> codes = new ArrayList<>();
        for (LocalAddresses.Usable usable : LocalAddresses.find()) {
            codes.add(new Code(usable.interfaceName(), new JoinCode(usable.address(), p, token)));
        }
        return codes;
    }

    /** The code to join this share at {@code address}, such as loopback for a second instance on this PC. Once started. */
    public JoinCode codeAt(Inet4Address address) {
        return new JoinCode(address, port(), token);
    }

    /** The phones attached or attaching. */
    public int peerCount() {
        return peers.size();
    }

    /** The connections closed by the token gate, before anything they sent was deserialized. */
    public int refusedAtGate() {
        return refusedAtGate.get();
    }

    int token() {
        return token;
    }

    /**
     * One connection's admission, before the view: answers heartbeats, takes the login and the hello, and passes the
     * phone's calls on to {@link PeerHandler} once it's admitted. Netty's thread; touches no game state.
     */
    private final class Admission extends ChannelInboundHandlerAdapter {
        /** What this connection's encoder wrote, for the measurement; null when it's off. */
        private final NetworkByteTracker bytes;
        private RemoteClient client;
        private boolean loggedIn;
        private volatile CompanionPeer peer;
        /** This phone's place in the journal, once admitted; closed when the connection closes. */
        private volatile ShareJournal.Subscription data;
        private volatile ChannelHandlerContext context;
        /** From the welcome to the phone's want. The event loop only. */
        private boolean awaitingWant;
        /** Whether the wanted steps went: nothing of the journal is drained before. The event loop only. */
        private boolean stepsSent;

        Admission(NetworkByteTracker bytes) {
            this.bytes = bytes;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            client = new RemoteClient(ctx.channel());
            ctx.executor().schedule(() -> {
                if (peer == null && ctx.channel().isActive()) {
                    netLog.warn("[Companion] Closing {}: not admitted within {} s", ctx.channel().remoteAddress(),
                            ADMISSION_SECONDS);
                    ctx.close();
                }
            }, ADMISSION_SECONDS, TimeUnit.SECONDS);
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HeartbeatEvent) {
                ctx.writeAndFlush(new HeartbeatEvent());
            } else if (msg instanceof LoginEvent login) {
                if (loggedIn) {
                    return;
                }
                client.setUsername(LogSafe.forDisplay(login.getUsername(), 64));
                client.setLibgdx(login.isLibgdx());
                loggedIn = true;
            } else if (msg instanceof ShareMessages.Hello hello) {
                admit(ctx, hello);
            } else if (msg instanceof ShareMessages.Want want) {
                sendWanted(ctx, want);
            } else if (peer != null && (msg instanceof GuiGameEvent || msg instanceof ReplyEvent)) {
                super.channelRead(ctx, msg); // the phone's screen calls and replies
            } else {
                netLog.info("[Companion] Dropping {} from {}", msg, ctx.channel().remoteAddress());
            }
        }

        private void admit(ChannelHandlerContext ctx, ShareMessages.Hello hello) {
            if (!loggedIn || peer != null) {
                netLog.warn("[Companion] Closing {}: hello out of order", ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
            if (hello.token() != token) {
                // no refusal message before slice 07: the phone sees the connection close
                netLog.warn("[Companion] Closing {}: wrong share token", ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
            ShareJournal shared = journal;
            if (shared == null) {
                netLog.warn("[Companion] Closing {}: the share has stopped", ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
            CompanionPeer joined = new CompanionPeer(table, client, ctx::close, meter, bytes);
            peer = joined;
            peers.add(joined);
            // what the phone holds counts only for this game: a phone that followed another starts from nothing
            long have = table.id().equals(hello.game()) ? hello.have() : ShareMessages.Hello.NOTHING;
            // the wakeup runs on the writing thread under the store's lock: it only schedules a drain on this
            // connection's event loop, which sends everything for this phone, so in order
            ShareJournal.Subscription subscription = shared.subscribe(have, () -> scheduleDrain(ctx));
            data = subscription;
            context = ctx;
            admitted.add(this);
            client.send(new ShareMessages.Welcome(table.id(), subscription.setup(), subscription.latestSeq(),
                    subscription.latestStep(), subscription.stepIndex()));
            awaitingWant = true;
            ctx.executor().schedule(() -> {
                if (awaitingWant && ctx.channel().isActive()) {
                    netLog.warn("[Companion] Closing {}: no want within {} s of the welcome",
                            ctx.channel().remoteAddress(), WANT_SECONDS);
                    ctx.close();
                }
            }, WANT_SECONDS, TimeUnit.SECONDS);
        }

        /**
         * The phone's want: the steps it asked for, read from the store now, then its catch-up, then the attach. All
         * on this event loop, so ahead of any live item's drain; and not under the journal's lock, as the steps are
         * read from disk ({@link ShareJournal.Subscription#steps}).
         */
        private void sendWanted(ChannelHandlerContext ctx, ShareMessages.Want want) {
            ShareJournal.Subscription subscription = data;
            if (!awaitingWant || subscription == null) {
                netLog.info("[Companion] Dropping {} from {}: not waiting for one", want,
                        ctx.channel().remoteAddress());
                return;
            }
            awaitingWant = false;
            netLog.info("[Companion] {} from {}", want, client.getUsername());
            for (ShareMessages.Step step : subscription.steps(want.steps())) {
                client.send(step);
            }
            stepsSent = true;
            drain(); // the catch-up, before the view
            ShareJournal shared = journal;
            if (shared == null || shared.ended()) {
                // an ended game (or a share stopping): no view to attach to, the end is what the phone needs. A drain
                // again, for an end written since the last one; then the connection closes once it's all out
                drain();
                netLog.info("[Companion] {} joined {}, which has ended: letting it go", client.getUsername(),
                        table.id());
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            peer.attach();
            netLog.info("[Companion] {} joined {} from {}", client.getUsername(), table.id(),
                    ctx.channel().remoteAddress());
        }

        private void scheduleDrain(ChannelHandlerContext ctx) {
            try {
                ctx.executor().execute(this::drain);
            } catch (RejectedExecutionException e) {
                // the share is stopping: the phone goes with it, and the store's write mustn't fail for that
                netLog.info("[Companion] Not sending to {}: the share is stopping", client.getUsername());
            }
        }

        /**
         * On the share's stop: sends what the subscription still has queued, then closes the connection once it's
         * written. Returns the connection's close. Any thread.
         *
         * <p>Known edge: a phone mid-join, welcomed but whose want hasn't come yet, gets nothing here, as its drain
         * sends nothing before the wanted steps; so it's closed without the game's {@code end}, even when the end was
         * just written. Its copy stays unended, and its next join finds the share gone; slice 04's rejoin covers it.
         */
        ChannelFuture finish() {
            ChannelHandlerContext ctx = context;
            try {
                ctx.executor().execute(() -> {
                    drain();
                    // the close waits for the writes before it, so nothing queued is cut off
                    ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                });
            } catch (RejectedExecutionException e) {
                ctx.close();
            }
            return ctx.channel().closeFuture();
        }

        /**
         * Sends this phone what its subscription has queued, oldest first; nothing before its wanted steps went. The
         * connection's event loop.
         */
        private void drain() {
            ShareJournal.Subscription subscription = data;
            if (subscription == null || !stepsSent) {
                return;
            }
            for (NetEvent message : subscription.drain()) {
                client.send(message);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
                netLog.warn("[Companion] {} sent nothing for {} s: closing", ctx.channel().remoteAddress(),
                        READER_IDLE_SECONDS);
                ctx.close();
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            admitted.remove(this);
            ShareJournal.Subscription subscription = data;
            if (subscription != null) {
                subscription.close();
                data = null;
            }
            CompanionPeer left = peer;
            if (left != null) {
                left.close();
                peers.remove(left);
                netLog.info("[Companion] {} left {}", client.getUsername(), table.id());
            }
            super.channelInactive(ctx);
        }
    }

    /**
     * Net play's protocol handler for an admitted phone: every call goes to its {@link PeerGate}, which drops it, and a
     * checksum resync is marked for the game thread, as {@code GameServerHandler.beforeCall} does.
     */
    private static final class PeerHandler extends GameProtocolHandler<IGameController> {
        private final Admission admission;

        PeerHandler(Admission admission) {
            super(false);
            this.admission = admission;
        }

        @Override
        protected ReplyPool getReplyPool(ChannelHandlerContext ctx) {
            return admission.peer.client().getReplyPool();
        }

        @Override
        protected IRemote getRemote(ChannelHandlerContext ctx) {
            return admission.peer.client();
        }

        @Override
        protected IGameController getToInvoke(ChannelHandlerContext ctx) {
            return admission.peer.gate();
        }

        @Override
        protected void beforeCall(ChannelHandlerContext ctx, ProtocolMethod protocolMethod, Object[] args) {
            if (protocolMethod == ProtocolMethod.requestResync) {
                netLog.debug("[DeltaSync] Resync requested by {}, deferring to game thread",
                        admission.peer.client().getUsername());
                admission.peer.gui().setResyncPending();
            }
        }
    }
}
