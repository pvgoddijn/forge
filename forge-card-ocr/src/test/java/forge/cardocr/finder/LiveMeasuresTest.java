package forge.cardocr.finder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

/** Glare, sharpness and signature on cards with exactly known corners. */
class LiveMeasuresTest {

    private static final int W = 640;
    private static final int H = 480;
    private static final CardQuad CARD = SyntheticCard.corners(320, 240, 300, 0, 0);

    /** A mid-grey card face with fine random texture. */
    private static byte[] texturedCard(long seed) {
        Random random = new Random(seed);
        byte[] luma = SyntheticCard.blank(W, H, 60);
        Homography h = Homography.fromUnitSquare(CARD);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                Corner uv = h.unmap(x + 0.5, y + 0.5);
                if (uv.x() >= 0 && uv.x() <= 1 && uv.y() >= 0 && uv.y() <= 1) {
                    luma[y * W + x] = (byte) (100 + random.nextInt(60));
                }
            }
        }
        return luma;
    }

    /** Paints part of the card, in flattened coordinates, with one value. */
    private static void paint(byte[] luma, CardQuad card, double u0, double v0, double u1, double v1, int value) {
        Homography h = Homography.fromUnitSquare(card);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                Corner uv = h.unmap(x + 0.5, y + 0.5);
                if (uv.x() >= u0 && uv.x() <= u1 && uv.y() >= v0 && uv.y() <= v1) {
                    luma[y * W + x] = (byte) value;
                }
            }
        }
    }

    private static byte[] boxBlurred(byte[] in) {
        byte[] out = in.clone();
        for (int y = 1; y < H - 1; y++) {
            for (int x = 1; x < W - 1; x++) {
                int sum = 0;
                for (int a = -1; a <= 1; a++) {
                    for (int b = -1; b <= 1; b++) {
                        sum += in[(y + a) * W + x + b] & 0xFF;
                    }
                }
                out[y * W + x] = (byte) (sum / 9);
            }
        }
        return out;
    }

    @Test
    void noGlareOnAnOrdinaryCard() {
        assertFalse(GlareDetector.measure(texturedCard(1), W, H, CARD).warns());
    }

    @Test
    void warnsOnGlareAtEitherEndBecauseOrientationIsUnknown() {
        byte[] top = texturedCard(1);
        paint(top, CARD, 0.2, 0.0, 0.8, 0.12, 255);
        assertTrue(GlareDetector.measure(top, W, H, CARD).warns());
        byte[] bottom = texturedCard(1);
        paint(bottom, CARD, 0.2, 0.88, 0.8, 1.0, 255);
        assertTrue(GlareDetector.measure(bottom, W, H, CARD).warns());
    }

    @Test
    void glareOutsideTheTitleBandsIsIgnored() {
        byte[] middle = texturedCard(1);
        paint(middle, CARD, 0, 0.2, 1, 0.8, 255);
        assertEquals(0, GlareDetector.measure(middle, W, H, CARD).worstShare());
    }

    @Test
    void aSmallHighlightStaysBelowTheShare() {
        byte[] luma = texturedCard(1);
        paint(luma, CARD, 0.5, 0.05, 0.52, 0.08, 255);
        assertFalse(GlareDetector.measure(luma, W, H, CARD).warns());
    }

    @Test
    void glareFollowsAPerspectiveOutline() {
        CardQuad tilted = SyntheticCard.corners(320, 240, 300, 33, 0.08);
        byte[] luma = SyntheticCard.blank(W, H, 100);
        paint(luma, tilted, 0, 0, 1, 0.12, 255);
        assertTrue(GlareDetector.measure(luma, W, H, tilted).worstShare() > 0.9);
    }

    @Test
    void aBandMostlyOutOfFrameCannotWarn() {
        CardQuad offTop = SyntheticCard.corners(320, 100, 300, 0, 0);
        byte[] luma = SyntheticCard.blank(W, H, 255);
        paint(luma, offTop, 0, 0.5, 1, 1, 100);
        assertEquals(0, GlareDetector.measure(luma, W, H, offTop).worstShare());
    }

    @Test
    void blurLowersSharpness() {
        byte[] sharp = texturedCard(2);
        byte[] blurred = boxBlurred(sharp);
        double a = CardSharpness.laplacianVariance(sharp, W, H, CARD);
        double b = CardSharpness.laplacianVariance(blurred, W, H, CARD);
        assertTrue(a > 5 * b, a + " vs " + b);
    }

    @Test
    void sharpnessOnlyLooksInsideTheCard() {
        byte[] plainCard = SyntheticCard.blank(W, H, 128);
        Random random = new Random(3);
        for (int i = 0; i < plainCard.length; i++) {
            plainCard[i] = (byte) random.nextInt(256);
        }
        paint(plainCard, CARD, 0, 0, 1, 1, 128);
        assertEquals(0, CardSharpness.laplacianVariance(plainCard, W, H, CARD), 1e-9);
        assertTrue(CardSharpness.laplacianVariance(plainCard, W, H, null) > 100);
    }

    @Test
    void sharpnessMatchesAHandComputedValue() {
        // A single bright pixel: Laplacian -400 there, +100 at four neighbours, 0 elsewhere.
        byte[] luma = new byte[49];
        luma[24] = 100;
        // 25 inner pixels, with mean 0.
        assertEquals((160000.0 + 4 * 10000) / 25, CardSharpness.laplacianVariance(luma, 7, 7, null), 1e-6);
    }

    @Test
    void tooFewSamplesGiveZero() {
        assertEquals(0, CardSharpness.laplacianVariance(new byte[9], 3, 3, null));
    }

    @Test
    void signatureSameCardIsCloseEvenAfterMovingTurningOrExposure() {
        byte[] first = SyntheticCard.blank(W, H, 200);
        SyntheticCard.draw(first, W, H, CARD, 30, 150, 60);
        float[] a = CardSignature.of(first, W, H, CARD);

        CardQuad moved = SyntheticCard.corners(300, 250, 280, 12, 0);
        byte[] second = SyntheticCard.blank(W, H, 200);
        SyntheticCard.draw(second, W, H, moved, 30, 150, 60);
        assertTrue(CardSignature.distance(a, CardSignature.of(second, W, H, moved)) < 5);
        assertTrue(CardSignature.distance(a, CardSignature.of(second, W, H, moved.flipped())) < 5);

        byte[] brighter = first.clone();
        for (int i = 0; i < brighter.length; i++) {
            brighter[i] = (byte) ((brighter[i] & 0xFF) + 30 > 255 ? 255 : (brighter[i] & 0xFF) + 30);
        }
        assertTrue(CardSignature.distance(a, CardSignature.of(brighter, W, H, CARD)) < 3);
    }

    @Test
    void signatureDifferentCardIsFar() {
        byte[] first = SyntheticCard.blank(W, H, 200);
        SyntheticCard.draw(first, W, H, CARD, 30, 150, 60);
        byte[] second = SyntheticCard.blank(W, H, 200);
        SyntheticCard.draw(second, W, H, CARD, 30, 60, 170);
        double distance = CardSignature.distance(CardSignature.of(first, W, H, CARD), CardSignature.of(second, W, H, CARD));
        assertTrue(distance > 30, "distance " + distance);
    }

    @Test
    void signatureNeedsTheCardInFrame() {
        assertNull(CardSignature.of(new byte[W * H], W, H, SyntheticCard.corners(40, 240, 300, 0, 0)));
    }
}
