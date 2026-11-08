package forge.gamemodes.companion.share;

import forge.gamemodes.net.client.FGameClient;
import forge.gui.interfaces.IGuiGame;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * A joined phone's connection to a shared Companion game (plan 2.2): net play's client, with Companion's admission and
 * data messages on top, as {@code ReplayClient} reuses it for a recording. Net play's login goes first, then
 * {@link ShareMessages.Hello} with the code's token; the host answers {@link ShareMessages.Welcome}, then sends the view
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

    private final int token;
    private volatile String welcomedTo;
    private volatile int epoch;

    public CompanionClient(String username, IGuiGame gui, JoinCode code) {
        super(username, gui, code.address().getHostAddress(), code.port());
        this.token = code.token();
    }

    /** The shared game's id once the host welcomed this phone, else null. */
    public String welcomedTo() {
        return welcomedTo;
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

    /** Says hello after the login, and takes Companion's messages off the view stream. Netty's thread. */
    private final class DataHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx); // the protocol handler after this one sends the login first
            ctx.channel().writeAndFlush(new ShareMessages.Hello(token, null));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ShareMessages.Welcome welcome) {
                welcomedTo = welcome.game();
                netLog.info("[Companion] {}", welcome);
            } else if (msg instanceof ShareMessages.Epoch e) {
                epoch = e.n();
                netLog.info("[Companion] {}", e);
            } else {
                super.channelRead(ctx, msg);
            }
        }
    }
}
