package forge.cardocr.assist;

import forge.cardocr.*;
import forge.cardocr.finder.*;

/**
 * Turns a phone rotation into motion in the analysis frame, assuming pure rotation about the lens.
 *
 * <p>Android's gyroscope uses device axes in the natural (portrait) orientation: +X to the screen's
 * right, +Y to its top, +Z out of the screen, counter-clockwise positive. Viewed upright, a rear
 * camera's image has u = +X, v = -Y and looks along -Z. A front camera's unmirrored image has u = -X,
 * v = -Y and looks along +Z. Sensor pixels are that image turned back by SENSOR_ORIENTATION.
 *
 * <p>Each point's ray is turned the opposite way to the phone and projected again. This covers pan,
 * tilt and roll about the optical axis alike, and the larger shift away from the image centre.
 * Moving the phone sideways, which matters at card distances, can't be seen by a gyroscope. The
 * centre of the frame stands in for the principal point, and rolling shutter is ignored.
 *
 * @param sensorOrientation SENSOR_ORIENTATION, one of 0, 90, 180, 270
 * @param frontFacing true for a front camera
 * @param focalX focal length in analysis pixels along the frame's x
 * @param focalY focal length in analysis pixels along the frame's y
 * @param centreX the frame's centre
 * @param centreY the frame's centre
 */
public record GyroMapping(int sensorOrientation, boolean frontFacing, double focalX, double focalY,
        double centreX, double centreY) {

    /**
     * From camera characteristics. Physical size covers the pixel array, whose pixels have the same
     * pitch as the active array's coordinates.
     *
     * @return null if a value needed is missing or unusable
     */
    public static GyroMapping of(Float focalMm, Double physicalWidthMm, Double physicalHeightMm, Dim pixelArray,
            int sensorOrientation, boolean frontFacing, Dim analysis, ActiveRect crop) {
        if (focalMm == null || physicalWidthMm == null || physicalHeightMm == null || pixelArray == null
                || focalMm <= 0 || physicalWidthMm <= 0 || physicalHeightMm <= 0) {
            return null;
        }
        ActiveRect visible = SensorGeometry.visibleArea(analysis, crop);
        double focalX = focalMm * pixelArray.width() / physicalWidthMm * analysis.width() / visible.width();
        double focalY = focalMm * pixelArray.height() / physicalHeightMm * analysis.height() / visible.height();
        return new GyroMapping(sensorOrientation, frontFacing, focalX, focalY, analysis.width() / 2.0, analysis.height() / 2.0);
    }

    /** The image motion caused by this phone rotation, given in device axes. */
    public ImageMotion motion(GyroHistory.Rotation device) {
        double u = frontFacing ? -device.x() : device.x();
        double v = -device.y();
        double w = frontFacing ? device.z() : -device.z();
        double x;
        double y;
        switch (sensorOrientation) {
            case 0 -> {
                x = u;
                y = v;
            }
            case 90 -> {
                x = v;
                y = -u;
            }
            case 180 -> {
                x = -u;
                y = -v;
            }
            case 270 -> {
                x = -v;
                y = u;
            }
            default -> throw new IllegalArgumentException("Not a right angle: " + sensorOrientation);
        }
        double ax = x;
        double ay = y;
        double angle = Math.sqrt(ax * ax + ay * ay + w * w);
        return angle == 0 ? ImageMotion.NONE : point -> turnedBack(point, ax, ay, w, angle);
    }

    /** Rodrigues' formula: the ray is turned by minus the camera's rotation. */
    private Corner turnedBack(Corner point, double ax, double ay, double az, double angle) {
        double rx = (point.x() - centreX) / focalX;
        double ry = (point.y() - centreY) / focalY;
        double kx = ax / angle;
        double ky = ay / angle;
        double kz = az / angle;
        double dot = kx * rx + ky * ry + kz;
        double sin = Math.sin(-angle);
        double cos = Math.cos(angle);
        double outX = rx * cos + (ky - kz * ry) * sin + kx * dot * (1 - cos);
        double outY = ry * cos + (kz * rx - kx) * sin + ky * dot * (1 - cos);
        double outZ = cos + (kx * ry - ky * rx) * sin + kz * dot * (1 - cos);
        if (outZ < 0.1) {
            return point; // Turned out of view, so there's no sensible position.
        }
        return new Corner(centreX + focalX * outX / outZ, centreY + focalY * outY / outZ);
    }
}
