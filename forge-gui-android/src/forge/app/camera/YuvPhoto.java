package forge.app.camera;

import android.graphics.*;
import android.media.Image;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Turns a YUV photo into a JPEG in software, so that every photo is a file that can be read the same way. */
final class YuvPhoto {

    private static final int QUALITY = 95;

    private YuvPhoto() {
    }

    static byte[] toJpeg(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer y = planes[0].getBuffer();
        for (int row = 0; row < height; row++) {
            y.position(row * planes[0].getRowStride());
            y.get(nv21, row * width, width);
        }
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int out = width * height;
        for (int row = 0; row < height / 2; row++) {
            for (int column = 0; column < width / 2; column++) {
                nv21[out++] = v.get(row * planes[2].getRowStride() + column * planes[2].getPixelStride());
                nv21[out++] = u.get(row * planes[1].getRowStride() + column * planes[1].getPixelStride());
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(1 << 20);
        new YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(new Rect(0, 0, width, height), QUALITY, jpeg);
        return jpeg.toByteArray();
    }
}
