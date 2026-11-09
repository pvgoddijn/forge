package forge.cardocr.reader;

import forge.cardocr.finder.*;

/** Where a card's title sits, and how to cut text out of an image at the recogniser's height. */
public final class TitleBands {

    public static final int BAND_HEIGHT = 48;
    private static final int MAX_TITLE_WIDTH = 800;
    private static final int MIN_TITLE_WIDTH = 64;
    private static final int MAX_BOX_WIDTH = 960;
    private static final int MIN_BOX_WIDTH = 48;
    /** A mis-fitted outline can squash a band to nothing, so its height counts as at least this. */
    private static final double MIN_BAND_HEIGHT_PX = 4;

    /** A part of the card, as shares of its width and height, measured in reading direction. */
    public record Window(double x0, double x1, double y0, double y1) {
    }

    /** Narrow leaves out the mana cost and wide doesn't. High suits old frames, low suits new ones. */
    public static final Window NARROW_HIGH = new Window(.05, .82, .02, .10);
    public static final Window NARROW_LOW = new Window(.05, .82, .04, .115);
    public static final Window WIDE_HIGH = new Window(.05, .93, .02, .10);
    public static final Window WIDE_LOW = new Window(.05, .93, .04, .115);
    /** Tall enough for both frames, for reading just once. */
    public static final Window NARROW_TALL = new Window(.05, .82, .02, .115);
    public static final Window WIDE_TALL = new Window(.05, .93, .02, .115);

    /** Pixels at the recogniser's height. */
    public record Band(int[] rgb, int width, int height) {
    }

    private TitleBands() {
    }

    public static Band title(ArgbImage image, CardQuad card, Window window) {
        double cardWidth = card.c0().distanceTo(card.c1());
        double cardHeight = card.c0().distanceTo(card.c3());
        double widthPx = (window.x1() - window.x0()) * cardWidth;
        double heightPx = (window.y1() - window.y0()) * cardHeight;
        int width = (int) Math.min(MAX_TITLE_WIDTH,
                Math.max(MIN_TITLE_WIDTH, BAND_HEIGHT * widthPx / Math.max(MIN_BAND_HEIGHT_PX, heightPx)));
        return new Band(crop(image, card, window, width, BAND_HEIGHT), width, BAND_HEIGHT);
    }

    /** A detected text box, one way up or the other. */
    public static Band box(ArgbImage image, TextBox box, boolean flipped) {
        int width = Math.min(MAX_BOX_WIDTH,
                Math.max(MIN_BOX_WIDTH, (int) Math.ceil(box.length() * BAND_HEIGHT / box.thickness())));
        CardQuad quad = flipped ? box.asQuad().flipped() : box.asQuad();
        return new Band(crop(image, quad, new Window(0, 1, 0, 1), width, BAND_HEIGHT), width, BAND_HEIGHT);
    }

    /** Box-filters the window down to the given size, with black outside the image. */
    static int[] crop(ArgbImage image, CardQuad quad, Window w, int outW, int outH) {
        Homography h = Homography.fromUnitSquare(quad);
        double stepX = (w.x1() - w.x0()) * quad.c0().distanceTo(quad.c1()) / outW;
        double stepY = (w.y1() - w.y0()) * quad.c0().distanceTo(quad.c3()) / outH;
        int n = (int) Math.max(1, Math.min(5, Math.ceil(Math.max(stepX, stepY))));
        int[] src = image.pixels();
        int width = image.width();
        int height = image.height();
        int[] out = new int[outW * outH];
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                int count = 0;
                for (int a = 0; a < n; a++) {
                    double u = w.x0() + (w.x1() - w.x0()) * (x + (a + .5) / n) / outW;
                    for (int d = 0; d < n; d++) {
                        double v = w.y0() + (w.y1() - w.y0()) * (y + (d + .5) / n) / outH;
                        int sx = (int) Math.floor(h.mapX(u, v));
                        int sy = (int) Math.floor(h.mapY(u, v));
                        if (sx >= 0 && sy >= 0 && sx < width && sy < height) {
                            int p = src[sy * width + sx];
                            r += p >> 16 & 255;
                            g += p >> 8 & 255;
                            b += p & 255;
                            count++;
                        }
                    }
                }
                if (count > 0) {
                    out[y * outW + x] = (r / count) << 16 | (g / count) << 8 | b / count;
                }
            }
        }
        return out;
    }
}
