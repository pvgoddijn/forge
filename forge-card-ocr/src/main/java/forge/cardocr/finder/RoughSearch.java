package forge.cardocr.finder;

import java.util.*;

/**
 * Finds rough 63x88 rectangles at any angle. It picks the image's strongest edge directions, turns
 * the image to each one so card edges run along the axes, and slides rectangles of every size over
 * running totals of edge pixels. The stride, dilation and size step suit a search image with a long
 * side of about 480.
 *
 * <p>Keeps scratch buffers between calls to avoid garbage on a preview stream, so one instance
 * must only be used by one thread at a time.
 */
final class RoughSearch {

    private static final double CARD_RATIO = 63.0 / 88.0;
    /** Slide step in pixels. */
    private static final int STRIDE = 2;
    /** Edge pixels count this far across a rectangle side, which tolerates slight perspective. */
    private static final int EDGE_DILATION = 2;
    /** Every side of a rectangle must be at least this much edge. */
    private static final double MIN_SIDE_COVERAGE = 0.35;
    private static final int SIZE_BANDS = 2;
    private static final double SIZE_STEP = 1.035;
    private static final double MIN_LONG_SIDE_OF_SHORT_IMAGE_SIDE = 0.22;
    private static final double MAX_LONG_SIDE_OF_LONG_IMAGE_SIDE = 1.15;
    /** Two candidates closer than this, as a share of the image's long side, are duplicates. */
    private static final double DUPLICATE_DISTANCE = 25.0 / 4160;
    /** The art box spans this share of the card's width, centred at this height of the card. */
    private static final double ART_WIDTH = 0.84;
    private static final double ART_CENTRE_Y = 0.3325;
    private static final double ART_HYPOTHESIS_PENALTY = 0.8;

    private float[] magnitude = new float[0];
    private float[] angleX = new float[0];
    private float[] angleY = new float[0];
    private float[] rotated = new float[0];
    private boolean[] horizontalEdge = new boolean[0];
    private boolean[] verticalEdge = new boolean[0];
    private int[] horizontalTotals = new int[0];
    private int[] verticalTotals = new int[0];
    private int[] latestEdgeRow = new int[0];
    private int[] viableColumns = new int[0];

    /** Rough quads in the image's coordinates, best first. */
    List<CardQuad> search(LumaImage image, int maxAngles, int maxCandidates) {
        float threshold = computeGradients(image);
        List<CardQuad> all = new ArrayList<>();
        for (double degrees : strongestAngles(image, threshold, maxAngles)) {
            searchAtAngle(image, Math.toRadians(degrees), threshold / 2, all);
        }
        all.sort((a, b) -> Double.compare(b.score(), a.score()));

        double duplicate = DUPLICATE_DISTANCE * Math.max(image.width(), image.height());
        List<CardQuad> kept = new ArrayList<>();
        for (CardQuad quad : all) {
            if (kept.size() >= maxCandidates) {
                break;
            }
            if (!isDuplicate(quad, kept, duplicate)) {
                kept.add(quad);
            }
        }
        return kept;
    }

    private static boolean isDuplicate(CardQuad quad, List<CardQuad> kept, double limit) {
        for (CardQuad other : kept) {
            if (quad.c0().distanceTo(other.c0()) < limit
                    && sideDifference(quad.c0(), quad.c1(), other.c0(), other.c1()) < limit
                    && sideDifference(quad.c0(), quad.c3(), other.c0(), other.c3()) < limit) {
                return true;
            }
        }
        return false;
    }

    private static double sideDifference(Corner a0, Corner a1, Corner b0, Corner b1) {
        return Math.hypot((a1.x() - a0.x()) - (b1.x() - b0.x()),
                (a1.y() - a0.y()) - (b1.y() - b0.y()));
    }

