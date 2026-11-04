package forge.cardocr.finder;

/** A point in image pixel coordinates. */
public record Corner(double x, double y) {

    public double distanceTo(Corner other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    Corner shifted(double dx, double dy) {
        return new Corner(x + dx, y + dy);
    }

    Corner scaled(double factor) {
        return new Corner(x * factor, y * factor);
    }
}
