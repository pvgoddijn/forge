package forge.cardocr.assist;

import java.nio.*;

/** Copies a camera Y plane into the gap-free rows the finder needs. */
public final class YPlane {

    private YPlane() {
    }

    /**
     * Doesn't move the buffer's position. The plane's last row may stop right after its last pixel,
     * so the buffer can be shorter than rowStride times height.
     *
     * @param out receives width times height bytes
     */
    public static void compact(ByteBuffer plane, int rowStride, int pixelStride, int width, int height, byte[] out) {
        ByteBuffer in = plane.duplicate();
        int start = in.position();
        for (int y = 0; y < height; y++) {
            int row = start + y * rowStride;
            if (pixelStride == 1) {
                in.position(row);
                in.get(out, y * width, width);
            } else {
                for (int x = 0; x < width; x++) {
                    out[y * width + x] = in.get(row + x * pixelStride);
                }
            }
        }
    }
}
