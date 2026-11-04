package forge.cardocr.assist;

import forge.cardocr.Dim;
import forge.cardocr.finder.*;

/**
 * Maps an analysis-frame outline onto a stored JPEG's pixels. Phones differ: some rotate the pixels
 * to the requested JPEG orientation and others only set the EXIF tag.
 */
public final class StillMapper {

    /** Keeps fine detail while bounding memory. Three of these are decoded one after another. */
    private static final int MAX_CROP_LONG_SIDE = 1600;

    /**
     * A region of a stored JPEG to decode, and the outline within the decoded result.
     *
     * @param sampleSize the power-of-two shrink to decode with
     */
    public record Crop(int left, int top, int right, int bottom, int sampleSize, CardQuad quad) {
    }

    private StillMapper() {
    }

    /**
     * How far the stored pixels are turned from the sensor's. The size settles any disagreement,
     * because a JPEG with neither rotated pixels nor an EXIF tag would otherwise look turned.
     */
    public static int pixelRotation(int requestedDegrees, int exifDegrees, Dim stream, Dim stored) {
        int rotation = ((requestedDegrees - exifDegrees) % 360 + 360) % 360;
        boolean swappedBySize = (stream.width() > stream.height()) != (stored.width() > stored.height());
        boolean swappedByTags = rotation % 180 != 0;
        if (stream.width() != stream.height() && swappedBySize != swappedByTags) {
            return swappedBySize ? requestedDegrees : 0;
        }
        return rotation;
    }

    /** The outline in stored pixels. Its corners keep their order around the card. */
    public static CardQuad toStored(CardQuad quad, Dim analysis, Dim jpegStream, ActiveRect crop,
            int pixelRotation, Dim stored) {
        Corner[] out = new Corner[4];
        Dim rotatedStream = SensorGeometry.rotatedSize(jpegStream, pixelRotation);
        double scaleX = (double) stored.width() / rotatedStream.width();
        double scaleY = (double) stored.height() / rotatedStream.height();
        for (int i = 0; i < 4; i++) {
            Corner inStream = SensorGeometry.streamToStream(quad.corners().get(i), analysis, jpegStream, crop);
            Corner turned = SensorGeometry.rotated(inStream, jpegStream, pixelRotation);
            out[i] = new Corner(turned.x() * scaleX, turned.y() * scaleY);
        }
        return new CardQuad(out[0], out[1], out[2], out[3], quad.score(), quad.refined());
    }

    /** As {@link #toStored}, for a photo taken with its own camera crop rather than the analysis stream's. */
    public static CardQuad toStoredCropped(CardQuad quad, Dim analysis, ActiveRect analysisCrop, ActiveRect photoCrop,
            int pixelRotation, Dim stored) {
        Corner[] out = new Corner[4];
        Dim rotated = SensorGeometry.rotatedSize(stored, pixelRotation);
        for (int i = 0; i < 4; i++) {
            Corner active = SensorGeometry.streamToActive(quad.corners().get(i), analysis, analysisCrop);
            Corner inPhoto = new Corner((active.x() - photoCrop.left()) / photoCrop.width() * rotated.width(),
                    (active.y() - photoCrop.top()) / photoCrop.height() * rotated.height());
            out[i] = SensorGeometry.rotated(inPhoto, rotated, pixelRotation);
        }
        return new CardQuad(out[0], out[1], out[2], out[3], quad.score(), quad.refined());
    }

    /** The region around an outline to decode, or null if the outline misses the image. */
    public static Crop cropFor(CardQuad quad, Dim stored) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Corner c : quad.corners()) {
            minX = Math.min(minX, c.x());
            minY = Math.min(minY, c.y());
            maxX = Math.max(maxX, c.x());
            maxY = Math.max(maxY, c.y());
        }
        int left = (int) Math.max(0, Math.floor(minX));
        int top = (int) Math.max(0, Math.floor(minY));
        int right = (int) Math.min(stored.width(), Math.ceil(maxX));
        int bottom = (int) Math.min(stored.height(), Math.ceil(maxY));
        if (right - left < 2 || bottom - top < 2) {
            return null;
        }
        int sample = 1;
        while (Math.max(right - left, bottom - top) / sample > MAX_CROP_LONG_SIDE) {
            sample *= 2;
        }
        Corner[] out = new Corner[4];
        for (int i = 0; i < 4; i++) {
            Corner c = quad.corners().get(i);
            out[i] = new Corner((c.x() - left) / sample, (c.y() - top) / sample);
        }
        return new Crop(left, top, right, bottom, sample,
                new CardQuad(out[0], out[1], out[2], out[3], quad.score(), quad.refined()));
    }
}
