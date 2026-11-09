package forge.cardocr.finder;

import java.util.*;

/**
 * Finds 63x88 mm cards in a greyscale image, at any angle and without a guide frame.
 *
 * <p>Plain Java with no Android or AWT types. An instance reuses buffers between calls, so use one
 * instance per thread. All work happens on the calling thread.
 */
public final class CardFinder {

    /** Refined outlines closer than this, as a share of the image's long side, are the same one. */
    private static final double SAME_OUTLINE = 0.015;
    private static final double UNREFINED_PENALTY = 0.15;
    /** The art box is about 0.84 x 0.435 = 0.365 of a card's area. */
    private static final double MIN_ART_SHARE = 0.28;
    private static final double MAX_ART_SHARE = 0.48;
    /** A rectangle lying across a card can take up this much of it: art box, title and type line. */
    private static final double MAX_SIDEWAYS_SHARE = 0.85;
    private static final double MAX_ACROSS_COS = Math.cos(Math.toRadians(65));
    private static final double ART_BOX_PENALTY = 0.6;
    private static final double NESTING_MARGIN = 1.08;
    /** Offsets of a nested outline's centre from the card's, as shares of the card's sides. */
    private static final double MAX_CENTRE_OFFSET_ACROSS = 0.1;
    private static final double MAX_CENTRE_OFFSET_ALONG = 0.3;
    private static final int ENCLOSING_CHECKS = 3;
    private static final int POLISHED = 3;
    private static final double MAX_POLISH_SHIFT = 0.025;
    private static final double MAX_REFIT_SHIFT = 0.07;
    /** The art box's share of the card's width and height, and its centre's offset from the card's. */
    private static final double ART_WIDTH = 0.84;
    private static final double ART_HEIGHT = 0.435;
    private static final double ART_OFFSET = 0.1675;

    private final FinderOptions options;
    private final RoughSearch roughSearch = new RoughSearch();

    public CardFinder() {
        this(FinderOptions.DEFAULTS);
    }

    public CardFinder(FinderOptions options) {
        this.options = options;
    }

    /**
     * Finds candidates in 8-bit luma stored without gaps between rows. A camera Y plane whose row
     * stride differs from its width must be compacted first.
     */
    public FindResult find(byte[] luma, int width, int height) {
        long start = System.nanoTime();
        return find(LumaImage.fromBytes(luma, width, height), start);
    }

    /** A refit if there are seeds, or else a full find. */
    public FindResult find(byte[] luma, int width, int height, List<CardQuad> seeds) {
        return seeds == null ? find(luma, width, height) : refit(luma, width, height, seeds);
    }

    /**
     * Fits known outlines to a new frame without searching, which on the phone is about 30 times cheaper.
     * It uses the same fitting and evidence as a full find. A fit that moved further than a frame's
     * movement explains has jumped to another line, and is dropped.
     *
     * @param seeds outlines in this image's coordinates
     * @return the fits that held, best first
     */
    public FindResult refit(byte[] luma, int width, int height, List<CardQuad> seeds) {
        long start = System.nanoTime();
        LumaImage image = LumaImage.fromBytes(luma, width, height).fitted(options.refineLongSide());
        double scale = (double) image.width() / width;
        List<CardQuad> fitted = new ArrayList<>();
        for (CardQuad seed : seeds) {
            CardQuad rough = seed.scaled(scale);
            CardQuad refined = EdgeRefiner.refine(image, rough);
            if (refined != null && refined.maxCornerDistance(rough) <= MAX_REFIT_SHIFT * rough.c0().distanceTo(rough.c3())) {
                fitted.add(refined.scaled(1 / scale));
            }
        }
        fitted.sort((a, b) -> Double.compare(b.score(), a.score()));
        long elapsed = System.nanoTime() - start;
        return new FindResult(List.copyOf(fitted), 0, elapsed, elapsed, true);
    }

    /** Finds candidates in luma with values 0 to 255. The array isn't changed. */
    public FindResult find(float[] luma, int width, int height) {
        LumaImage.checkSize(luma.length, width, height);
        return find(new LumaImage(luma, width, height), System.nanoTime());
    }