    /** Fills the Sobel buffers and returns the magnitude of the image's top 10 % of edges. */
    private float computeGradients(LumaImage image) {
        int w = image.width();
        int h = image.height();
        int n = w * h;
        if (magnitude.length < n) {
            magnitude = new float[n];
            angleX = new float[n];
            angleY = new float[n];
        }
        float[] g = image.pixels();
        // A Sobel magnitude stays below 1500 for 8-bit input, so whole-number bins are enough for
        // the percentile and much cheaper than sorting every pixel on every frame.
        int[] histogram = new int[1500];
        Arrays.fill(magnitude, 0, n, 0);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = g[i + 1 + w] + 2 * g[i + 1] + g[i + 1 - w]
                        - g[i - 1 + w] - 2 * g[i - 1] - g[i - 1 - w];
                float gy = g[i + w + 1] + 2 * g[i + w] + g[i + w - 1]
                        - g[i - w + 1] - 2 * g[i - w] - g[i - w - 1];
                float m = (float) Math.sqrt(gx * gx + gy * gy);
                angleX[i] = gx;
                angleY[i] = gy;
                magnitude[i] = m;
                histogram[Math.min(histogram.length - 1, (int) m)]++;
            }
        }
        int rank = (int) (n * 0.90);
        int seen = n - (w - 2) * (h - 2); // border pixels were left at zero
        int percentile = 0;
        for (int bin = 0; bin < histogram.length; bin++) {
            seen += histogram[bin];
            if (seen > rank) {
                percentile = bin;
                break;
            }
        }
        return Math.max(8, percentile);
    }

    /** Up to maxAngles directions in 0-89 degrees, strongest first and at least 2.5 degrees apart. */
    private double[] strongestAngles(LumaImage image, float threshold, int maxAngles) {
        int n = image.width() * image.height();
        double[] histogram = new double[90];
        for (int i = 0; i < n; i++) {
            if (magnitude[i] > threshold) {
                double degrees = Math.toDegrees(Math.atan2(angleY[i], angleX[i]));
                degrees = ((degrees % 90) + 90) % 90;
                histogram[(int) degrees % 90]++;
            }
        }
        double[] smoothed = new double[90];
        for (int i = 0; i < 90; i++) {
            smoothed[i] = histogram[(i + 89) % 90] + 2 * histogram[i] + histogram[(i + 1) % 90];
        }
        double[] chosen = new double[maxAngles];
        int count = 0;
        boolean[] used = new boolean[90];
        for (int round = 0; round < 90 && count < maxAngles; round++) {
            int best = -1;
            for (int i = 0; i < 90; i++) {
                if (!used[i] && (best < 0 || smoothed[i] > smoothed[best])) {
                    best = i;
                }
            }
            used[best] = true;
            if (!isCloseToAny(best, chosen, count)) {
                chosen[count++] = best;
            }
        }
        return Arrays.copyOf(chosen, count);
    }

    private static boolean isCloseToAny(int degrees, double[] chosen, int count) {
        for (int i = 0; i < count; i++) {
            double difference = Math.abs(((degrees - chosen[i]) % 90 + 135) % 90 - 45);
            if (difference < 2.5) {
                return true;
            }
        }
        return false;
    }

    private void searchAtAngle(LumaImage image, double angle, float threshold, List<CardQuad> out) {
        Rotation rotation = Rotation.of(image, angle);
        rotate(image, rotation);
        findOrientedEdges(rotation, threshold);
        buildRunningTotals(rotation);

        int minSide = Math.min(image.width(), image.height());
        int maxSide = Math.max(image.width(), image.height());
        // The best rectangle in each band of sizes, so a strong large shape can't hide a card.
        Placement[] portrait = new Placement[SIZE_BANDS];
        Placement[] landscape = new Placement[SIZE_BANDS];
        double minLong = MIN_LONG_SIDE_OF_SHORT_IMAGE_SIDE * minSide;
        double maxLong = MAX_LONG_SIDE_OF_LONG_IMAGE_SIDE * maxSide;
        double bandWidth = Math.log(maxLong / minLong) / SIZE_BANDS;
        for (double size = minLong; size <= maxLong; size *= SIZE_STEP) {
            int band = Math.min(SIZE_BANDS - 1, (int) (Math.log(size / minLong) / bandWidth));
            int shortSide = (int) (size * CARD_RATIO);
            int longSide = (int) size;
            portrait[band] = better(portrait[band], bestPlacement(rotation, shortSide, longSide, image));
            landscape[band] = better(landscape[band], bestPlacement(rotation, longSide, shortSide, image));
        }
        for (int band = 0; band < SIZE_BANDS; band++) {
            if (portrait[band] != null) {
                addPortrait(portrait[band], rotation, out);
            }
            if (landscape[band] != null) {
                addLandscape(landscape[band], rotation, out);
            }
        }
    }

    private static Placement better(Placement current, Placement other) {
        if (other == null) {
            return current;
        }
        return current == null || other.score > current.score ? other : current;
    }

    /** Fills the rotated buffer. Pixels from outside the source image are -1. */
    private void rotate(LumaImage image, Rotation rotation) {
        int nw = rotation.width;
        int nh = rotation.height;
        int n = nw * nh;
        if (rotated.length < n) {
            rotated = new float[n];
        }
        int w = image.width();
        int h = image.height();
        float[] g = image.pixels();
        for (int y = 0; y < nh; y++) {
            double v = y - rotation.centreY;
            // Walk along the row incrementally: this is the search's hottest setup loop.
            double sx = rotation.sourceCentreX - rotation.centreX * rotation.cos - v * rotation.sin;
            double sy = rotation.sourceCentreY - rotation.centreX * rotation.sin + v * rotation.cos;
            int row = y * nw;
            for (int x = 0; x < nw; x++) {
                if (sx >= 0 && sy >= 0 && sx < w - 1 && sy < h - 1) {
                    int ix = (int) sx;
                    int iy = (int) sy;
                    float fx = (float) (sx - ix);
                    float fy = (float) (sy - iy);
                    int i = iy * w + ix;
                    rotated[row + x] = g[i] * (1 - fx) * (1 - fy) + g[i + 1] * fx * (1 - fy)
                            + g[i + w] * (1 - fx) * fy + g[i + w + 1] * fx * fy;
                } else {
                    rotated[row + x] = -1;
                }
                sx += rotation.cos;
                sy += rotation.sin;
            }
        }
    }

    private void findOrientedEdges(Rotation rotation, float threshold) {
        int nw = rotation.width;
        int nh = rotation.height;
        int n = nw * nh;
        if (horizontalEdge.length < n) {
            horizontalEdge = new boolean[n];
            verticalEdge = new boolean[n];
        }
        Arrays.fill(horizontalEdge, 0, n, false);
        Arrays.fill(verticalEdge, 0, n, false);
        float[] r = rotated;
        int up = 2 * nw;
        for (int y = 2; y < nh - 2; y++) {
            for (int x = 2; x < nw - 2; x++) {
                int i = y * nw + x;
                if (r[i] < 0 || r[i - up] < 0 || r[i + up] < 0 || r[i - 2] < 0 || r[i + 2] < 0) {
                    continue;
                }
                float across = Math.abs(r[i + up] + r[i + up + 1] + r[i + up - 1]
                        - r[i - up] - r[i - up + 1] - r[i - up - 1]);
                float along = Math.abs(r[i + 2] + r[i + 2 + nw] + r[i + 2 - nw]
                        - r[i - 2] - r[i - 2 + nw] - r[i - 2 - nw]);
                if (across > threshold && across > along) {
                    horizontalEdge[i] = true;
                }
                if (along > threshold && along > across) {
                    verticalEdge[i] = true;
                }
            }
        }
    }

    /**
     * Running totals of edge pixels, both stored row by row. horizontalTotals[y * (nw + 1) + x]
     * counts edge pixels in row y left of x, and verticalTotals[y * nw + x] counts them in column x
     * above y. An edge pixel counts for all rows or columns within the dilation distance. One pass
     * over each row is enough, since only the latest edge within reach matters.
     */
    private void buildRunningTotals(Rotation rotation) {
        int nw = rotation.width;
        int nh = rotation.height;
        int rowLength = nw + 1;
        if (horizontalTotals.length < nh * rowLength) {
            horizontalTotals = new int[nh * rowLength];
        }
        if (verticalTotals.length < (nh + 1) * nw) {
            verticalTotals = new int[(nh + 1) * nw];
        }
        if (latestEdgeRow.length < nw) {
            latestEdgeRow = new int[nw];
        }
        int[] latestRow = latestEdgeRow;
        Arrays.fill(latestRow, 0, nw, Integer.MIN_VALUE / 2);
        // Rows above the window can't hold an edge for row 0, but the first rows of it can.
        for (int y = 0; y < EDGE_DILATION && y < nh; y++) {
            int row = y * nw;
            for (int x = 0; x < nw; x++) {
                if (horizontalEdge[row + x]) {
                    latestRow[x] = y;
                }
            }
        }
        Arrays.fill(verticalTotals, 0, nw, 0);
        for (int y = 0; y < nh; y++) {
            int total = 0;
            int base = y * rowLength;
            horizontalTotals[base] = 0;
            int newest = y + EDGE_DILATION;
            boolean hasNewest = newest < nh;
            int newestRow = newest * nw;
            int oldest = y - EDGE_DILATION;
            for (int x = 0; x < nw; x++) {
                if (hasNewest && horizontalEdge[newestRow + x]) {
                    latestRow[x] = newest;
                }
                if (latestRow[x] >= oldest) {
                    total++;
                }
                horizontalTotals[base + x + 1] = total;
            }
            int row = y * nw;
            int next = row + nw;
            int latestColumn = Integer.MIN_VALUE / 2;
            for (int x = 0; x < EDGE_DILATION && x < nw; x++) {
                if (verticalEdge[row + x]) {
                    latestColumn = x;
                }
            }
            for (int x = 0; x < nw; x++) {
                int ahead = x + EDGE_DILATION;
                if (ahead < nw && verticalEdge[row + ahead]) {
                    latestColumn = ahead;
                }
                verticalTotals[next + x] = verticalTotals[row + x]
                        + (latestColumn >= x - EDGE_DILATION ? 1 : 0);
            }
        }
    }

    /**
     * The best position for one rectangle size, or null if no position has all four sides. Most
     * rows and columns can't reach the coverage a side needs, so those are skipped outright. That
     * prunes only positions that would be rejected anyway.
     */
    private Placement bestPlacement(Rotation rotation, int ww, int hh, LumaImage image) {
        int nw = rotation.width;
        int nh = rotation.height;
        if (ww >= nw - 8 || hh >= nh - 8) {
            return null;
        }
        int w = image.width();
        int h = image.height();
        int rowLength = nw + 1;
        int minAcross = (int) Math.ceil(MIN_SIDE_COVERAGE * ww);
        int minDown = (int) Math.ceil(MIN_SIDE_COVERAGE * hh);
        int[] hTotals = horizontalTotals;
        int[] vTotals = verticalTotals;

        // Columns that have enough edge pixels for both of a position's vertical sides.
        if (viableColumns.length < nw) {
            viableColumns = new int[nw];
        }
        int[] columns = viableColumns;
        int columnCount = 0;
        int columnTotals = nh * nw;
        for (int x = EDGE_DILATION; x + ww < nw - EDGE_DILATION; x += STRIDE) {
            if (vTotals[columnTotals + x] >= minDown && vTotals[columnTotals + x + ww] >= minDown) {
                columns[columnCount++] = x;
            }
        }

        double halfDiagonal = Math.hypot(w, h) / 2;
        double sizeFactor = 0.85 + 0.15 * Math.min(1, (double) Math.max(ww, hh) / Math.max(w, h) * 2);
        double bestScore = 0;
        int bestX = 0;
        int bestY = 0;
        for (int y = EDGE_DILATION; y + hh < nh - EDGE_DILATION; y += STRIDE) {
            int topTotals = y * rowLength;
            int bottomTotals = (y + hh) * rowLength;
            if (hTotals[topTotals + nw] < minAcross || hTotals[bottomTotals + nw] < minAcross) {
                continue;
            }
            int aboveTop = y * nw;
            int aboveBottom = (y + hh) * nw;
            for (int c = 0; c < columnCount; c++) {
                int x = columns[c];
                int t = hTotals[topTotals + x + ww] - hTotals[topTotals + x];
                if (t < minAcross) {
                    continue;
                }
                int b = hTotals[bottomTotals + x + ww] - hTotals[bottomTotals + x];
                if (b < minAcross) {
                    continue;
                }
                int l = vTotals[aboveBottom + x] - vTotals[aboveTop + x];
                if (l < minDown) {
                    continue;
                }
                int r = vTotals[aboveBottom + x + ww] - vTotals[aboveTop + x + ww];
                if (r < minDown) {
                    continue;
                }
                if (rotated[aboveTop + x] < 0 || rotated[aboveBottom + x] < 0
                        || rotated[aboveTop + x + ww] < 0 || rotated[aboveBottom + x + ww] < 0) {
                    continue;
                }
                double top = t / (double) ww;
                double bottom = b / (double) ww;
                double left = l / (double) hh;
                double right = r / (double) hh;
                double weakest = Math.min(Math.min(top, bottom), Math.min(left, right));
                double fromCentre = Math.hypot(x + ww / 2.0 - rotation.centreX,
                        y + hh / 2.0 - rotation.centreY) / halfDiagonal;
                double score = (top + bottom + left + right) / 4 * (0.5 + 0.5 * weakest)
                        * (1 - 0.45 * fromCentre) * sizeFactor;
                if (score > bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return bestScore > 0 ? new Placement(bestScore, bestX, bestY, ww, hh) : null;
    }

    private static void addPortrait(Placement p, Rotation rotation, List<CardQuad> out) {
        Corner origin = rotation.toSource(p.x, p.y);
        Corner right = rotation.toSource(p.x + p.width, p.y);
        Corner down = rotation.toSource(p.x, p.y + p.height);
        out.add(frame(origin, sub(right, origin), sub(down, origin), p.score));
    }

    /** A landscape rectangle is either a sideways card or the art box of an upright card. */
    private static void addLandscape(Placement p, Rotation rotation, List<CardQuad> out) {
        Corner origin = rotation.toSource(p.x, p.y);
        Corner right = sub(rotation.toSource(p.x + p.width, p.y), origin);
        Corner down = sub(rotation.toSource(p.x, p.y + p.height), origin);

        Corner bottomLeft = new Corner(origin.x() + down.x(), origin.y() + down.y());
        out.add(frame(bottomLeft, new Corner(-down.x(), -down.y()), right, p.score));

        Corner width = right.scaled(1 / ART_WIDTH);
        double cardHeight = Math.hypot(width.x(), width.y()) * 88 / 63;
        Corner height = down.scaled(cardHeight / Math.hypot(down.x(), down.y()));
        double centreX = origin.x() + (right.x() + down.x()) / 2;
        double centreY = origin.y() + (right.y() + down.y()) / 2;
        double offsetX = width.x() / 2 + height.x() * ART_CENTRE_Y;
        double offsetY = width.y() / 2 + height.y() * ART_CENTRE_Y;
        double score = p.score * ART_HYPOTHESIS_PENALTY;
        out.add(frame(new Corner(centreX - offsetX, centreY - offsetY), width, height, score));
        // The same art box on a card that lies the other way up.
        out.add(frame(new Corner(centreX + offsetX, centreY + offsetY),
                width.scaled(-1), height.scaled(-1), score));
    }

    private static Corner sub(Corner a, Corner b) {
        return new Corner(a.x() - b.x(), a.y() - b.y());
    }

    /** A parallelogram from an origin and the short and long side vectors. */
    private static CardQuad frame(Corner origin, Corner shortSide, Corner longSide, double score) {
        Corner c1 = new Corner(origin.x() + shortSide.x(), origin.y() + shortSide.y());
        Corner c3 = new Corner(origin.x() + longSide.x(), origin.y() + longSide.y());
        Corner c2 = new Corner(c1.x() + longSide.x(), c1.y() + longSide.y());
        return new CardQuad(origin, c1, c2, c3, score, false);
    }

    private record Placement(double score, int x, int y, int width, int height) {
    }

    private static final class Rotation {
        final double cos;
        final double sin;
        final int width;
        final int height;
        final double centreX;
        final double centreY;
        final double sourceCentreX;
        final double sourceCentreY;

        private Rotation(double cos, double sin, int width, int height, LumaImage source) {
            this.cos = cos;
            this.sin = sin;
            this.width = width;
            this.height = height;
            centreX = width / 2.0;
            centreY = height / 2.0;
            sourceCentreX = source.width() / 2.0;
            sourceCentreY = source.height() / 2.0;
        }

        static Rotation of(LumaImage source, double angle) {
            double c = Math.cos(angle);
            double s = Math.sin(angle);
            int w = (int) (Math.abs(source.width() * c) + Math.abs(source.height() * s)) + 2;
            int h = (int) (Math.abs(source.width() * s) + Math.abs(source.height() * c)) + 2;
            return new Rotation(c, s, w, h, source);
        }

        Corner toSource(double x, double y) {
            double u = x - centreX;
            double v = y - centreY;
            return new Corner(sourceCentreX + u * cos - v * sin, sourceCentreY + u * sin + v * cos);
        }
    }
}
