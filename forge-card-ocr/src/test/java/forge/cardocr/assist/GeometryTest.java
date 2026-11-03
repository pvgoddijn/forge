package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.Dim;
import forge.cardocr.finder.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

/** Coordinate mapping, with expected values worked out by hand on a 4000x3000 sensor. */
class GeometryTest {

    /** The preview scale is a float. */
    private static final double D = 1e-3;
    private static final ActiveRect FULL = new ActiveRect(0, 0, 4000, 3000);
    private static final Dim ANALYSIS = new Dim(640, 480);
    private static final Dim WIDE_PREVIEW = new Dim(1920, 1080);

    private static void assertCorner(double x, double y, Corner c) {
        assertEquals(x, c.x(), D, "x");
        assertEquals(y, c.y(), D, "y");
    }

    private static CardQuad quad(double l, double t, double r, double b) {
        return new CardQuad(new Corner(l, t), new Corner(r, t), new Corner(r, b), new Corner(l, b), 1, true);
    }

    @Test
    void sameRatioStreamShowsTheWholeCrop() {
        assertEquals(FULL, SensorGeometry.visibleArea(ANALYSIS, FULL));
        assertCorner(4000, 3000, SensorGeometry.streamToActive(new Corner(640, 480), ANALYSIS, FULL));
    }

    @Test
    void widerStreamIsCutTopAndBottom() {
        assertEquals(new ActiveRect(0, 375, 4000, 2250), SensorGeometry.visibleArea(WIDE_PREVIEW, FULL));
    }

    @Test
    void narrowerStreamIsCutAtTheSides() {
        assertEquals(new ActiveRect(500, 0, 3000, 3000), SensorGeometry.visibleArea(new Dim(1000, 1000), FULL));
    }

    @Test
    void zoomCropMovesActiveCoordinates() {
        ActiveRect zoom = new ActiveRect(1000, 750, 2000, 1500);
        assertCorner(1000, 750, SensorGeometry.streamToActive(new Corner(0, 0), ANALYSIS, zoom));
        assertCorner(2000, 1500, SensorGeometry.streamToActive(new Corner(320, 240), ANALYSIS, zoom));
    }

    @Test
    void offCentreCropIsRespected() {
        ActiveRect crop = new ActiveRect(100, 200, 2000, 1500);
        assertCorner(100, 200, SensorGeometry.streamToActive(new Corner(0, 0), ANALYSIS, crop));
    }

    @Test
    void activeAndStreamAreInverses() {
        Corner p = new Corner(123, 77);
        ActiveRect crop = new ActiveRect(300, 100, 3000, 2000);
        Corner back = SensorGeometry.activeToStream(SensorGeometry.streamToActive(p, WIDE_PREVIEW, crop), WIDE_PREVIEW, crop);
        assertCorner(123, 77, back);
    }

    @Test
    void streamToStreamAccountsForDifferentRatios() {
        // Analysis top centre is active (2000, 0), above the 16:9 stream's top edge at 375.
        assertCorner(960, -180, SensorGeometry.streamToStream(new Corner(320, 0), ANALYSIS, WIDE_PREVIEW, FULL));
    }

    @ParameterizedTest
    @CsvSource({"0,10,20", "90,60,10", "180,90,60", "270,20,90"})
    void rotatesClockwiseInA100by80Image(int degrees, double x, double y) {
        assertCorner(x, y, SensorGeometry.rotated(new Corner(10, 20), new Dim(100, 80), degrees));
    }

    @Test
    void rejectsOtherAngles() {
        assertThrows(IllegalArgumentException.class, () -> SensorGeometry.rotated(new Corner(0, 0), new Dim(1, 1), 45));
    }

    @Test
    void rotatedSizeSwapsOnlyForQuarterTurns() {
        assertEquals(new Dim(3, 4), SensorGeometry.rotatedSize(new Dim(4, 3), 90));
        assertEquals(new Dim(4, 3), SensorGeometry.rotatedSize(new Dim(4, 3), 180));
    }

    private static ViewMapper mapper(Dim preview, int sensor, boolean mirrored, int vw, int vh) {
        return new ViewMapper(preview, ANALYSIS, FULL, sensor, mirrored, vw, vh);
    }