    private FindResult find(LumaImage input, long start) {
        LumaImage searchImage = input.fitted(options.searchLongSide());
        double searchScale = (double) input.width() / searchImage.width();
        List<CardQuad> rough = roughSearch.search(
                searchImage, options.maxAngles(), options.roughCandidates());
        long afterSearch = System.nanoTime();

        LumaImage refineImage = input.fitted(options.refineLongSide());
        double refineScale = (double) refineImage.width() / input.width();
        List<CardQuad> candidates = new ArrayList<>();
        for (CardQuad quad : rough) {
            CardQuad roughInInput = quad.scaled(searchScale);
            CardQuad refined = EdgeRefiner.refine(refineImage, roughInInput.scaled(refineScale));
            candidates.add(refined != null
                    ? refined.scaled(1 / refineScale)
                    : roughInInput.withScore(roughInInput.score() * UNREFINED_PENALTY));
        }
        addEnclosingCards(candidates, refineImage, refineScale);
        List<CardQuad> ranked = demoteArtBoxes(candidates);
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<CardQuad> distinct = withoutRepeats(
                ranked, SAME_OUTLINE * Math.max(input.width(), input.height()));
        if (distinct.size() > options.maxCandidates()) {
            distinct = distinct.subList(0, options.maxCandidates());
        }
        distinct = polished(distinct, refineImage, refineScale);
        long end = System.nanoTime();
        return new FindResult(List.copyOf(distinct), afterSearch - start, end - afterSearch,
                end - start);
    }

    /**
     * A coarse search can start a fit several pixels off, and a tilted card's sides then get sampled
     * unevenly. Fitting the best few again from their own outlines removes most of that error. They
     * keep their ranks, since this only moves corners slightly.
     */
    private static List<CardQuad> polished(List<CardQuad> ranked, LumaImage image, double scale) {
        List<CardQuad> out = new ArrayList<>(ranked);
        for (int i = 0; i < Math.min(POLISHED, out.size()); i++) {
            CardQuad quad = out.get(i);
            if (quad.refined()) {
                CardQuad again = EdgeRefiner.refine(image, quad.scaled(scale));
                // A big move means it jumped to another line, such as a sleeve's edge, not a better fit.
                double limit = MAX_POLISH_SHIFT * quad.scaled(scale).c0().distanceTo(quad.scaled(scale).c3());
                if (again != null && again.maxCornerDistance(quad.scaled(scale)) < limit) {
                    out.set(i, again.scaled(1 / scale).withScore(quad.score()));
                }
            }
        }
        return out;
    }

    /**
     * A strong rectangle may be the art box of a card whose own outline the search didn't suggest.
     * So tries the card around each of the best few, on both possible sides of the art box.
     */
    private static void addEnclosingCards(List<CardQuad> candidates, LumaImage image, double scale) {
        List<CardQuad> best = new ArrayList<>(candidates);
        best.removeIf(q -> !q.refined());
        best.sort((a, b) -> Double.compare(b.score(), a.score()));
        for (CardQuad quad : best.subList(0, Math.min(ENCLOSING_CHECKS, best.size()))) {
            for (int side = -1; side <= 1; side += 2) {
                CardQuad around = EdgeRefiner.refine(image, cardAroundArtBox(quad, side).scaled(scale));
                if (around != null && around.evidence() >= CardQuad.MIN_CARD_EVIDENCE) {
                    candidates.add(around.scaled(1 / scale));
                }
            }
        }
    }

    /** The card an art box would belong to, with its title towards c0-side or away from it. */
    private static CardQuad cardAroundArtBox(CardQuad art, int side) {
        // The art box's long side is along the card's width, its short side along the card's height.
        double acrossX = (art.c3().x() - art.c0().x()) / ART_WIDTH;
        double acrossY = (art.c3().y() - art.c0().y()) / ART_WIDTH;
        double downX = (art.c1().x() - art.c0().x()) / ART_HEIGHT;
        double downY = (art.c1().y() - art.c0().y()) / ART_HEIGHT;
        double centreX = (art.c0().x() + art.c2().x()) / 2 + side * downX * ART_OFFSET;
        double centreY = (art.c0().y() + art.c2().y()) / 2 + side * downY * ART_OFFSET;
        Corner c0 = new Corner(centreX - acrossX / 2 - downX / 2, centreY - acrossY / 2 - downY / 2);
        Corner c1 = new Corner(centreX + acrossX / 2 - downX / 2, centreY + acrossY / 2 - downY / 2);
        Corner c2 = new Corner(centreX + acrossX / 2 + downX / 2, centreY + acrossY / 2 + downY / 2);
        Corner c3 = new Corner(centreX - acrossX / 2 + downX / 2, centreY - acrossY / 2 + downY / 2);
        return new CardQuad(c0, c1, c2, c3, art.score(), false);
    }

    /**
     * The art box is almost card-shaped when sideways, and often has stronger edges than the card.
     * So an outline sitting inside a refined candidate, at about the art box's share of its area,
     * loses to the enclosing one. A rough outer candidate doesn't count: rough rectangles include
     * guesses for a card around whatever looked like an art box, often the real card itself.
     */
    private static List<CardQuad> demoteArtBoxes(List<CardQuad> candidates) {
        List<CardQuad> out = new ArrayList<>();
        for (CardQuad quad : candidates) {
            boolean artBox = false;
            for (CardQuad other : candidates) {
                if (other != quad && other.refined() && other.evidence() >= CardQuad.MIN_CARD_EVIDENCE
                        && isArtBoxOf(quad, other)) {
                    artBox = true;
                    break;
                }
            }
            out.add(artBox ? quad.withScore(quad.score() * ART_BOX_PENALTY) : quad);
        }
        return out;
    }

