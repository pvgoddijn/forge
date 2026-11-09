package forge.cardocr.finder;

import java.util.*;

/** Draws cards with exactly known corners on a plain background, supersampled like a real lens. */
final class SyntheticCard {

    private static final int SUPERSAMPLE = 4;
    private static final double RADIUS_MM = 3;
    /** How far printed detail swings the luma of the face and the art, like art and text on a card. */
    private static final double DETAIL = 14;

    private SyntheticCard() {
    }

    /**
     * A portrait card's true corners: centred at (cx, cy), turned clockwise by degrees, with its
     * top edge shrunk by keystone (0.1 means 10 % narrower) to imitate a tilted phone.
     */
    static CardQuad corners(double cx, double cy, double height, double degrees, double keystone) {
        double halfH = height / 2;
        double halfW = halfH * 63 / 88;
        double topW = halfW * (1 - keystone);
        double[][] local = {{-topW, -halfH}, {topW, -halfH}, {halfW, halfH}, {-halfW, halfH}};
        double c = Math.cos(Math.toRadians(degrees));
        double s = Math.sin(Math.toRadians(degrees));
        Corner[] out = new Corner[4];
        for (int i = 0; i < 4; i++) {
            out[i] = new Corner(cx + local[i][0] * c - local[i][1] * s,
                    cy + local[i][0] * s + local[i][1] * c);
        }
        return new CardQuad(out[0], out[1], out[2], out[3], 1, true);
    }

    static byte[] blank(int width, int height, int background) {
        byte[] luma = new byte[width * height];
        Arrays.fill(luma, (byte) background);
        return luma;
    }

    /**
     * Paints a rounded card with a dark border, optionally with a lighter face and an art box
     * inside it, as on a real card.
     */
    static void draw(byte[] luma, int width, int height, CardQuad card, int border, int face,
            int art) {
        draw(luma, width, height, card, border, face, art, RADIUS_MM);
    }

    /** As the other draw, with a chosen corner radius in mm. A radius of 0 gives square corners. */
    static void draw(byte[] luma, int width, int height, CardQuad card, int border, int face,
            int art, double radiusMm) {
        Homography h = Homography.fromUnitSquare(card);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Corner c : card.corners()) {
            minX = Math.min(minX, c.x());
            minY = Math.min(minY, c.y());
            maxX = Math.max(maxX, c.x());
            maxY = Math.max(maxY, c.y());
        }
        int x0 = Math.max(0, (int) minX - 1);
        int y0 = Math.max(0, (int) minY - 1);
        int x1 = Math.min(width - 1, (int) maxX + 1);
        int y1 = Math.min(height - 1, (int) maxY + 1);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = y * width + x;
                double sum = 0;
                for (int a = 0; a < SUPERSAMPLE; a++) {
                    for (int b = 0; b < SUPERSAMPLE; b++) {
                        Corner uv = h.unmap(x + (a + 0.5) / SUPERSAMPLE, y + (b + 0.5) / SUPERSAMPLE);
                        sum += shade(uv.x(), uv.y(), border, face, art, luma[i] & 0xFF, radiusMm);
                    }
                }
                luma[i] = (byte) Math.round(sum / (SUPERSAMPLE * SUPERSAMPLE));
            }
        }
    }

    private static double shade(double u, double v, int border, int face, int art, int outside, double radius) {
        double xMm = u * 63;
        double yMm = v * 88;
        if (!insideRounded(xMm, yMm, radius)) {
            return outside;
        }
        if (xMm > 3 && xMm < 60 && yMm > 3 && yMm < 85) {
            boolean inArt = u > 0.08 && u < 0.92 && v > 0.115 && v < 0.55;
            // Blocks of random shade, which unlike a regular pattern don't repeat.
            int block = (int) (xMm / 4) * 31 + (int) (yMm / 4) * 977;
            double detail = DETAIL * (new Random(block).nextDouble() * 2 - 1);
            return (inArt ? art : face) + detail;
        }
        return border;
    }

    private static boolean insideRounded(double xMm, double yMm, double radius) {
        if (xMm < 0 || yMm < 0 || xMm > 63 || yMm > 88) {
            return false;
        }
        double dx = Math.max(0, Math.max(radius - xMm, xMm - (63 - radius)));
        double dy = Math.max(0, Math.max(radius - yMm, yMm - (88 - radius)));
        return dx * dx + dy * dy <= radius * radius;
    }

    /** The worst corner distance, allowing the 180 degree turn that outlines can't resolve. */
    static double cornerError(CardQuad found, CardQuad truth) {
        return Math.min(found.maxCornerDistance(truth), found.maxCornerDistance(truth.flipped()));
    }
}
