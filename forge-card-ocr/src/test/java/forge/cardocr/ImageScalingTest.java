package forge.cardocr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ImageScalingTest {

    @Test
    void smallImageIsNotDownsampled() {
        assertEquals(1, ImageScaling.sampleSize(new Dim(1600, 1200), 2048));
    }

    @Test
    void largeImageStaysAtLeastTheRequestedSize() {
        assertEquals(2, ImageScaling.sampleSize(new Dim(8000, 6000), 2048 + 1));
        assertEquals(4, ImageScaling.sampleSize(new Dim(8192, 6144), 2048));
    }

    @Test
    void mapsExifOrientationToRotation() {
        assertEquals(0, ImageScaling.rotationDegrees(1));
        assertEquals(180, ImageScaling.rotationDegrees(3));
        assertEquals(90, ImageScaling.rotationDegrees(6));
        assertEquals(270, ImageScaling.rotationDegrees(8));
        assertEquals(0, ImageScaling.rotationDegrees(0));
    }
}
