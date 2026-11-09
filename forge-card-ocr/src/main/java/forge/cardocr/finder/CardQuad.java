package forge.cardocr.finder;

import java.util.*;

/**
 * A candidate card outline in the coordinates of the image given to the finder.
 *
 * <p>Corners go around the card from {@code c0}: c0-c1 and c3-c2 are the short (63 mm) sides,
 * c0-c3 and c1-c2 the long (88 mm) sides. The finder works on outlines only, so it can't tell
 * which way up the card is: the reading direction is either this quad or {@link #flipped()}.
 *
 * @param score higher is better. Comparable within one result, not an absolute confidence
 * @param refined true if four edges were fitted to the image. False means the rough rectangle was
 *     kept with a lowered score, so its corners are only approximate
 * @param evidence 0 to 1, how much this looks like a whole card and not just any rectangle. See
 *     {@link CardEvidence}. Unlike the score, it means the same from image to image
 */
public record CardQuad(Corner c0, Corner c1, Corner c2, Corner c3, double score, boolean refined, double evidence) {

    /** Evidence below this means the outline must not be treated as a card. */
    public static final double MIN_CARD_EVIDENCE = 0.35;

    /** An outline with no measurements behind it: full evidence if fitted, none if rough. */
    public CardQuad(Corner c0, Corner c1, Corner c2, Corner c3, double score, boolean refined) {
        this(c0, c1, c2, c3, score, refined, refined ? 1 : 0);
    }

    public List<Corner> corners() {
        return List.of(c0, c1, c2, c3);
    }

    /** The same outline turned 180 degrees. */
    public CardQuad flipped() {
        return new CardQuad(c2, c3, c0, c1, score, refined, evidence);
    }

    /** The same outline in an image scaled by this factor, such as a full photo after a preview. */
    public CardQuad scaled(double factor) {
        return new CardQuad(c0.scaled(factor), c1.scaled(factor), c2.scaled(factor),
                c3.scaled(factor), score, refined, evidence);
    }

    /** The same outline moved in the image. */
    public CardQuad shifted(double dx, double dy) {
        return new CardQuad(c0.shifted(dx, dy), c1.shifted(dx, dy), c2.shifted(dx, dy), c3.shifted(dx, dy), score,
                refined, evidence);
    }

    CardQuad withScore(double newScore) {
        return new CardQuad(c0, c1, c2, c3, newScore, refined, evidence);
    }

    double area() {
        return Math.abs(cross(c0, c1) + cross(c1, c2) + cross(c2, c3) + cross(c3, c0)) / 2;
    }

    /** Whether a point is inside this quad, which must be convex. */
    boolean contains(Corner p) {
        double a = side(c0, c1, p);
        double b = side(c1, c2, p);
        double c = side(c2, c3, p);
        double d = side(c3, c0, p);
        return (a >= 0 && b >= 0 && c >= 0 && d >= 0) || (a <= 0 && b <= 0 && c <= 0 && d <= 0);
    }

    private static double cross(Corner a, Corner b) {
        return a.x() * b.y() - a.y() * b.x();
    }

    private static double side(Corner a, Corner b, Corner p) {
        return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
    }

    /** The largest corner distance to the other quad, in the given corner order. */
    double maxCornerDistance(CardQuad other) {
        return Math.max(Math.max(c0.distanceTo(other.c0), c1.distanceTo(other.c1)),
                Math.max(c2.distanceTo(other.c2), c3.distanceTo(other.c3)));
    }
}
