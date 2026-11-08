package forge.gamemodes.companion.share;

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
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
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

import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * {@link ShareJournal#subscribe subscribes} with the {@code have} of its hello and gets, in this order, the welcome
 * (setup and latest seq), its catch-up, then the attach, and then each item the store writes, live, in write order.
 * Each phone's data is sent from its connection's event loop: the store's wakeup only schedules a drain there, so the
 * game thread never waits for a phone.
 */
public final class CompanionShareHost implements IHasForgeLog {
    /** The Companion port, one above net play's 36743; the next free one up to {@link #LAST_PORT} if it's taken. */
    public static final int FIRST_PORT = 36744;
    public static final int LAST_PORT = 36753;
    /** A phone that sends nothing for this long is gone (its heartbeat is every 15 s; plan 3.1). */
    static final int READER_IDLE_SECONDS = 15;
    /** A connection that hasn't been admitted by then is closed. */
    static final int ADMISSION_SECONDS = 15;
    private static final int MAX_FRAME_BYTES = 9766 * 1024; // as net play's

    /** A code to show, with the interface its address is on. */
    public record Code(String interfaceName, JoinCode code) { }

    private final CompanionTable table;
    private final int token;
    private final Set<CompanionPeer> peers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger refusedAtGate = new AtomicInteger();
    /** The measurement's logging (slice 01); null, and nothing measured, when it's off. */
    private final ShareMeter meter = ShareMeter.enabled() ? new ShareMeter() : null;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int port;
    /** The table's journal while shared, else null. */
    private volatile ShareJournal journal;

    /** Shares {@code table} with a fresh random token (kept across restarts from slice 04). */
    public CompanionShareHost(CompanionTable table) {
        this(table, new SecureRandom().nextInt());
    }

    CompanionShareHost(CompanionTable table, int token) {
        this.table = table;
        this.token = token;
    }

    /**
     * Listens on every interface, on the first free port from {@link #FIRST_PORT} to {@link #LAST_PORT}, and returns
     * it. Throws {@link IllegalStateException} when none is free. Starting a started share returns its port.
     */
    public synchronized int start() {
        return start(FIRST_PORT, LAST_PORT);
    }

    synchronized int start(int first, int last) {
        if (serverChannel != null) {
            return port;
        }
        // before listening, so every phone finds it; with no phone it only fills memory (AC-114)
        journal = ShareJournal.install(table.store(), table.id());
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
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
        for (int p = first; p <= last; p++) {
            ChannelFuture bound = bootstrap.bind(p).awaitUninterruptibly();
            if (bound.isSuccess()) {
                serverChannel = bound.channel();
                port = p;
                netLog.info("[Companion] Sharing {} on port {}", table.id(), p);
                return p;
            }
            netLog.info("[Companion] Port {} is taken: {}", p, String.valueOf(bound.cause()));
        }
        stop();
        throw new IllegalStateException("No free port from " + first + " to " + last + " to share the game on");
    }

    /** Stops listening and lets every phone go. Stopping a stopped share does nothing. Any thread. */
    public synchronized void stop() {
        if (journal != null) {
            table.store().setJournal(null); // the store writes as unshared again
            journal = null;
        }
        for (CompanionPeer peer : peers) {
            peer.close();
        }
        peers.clear();
        if (meter != null) {
            meter.write();
        }
        if (serverChannel != null) {
            serverChannel.close();
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
            client.send(new ShareMessages.Welcome(table.id(), subscription.setup(), subscription.latestSeq()));
            drain(); // the catch-up, before the view: on this event loop, so ahead of any live item's drain
            joined.attach();
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

        /** Sends this phone what its subscription has queued, oldest first. The connection's event loop. */
        private void drain() {
            ShareJournal.Subscription subscription = data;
            if (subscription == null) {
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
