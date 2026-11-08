package forge.gamemodes.companion.share;

import forge.util.IHasForgeLog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

/**
 * The first handler of a share's connection: before a single byte reaches Java deserialization, the phone must send
 * the raw preamble {@code "FCMP"} + the share token (4 bytes, big-endian). A wrong preamble closes the connection at
 * once, and so does none within the admission time; the right one takes the gate out of the pipeline, and the bytes
 * after it go on to net play's decoder. {@link ShareMessages.Hello} still carries the token, checked again after it.
 */
final class TokenGate extends ByteToMessageDecoder implements IHasForgeLog {
    /** "FCMP": Forge Companion multi-phone. */
    static final int MAGIC = 0x46434D50;
    static final int LENGTH = 8;

    private final IntPredicate tokenMatches;
    private final Runnable onRefused;
    private final int timeoutSeconds;

    /** {@code onRefused} runs on Netty's thread for each connection the gate closes. */
    TokenGate(IntPredicate tokenMatches, Runnable onRefused, int timeoutSeconds) {
        this.tokenMatches = tokenMatches;
        this.onRefused = onRefused;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** The preamble a phone writes first, raw, for {@code token}. The caller passes it to the write, which owns it. */
    static ByteBuf preamble(ByteBufAllocator alloc, int token) {
        ByteBuf buf = alloc.buffer(LENGTH);
        buf.writeInt(MAGIC);
        buf.writeInt(token);
        return buf;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.executor().schedule(() -> {
            if (!ctx.isRemoved() && ctx.channel().isActive()) {
                refuse(ctx, "no preamble within " + timeoutSeconds + " s");
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
        super.channelActive(ctx);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < LENGTH) {
            return;
        }
        int magic = in.readInt();
        int token = in.readInt();
        if (magic != MAGIC || !tokenMatches.test(token)) {
            in.skipBytes(in.readableBytes());
            refuse(ctx, magic != MAGIC ? "not a Companion preamble" : "wrong share token");
            return;
        }
        // whatever came after the preamble goes on to the decoder when the gate leaves
        ctx.pipeline().remove(this);
    }

    private void refuse(ChannelHandlerContext ctx, String why) {
        // no refusal message before slice 07: the phone sees the connection close
        netLog.warn("[Companion] Closing {} at the gate: {}", ctx.channel().remoteAddress(), why);
        onRefused.run();
        ctx.close();
    }
}