    @Test
    void sensorCentreIsViewCentre() {
        for (int sensor : new int[] {0, 90, 180, 270}) {
            assertCorner(540, 1200, mapper(new Dim(1440, 1080), sensor, false, 1080, 2400).toView(new Corner(320, 240)));
        }
    }

    /**
     * 4:3 preview, sensor at 90, view 1080x2400. Upright frame is 3:4 and fills the height, so
     * it's 1800 wide, overflowing 360 on each side.
     */
    @Test
    void tallPhoneSensor90MapsCornersWithSideCrop() {
        ViewMapper m = mapper(new Dim(1440, 1080), 90, false, 1080, 2400);
        // Sensor top-left turns to the upright image's top right.
        assertCorner(1440, 0, m.toView(new Corner(0, 0)));
        // Sensor top-right turns to bottom right.
        assertCorner(1440, 2400, m.toView(new Corner(640, 0)));
        // Sensor bottom-left turns to top left.
        assertCorner(-360, 0, m.toView(new Corner(0, 480)));
    }

    @Test
    void sensor270IsTheOppositeTurn() {
        ViewMapper m = mapper(new Dim(1440, 1080), 270, false, 1080, 2400);
        assertCorner(-360, 2400, m.toView(new Corner(0, 0)));
        assertCorner(1440, 0, m.toView(new Corner(640, 480)));
    }

    @Test
    void mirroredPreviewSwapsLeftAndRight() {
        ViewMapper m = mapper(new Dim(1440, 1080), 270, true, 1080, 2400);
        assertCorner(1440, 2400, m.toView(new Corner(0, 0)));
    }

    @Test
    void widerPreviewShiftsAnalysisPoints() {
        // 16:9 preview on a view of its own upright size: no crop transform at all. Analysis's top
        // edge lies above the preview's, which after turning 90 is beyond its right edge.
        ViewMapper m = mapper(WIDE_PREVIEW, 90, false, 1080, 1920);
        assertCorner(1260, 960, m.toView(new Corner(320, 0)));
    }

    @Test
    void quadMapsAllCornersInOrder() {
        ViewMapper m = mapper(new Dim(1440, 1080), 90, false, 1080, 2400);
        var corners = m.toView(quad(0, 0, 640, 480));
        assertEquals(4, corners.size());
        assertCorner(1440, 0, corners.get(0));
        assertCorner(1440, 2400, corners.get(1));
        assertCorner(-360, 2400, corners.get(2));
    }

    @Test
    void meteringRegionShrinksToTheCardsMiddleInActiveCoordinates() {
        // Card 200x200 analysis pixels at centre. 80 % of it spans 240..400 and 160..320, times 6.25.
        MeteringRegions.Region r = MeteringRegions.forCard(quad(220, 140, 420, 340), ANALYSIS, FULL, new Dim(4000, 3000));
        assertEquals(new MeteringRegions.Region(1500, 1000, 1000, 1000), r);
    }

    @Test
    void meteringRegionFollowsZoomCrop() {
        ActiveRect zoom = new ActiveRect(1000, 750, 2000, 1500);
        MeteringRegions.Region r = MeteringRegions.forCard(quad(220, 140, 420, 340), ANALYSIS, zoom, new Dim(4000, 3000));
        assertEquals(new MeteringRegions.Region(1750, 1250, 500, 500), r);
    }

    @Test
    void meteringRegionIsClampedInsideActiveArrayLastPixel() {
        MeteringRegions.Region r = MeteringRegions.forCard(quad(500, 300, 800, 600), ANALYSIS, FULL, new Dim(4000, 3000));
        assertTrue(r.x() + r.width() <= 3999);
        assertTrue(r.y() + r.height() <= 2999);
        assertTrue(r.x() >= 0 && r.y() >= 0);
    }

    @Test
    void meteringRegionIsClampedToZoomCrop() {
        ActiveRect zoom = new ActiveRect(1000, 750, 2000, 1500);
        MeteringRegions.Region r = MeteringRegions.forCard(quad(-200, -200, 100, 100), ANALYSIS, zoom, new Dim(4000, 3000));
        assertEquals(1000, r.x());
        assertEquals(750, r.y());
    }