    private static boolean isArtBoxOf(CardQuad inner, CardQuad outer) {
        double share = inner.area() / outer.area();
        if (share > MAX_ART_SHARE && share <= MAX_SIDEWAYS_SHARE && !liesAcross(inner, outer)) {
            return false;
        }
        if (share < MIN_ART_SHARE || share > MAX_SIDEWAYS_SHARE) {
            return false;
        }
        if (!sitsOnCentreLine(inner, outer)) {
            return false;
        }
        // Fitted lines wander a little, so a corner may lie just outside the outer outline.
        double cx = (outer.c0().x() + outer.c2().x()) / 2;
        double cy = (outer.c0().y() + outer.c2().y()) / 2;
        CardQuad roomy = outer.shifted(-cx, -cy).scaled(NESTING_MARGIN).shifted(cx, cy);
        for (Corner corner : inner.corners()) {
            if (!roomy.contains(corner)) {
                return false;
            }
        }
        return true;
    }

    /** An art box is centred across the card, and lies towards one end along it. */
    private static boolean sitsOnCentreLine(CardQuad inner, CardQuad outer) {
        double dx = (inner.c0().x() + inner.c2().x() - outer.c0().x() - outer.c2().x()) / 2;
        double dy = (inner.c0().y() + inner.c2().y() - outer.c0().y() - outer.c2().y()) / 2;
        double widthX = outer.c1().x() - outer.c0().x();
        double widthY = outer.c1().y() - outer.c0().y();
        double lengthX = outer.c3().x() - outer.c0().x();
        double lengthY = outer.c3().y() - outer.c0().y();
        double across = Math.abs(dx * widthX + dy * widthY) / (widthX * widthX + widthY * widthY);
        double along = Math.abs(dx * lengthX + dy * lengthY) / (lengthX * lengthX + lengthY * lengthY);
        return across <= MAX_CENTRE_OFFSET_ACROSS && along <= MAX_CENTRE_OFFSET_ALONG;
    }

    /** Whether the inner outline's long side runs along the outer one's short side. */
    private static boolean liesAcross(CardQuad inner, CardQuad outer) {
        double ix = inner.c3().x() - inner.c0().x();
        double iy = inner.c3().y() - inner.c0().y();
        double ox = outer.c3().x() - outer.c0().x();
        double oy = outer.c3().y() - outer.c0().y();
        double cos = Math.abs(ix * ox + iy * oy) / (Math.hypot(ix, iy) * Math.hypot(ox, oy));
        return cos < MAX_ACROSS_COS;
    }

    /** Two rough rectangles can refine to one outline, and only the first should use up a rank. */
    private static List<CardQuad> withoutRepeats(List<CardQuad> sorted, double limit) {
        List<CardQuad> kept = new ArrayList<>();
        for (CardQuad quad : sorted) {
            boolean repeat = false;
            for (CardQuad other : kept) {
                if (quad.maxCornerDistance(other) < limit
                        || quad.maxCornerDistance(other.flipped()) < limit) {
                    repeat = true;
                    break;
                }
            }
            if (!repeat) {
                kept.add(quad);
            }
        }
        return kept;
    }

    /**
     * Flattens a quad to an upright image, averaging source pixels when shrinking. Pixels outside
     * the source come out opaque black.
     *
     * @param argb source pixels, row by row
     * @return opaque ARGB pixels, outW by outH
     */
    public static int[] warp(int[] argb, int width, int height, CardQuad quad, int outW, int outH) {
        LumaImage.checkSize(argb.length, width, height);
        Homography h = Homography.fromUnitSquare(quad);
        double stepX = quad.c0().distanceTo(quad.c1()) / outW;
        double stepY = quad.c0().distanceTo(quad.c3()) / outH;
        int n = (int) Math.max(1, Math.min(5, Math.ceil(Math.max(stepX, stepY))));
        int[] out = new int[outW * outH];
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                int count = 0;
                for (int a = 0; a < n; a++) {
                    for (int d = 0; d < n; d++) {
                        double u = (x + (a + 0.5) / n) / outW;
                        double v = (y + (d + 0.5) / n) / outH;
                        int sx = (int) Math.floor(h.mapX(u, v));
                        int sy = (int) Math.floor(h.mapY(u, v));
                        if (sx >= 0 && sy >= 0 && sx < width && sy < height) {
                            int p = argb[sy * width + sx];
                            r += p >> 16 & 255;
                            g += p >> 8 & 255;
                            b += p & 255;
                            count++;
                        }
                    }
                }
                int rgb = count > 0 ? (r / count) << 16 | (g / count) << 8 | (b / count) : 0;
                out[y * outW + x] = 0xFF000000 | rgb;
            }
        }
        return out;
    }
}
