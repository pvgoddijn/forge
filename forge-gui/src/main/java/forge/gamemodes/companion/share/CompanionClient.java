package forge.gamemodes.companion.share;

import forge.gamemodes.match.GameLobby;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.ILobbyListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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

    public CompanionClient(String username, IGuiGame gui, JoinCode code) {
        super(username, gui, code.address().getHostAddress(), code.port());
        this.token = code.token();
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
            connectionOpen = true;
            listenersTold = false;
            // before the login: this handler's channelActive runs before the protocol handler's, which writes it, and
            // writes on the event loop go out in order, so the host's TokenGate reads these 8 bytes first
            ctx.channel().writeAndFlush(TokenGate.preamble(ctx.alloc(), token));
            super.channelActive(ctx); // the protocol handler after this one sends the login
            ctx.channel().writeAndFlush(new ShareMessages.Hello(token, null));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            connectionOpen = false;
            // net play's handler, before this one, tells the listeners only when this is its current channel; a host
            // that closes at once (a wrong token) can beat connect() to setting it, and then no one would hear of it
            if (!listenersTold && !left && getReconnectState() == ReconnectState.CONNECTED) {
                netLog.info("[Companion] Connection closed before it was counted: telling the listeners");
                for (ILobbyListener listener : listeners) {
                    listener.close();
                }
            }
            super.channelInactive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ShareMessages.Welcome welcome) {
                welcomedTo = welcome.game();
                netLog.info("[Companion] {}", welcome);
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
}
