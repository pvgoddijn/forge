package forge.gamemodes.companion.share;

import forge.net.HeadlessNetworkGuiGame;
import forge.net.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measurement harness for slice 01 (not a test, never run by CI): opens N headless joins to a shared Companion game,
 * keeps them joined, and prints the bytes each one receives, so a host phone can be loaded with 8 or 12 consumers
 * with only two phones to hand. Each viewer is a real {@link CompanionClient}, so it writes the {@code "FCMP"} + token
 * preamble the host's {@link TokenGate} wants, says hello and follows the view like a joined phone.
 *
 * <p>Usage: {@code ViewerLoad <join code> <viewers> <seconds>}. Bytes are counted off the socket, before LZ4 and
 * deserialization, so they compare with the host's {@code [CompanionMeasure]} bytes per peer.
 */
public final class ViewerLoad {
    private static final long REPORT_EVERY_MS = 10_000;

    private ViewerLoad() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 3) {
            System.err.println("Usage: ViewerLoad <join code> <viewers> <seconds>");
            System.exit(2);
        }
        JoinCode code = JoinCode.parse(args[0]).orElseThrow(() -> new IllegalArgumentException("Not a join code: " + args[0]));
        int count = Integer.parseInt(args[1]);
        long seconds = Long.parseLong(args[2]);
        System.out.printf("ViewerLoad: %d viewers to %s:%d for %d s%n", count, code.address().getHostAddress(),
                code.port(), seconds);

        TestUtils.ensureFModelInitialized(); // the viewers decode card views like a phone does

        List<CountingClient> viewers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            CountingClient viewer = new CountingClient("Viewer-" + i, code);
            try {
                viewer.connect();
                viewers.add(viewer);
            } catch (RuntimeException e) {
                System.out.printf("Viewer-%d failed to connect: %s%n", i, e);
            }
        }

        long end = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(Math.min(REPORT_EVERY_MS, Math.max(1, end - System.currentTimeMillis())));
            report(viewers);
        }
        for (CountingClient viewer : viewers) {
            viewer.close();
        }
        System.out.println("ViewerLoad: done");
        System.exit(0); // Netty's event loop threads aren't daemons
    }

    private static void report(List<CountingClient> viewers) {
        long total = 0;
        StringBuilder line = new StringBuilder();
        for (CountingClient viewer : viewers) {
            long bytes = viewer.bytes.get();
            total += bytes;
            line.append(String.format("%n  %s: %,d B, %s, epoch %d", viewer.getUsername(), bytes,
                    viewer.isActive() ? (viewer.welcomedTo() != null ? "joined" : "connecting") : "NOT ACTIVE",
                    viewer.epoch()));
        }
        System.out.printf("ViewerLoad: %d viewers, %,d B in total%s%n", viewers.size(), total, line);
    }

    /** A Companion client that also counts the bytes off its socket. */
    private static final class CountingClient extends CompanionClient {
        final AtomicLong bytes = new AtomicLong();

        CountingClient(String name, JoinCode code) {
            super(name, new HeadlessNetworkGuiGame(), code);
        }

        /**
         * Puts the byte counter at the head of each new connection's pipeline, then steps aside for Companion's own
         * data handler (which writes the preamble).
         */
        @Override
        protected ChannelHandler newExtraInboundHandler() {
            ChannelHandler data = super.newExtraInboundHandler();
            return new ChannelInboundHandlerAdapter() {
                @Override
                public void handlerAdded(ChannelHandlerContext ctx) {
                    ctx.pipeline().addFirst(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext c, Object msg) {
                            if (msg instanceof ByteBuf buf) {
                                bytes.addAndGet(buf.readableBytes());
                            }
                            c.fireChannelRead(msg);
                        }
                    });
                    ctx.pipeline().replace(this, null, data);
                }
            };
        }
    }
}