    @Test
    void cardOutsideTheCropHasNoRegion() {
        assertNull(MeteringRegions.forCard(quad(-400, -400, -200, -200), ANALYSIS, FULL, new Dim(4000, 3000)));
    }

    @Test
    void meteringUsesTheAnalysisStreamsOwnCrop() {
        // A 16:9 analysis stream sees only y 375..2625, so its top row meters at y 375 and below.
        Dim wide = new Dim(640, 360);
        MeteringRegions.Region r = MeteringRegions.forCard(quad(0, 0, 640, 360), wide, FULL, new Dim(4000, 3000));
        assertEquals(375 + 225, r.y());
    }

    @Test
    void pixelRotationFromExifAndSize() {
        Dim stream = new Dim(4000, 3000);
        // EXIF-only phone: pixels unturned, tag says turn 90.
        assertEquals(0, StillMapper.pixelRotation(90, 90, stream, new Dim(4000, 3000)));
        // Pixel-rotating phone: pixels turned, no tag.
        assertEquals(90, StillMapper.pixelRotation(90, 0, stream, new Dim(3000, 4000)));
        // A phone that did neither: size says unturned, despite no tag.
        assertEquals(0, StillMapper.pixelRotation(90, 0, stream, new Dim(4000, 3000)));
        // A contradictory tag on turned pixels: size wins.
        assertEquals(270, StillMapper.pixelRotation(270, 270, stream, new Dim(3000, 4000)));
    }

    @Test
    void outlineMapsToUnturnedAndTurnedStills() {
        Dim jpeg = new Dim(4000, 3000);
        CardQuad card = quad(64, 48, 128, 96);
        CardQuad plain = StillMapper.toStored(card, ANALYSIS, jpeg, FULL, 0, jpeg);
        assertCorner(400, 300, plain.c0());
        assertCorner(800, 600, plain.c2());
        CardQuad turned = StillMapper.toStored(card, ANALYSIS, jpeg, FULL, 90, new Dim(3000, 4000));
        assertCorner(2700, 400, turned.c0());
        assertCorner(2400, 800, turned.c2());
    }

    @Test
    void outlineScalesToAStoredSizeUnlikeTheStream() {
        CardQuad scaled = StillMapper.toStored(quad(64, 48, 128, 96), ANALYSIS, new Dim(4000, 3000), FULL, 0, new Dim(2000, 1500));
        assertCorner(200, 150, scaled.c0());
    }

    @Test
    void outlineHonoursRatioDifferenceBetweenAnalysisAndJpeg() {
        // 16:9 analysis on a 4:3 JPEG: analysis top is 375 down the sensor.
        CardQuad mapped = StillMapper.toStored(quad(0, 0, 640, 360), new Dim(640, 360), new Dim(4000, 3000), FULL, 0, new Dim(4000, 3000));
        assertCorner(0, 375, mapped.c0());
        assertCorner(4000, 2625, mapped.c2());
    }

    @Test
    void cropClipsToImageAndShiftsTheOutline() {
        StillMapper.Crop crop = StillMapper.cropFor(quad(-10, 100, 500, 900), new Dim(4000, 3000));
        assertEquals(0, crop.left());
        assertEquals(100, crop.top());
        assertEquals(500, crop.right());
        assertEquals(900, crop.bottom());
        assertEquals(1, crop.sampleSize());
        assertCorner(-10, 0, crop.quad().c0());
    }

    @Test
    void largeCropIsSampledDownAndOutlineFollows() {
        StillMapper.Crop crop = StillMapper.cropFor(quad(100, 100, 3300, 2500), new Dim(4000, 3000));
        assertEquals(2, crop.sampleSize());
        assertCorner(1600, 1200, crop.quad().c2());
    }

    @Test
    void outlineOutsideTheImageHasNoCrop() {
        assertNull(StillMapper.cropFor(quad(-500, -500, -100, -100), new Dim(4000, 3000)));
    }
}
