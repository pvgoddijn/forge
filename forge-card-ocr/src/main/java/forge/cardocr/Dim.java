package forge.cardocr;

/** A width and height. Plain Java so size logic runs in JVM unit tests, unlike android.util.Size. */
public record Dim(int width, int height) {

    public long area() {
        return (long) width * height;
    }

    /** Long side over short side, so 1920x1080 and 1080x1920 compare as equal. */
    public double aspect() {
        return (double) Math.max(width, height) / Math.min(width, height);
    }

    public int longSide() {
        return Math.max(width, height);
    }

    public int shortSide() {
        return Math.min(width, height);
    }
}
