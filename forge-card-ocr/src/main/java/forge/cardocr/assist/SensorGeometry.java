package forge.cardocr.assist;

import forge.cardocr.Dim;
import forge.cardocr.finder.*;

/**
 * Coordinate changes between camera streams. Camera2 fills each stream from the largest centred
 * rectangle of the crop region that has the stream's aspect ratio, so streams of different ratios
 * show different parts of the sensor. Going through active-array coordinates accounts for that.
 */
public final class SensorGeometry {

    private SensorGeometry() {
    }

    /** The part of the sensor that a stream of this size shows. */
    public static ActiveRect visibleArea(Dim stream, ActiveRect crop) {
        double streamAspect = (double) stream.width() / stream.height();
        double width = crop.width();
        double height = crop.height();
        if (streamAspect > width / height) {
            height = width / streamAspect;
        } else {
            width = height * streamAspect;
        }
        return new ActiveRect(crop.left() + (crop.width() - width) / 2,
                crop.top() + (crop.height() - height) / 2, width, height);
    }

    public static Corner streamToActive(Corner point, Dim stream, ActiveRect crop) {
        ActiveRect area = visibleArea(stream, crop);
        return new Corner(area.left() + point.x() / stream.width() * area.width(),
                area.top() + point.y() / stream.height() * area.height());
    }

    public static Corner activeToStream(Corner point, Dim stream, ActiveRect crop) {
        ActiveRect area = visibleArea(stream, crop);
        return new Corner((point.x() - area.left()) / area.width() * stream.width(),
                (point.y() - area.top()) / area.height() * stream.height());
    }

    /** Moves a point from one stream to another. */
    public static Corner streamToStream(Corner point, Dim from, Dim to, ActiveRect crop) {
        return activeToStream(streamToActive(point, from, crop), to, crop);
    }

    /** A point's position after its image of the given size is turned clockwise. */
    public static Corner rotated(Corner p, Dim image, int degrees) {
        return switch (((degrees % 360) + 360) % 360) {
            case 0 -> p;
            case 90 -> new Corner(image.height() - p.y(), p.x());
            case 180 -> new Corner(image.width() - p.x(), image.height() - p.y());
            case 270 -> new Corner(p.y(), image.width() - p.x());
            default -> throw new IllegalArgumentException("Not a right angle: " + degrees);
        };
    }

    /** The size of an image after turning it clockwise. */
    public static Dim rotatedSize(Dim image, int degrees) {
        return degrees % 180 == 0 ? image : new Dim(image.height(), image.width());
    }
}
