package forge.cardocr.assist;

import forge.cardocr.finder.*;

/** Outline measurements for the live tracking code. */
final class Quads {

    private Quads() {
    }

    static Corner centre(CardQuad q) {
        return new Corner((q.c0().x() + q.c1().x() + q.c2().x() + q.c3().x()) / 4,
                (q.c0().y() + q.c1().y() + q.c2().y() + q.c3().y()) / 4);
    }

    static double longSide(CardQuad q) {
        return Math.max(q.c0().distanceTo(q.c3()), q.c1().distanceTo(q.c2()));
    }

    static double area(CardQuad q) {
        return Math.abs(cross(q.c0(), q.c1()) + cross(q.c1(), q.c2()) + cross(q.c2(), q.c3()) + cross(q.c3(), q.c0())) / 2;
    }

    /** The worst corner distance, allowing the 180 degree turn that outlines can't resolve. */
    static double distance(CardQuad a, CardQuad b) {
        return Math.min(ordered(a, b), ordered(a, b.flipped()));
    }

    /** {@code q}, turned if needed so its corners line up with the reference's. */
    static CardQuad alignedTo(CardQuad q, CardQuad reference) {
        return ordered(q, reference) <= ordered(q.flipped(), reference) ? q : q.flipped();
    }

    /** Part of the way from a to b, keeping b's scores. Their corners must already line up. */
    static CardQuad between(CardQuad a, CardQuad b, double share) {
        return new CardQuad(between(a.c0(), b.c0(), share), between(a.c1(), b.c1(), share), between(a.c2(), b.c2(), share),
                between(a.c3(), b.c3(), share), b.score(), b.refined(), b.evidence());
    }

    private static Corner between(Corner a, Corner b, double share) {
        return new Corner(a.x() + share * (b.x() - a.x()), a.y() + share * (b.y() - a.y()));
    }

    /** {@code q} with each corner moved as far as the same corner moved from {@code from} to {@code to}. */
    static CardQuad offset(CardQuad q, CardQuad from, CardQuad to) {
        return new CardQuad(offset(q.c0(), from.c0(), to.c0()), offset(q.c1(), from.c1(), to.c1()), offset(q.c2(), from.c2(), to.c2()),
                offset(q.c3(), from.c3(), to.c3()), q.score(), q.refined(), q.evidence());
    }

    private static Corner offset(Corner p, Corner from, Corner to) {
        return new Corner(p.x() + to.x() - from.x(), p.y() + to.y() - from.y());
    }

    /** The median of the four corners' distances. Their order must already line up. */
    static double medianCornerDistance(CardQuad a, CardQuad b) {
        double[] gaps = {a.c0().distanceTo(b.c0()), a.c1().distanceTo(b.c1()), a.c2().distanceTo(b.c2()), a.c3().distanceTo(b.c3())};
        java.util.Arrays.sort(gaps);
        return (gaps[1] + gaps[2]) / 2;
    }

    /** Whether all of inner's corners are inside outer, which must be convex. */
    static boolean encloses(CardQuad outer, CardQuad inner) {
        for (Corner p : inner.corners()) {
            double a = side(outer.c0(), outer.c1(), p);
            double b = side(outer.c1(), outer.c2(), p);
            double c = side(outer.c2(), outer.c3(), p);
            double d = side(outer.c3(), outer.c0(), p);
            boolean in = (a >= 0 && b >= 0 && c >= 0 && d >= 0) || (a <= 0 && b <= 0 && c <= 0 && d <= 0);
            if (!in) {
                return false;
            }
        }
        return true;
    }

    /** As {@link #encloses}, with outer first enlarged by this factor about its centre. */
    static boolean enclosesWithRoom(CardQuad outer, CardQuad inner, double factor) {
        Corner c = centre(outer);
        return encloses(outer.shifted(-c.x(), -c.y()).scaled(factor).shifted(c.x(), c.y()), inner);
    }

    private static double ordered(CardQuad a, CardQuad b) {
        return Math.max(Math.max(a.c0().distanceTo(b.c0()), a.c1().distanceTo(b.c1())),
                Math.max(a.c2().distanceTo(b.c2()), a.c3().distanceTo(b.c3())));
    }

    private static double cross(Corner a, Corner b) {
        return a.x() * b.y() - a.y() * b.x();
    }

    private static double side(Corner a, Corner b, Corner p) {
        return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
    }
}
