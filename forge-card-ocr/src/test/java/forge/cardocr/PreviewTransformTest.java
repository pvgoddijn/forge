package forge.cardocr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PreviewTransformTest {

    private static final float DELTA = 0.0001f;

    /** Whatever the sizes, the shown frame must keep the rotated stream's ratio and cover the view. */
    private static void assertFillsUndistorted(Dim stream, int sensor, int viewW, int viewH) {
        PreviewTransform.Scale scale = PreviewTransform.centerCrop(stream, sensor, viewW, viewH);
        double shownW = viewW * scale.x();
        double shownH = viewH * scale.y();
        double expectedRatio = sensor % 180 == 0
                ? (double) stream.width() / stream.height()
                : (double) stream.height() / stream.width();
        assertEquals(expectedRatio, shownW / shownH, 0.0001);
        assertTrue(shownW >= viewW - 0.01 && shownH >= viewH - 0.01);
        assertTrue(Math.min(scale.x(), scale.y()) == 1f, "One axis must fit exactly");
    }

    @Test
    void tallPhoneWithSensorAt90CropsTheSides() {
        PreviewTransform.Scale scale = PreviewTransform.centerCrop(new Dim(1920, 1080), 90, 1080, 2400);
        assertEquals(1.25f, scale.x(), DELTA);
        assertEquals(1f, scale.y(), DELTA);
    }

    @Test
    void matchingRatioNeedsNoScaling() {
        PreviewTransform.Scale scale = PreviewTransform.centerCrop(new Dim(1920, 1080), 270, 1080, 1920);
        assertEquals(1f, scale.x(), DELTA);
        assertEquals(1f, scale.y(), DELTA);
    }

    @Test
    void fillsWithoutDistortionForCommonCombinations() {
        for (int sensor : new int[] {0, 90, 180, 270}) {
            for (Dim stream : new Dim[] {new Dim(1920, 1080), new Dim(1440, 1080), new Dim(640, 480)}) {
                assertFillsUndistorted(stream, sensor, 1080, 2400);
                assertFillsUndistorted(stream, sensor, 720, 1280);
                assertFillsUndistorted(stream, sensor, 1600, 1600);
            }
        }
    }

    @Test
    void unmeasuredViewGetsIdentity() {
        assertEquals(new PreviewTransform.Scale(1f, 1f), PreviewTransform.centerCrop(new Dim(1920, 1080), 90, 0, 0));
    }
}
