package forge.cardocr;

/**
 * Scale for a TextureView that shows a camera stream in a portrait-locked activity.
 *
 * <p>The camera's SurfaceTexture already rotates frames to the device's natural orientation, then
 * stretches them to the view. So rotation needs no correction here; only the stretch does.
 */
public final class PreviewTransform {

    public record Scale(float x, float y) {
    }

    private PreviewTransform() {
    }

    /** Scale around the view centre that fills the view with no distortion, cropping the overflow. */
    public static Scale centerCrop(Dim stream, int sensorOrientation, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return new Scale(1f, 1f);
        }
        boolean swapped = sensorOrientation % 180 != 0;
        double frameWidth = swapped ? stream.height() : stream.width();
        double frameHeight = swapped ? stream.width() : stream.height();
        double ratioX = frameWidth / viewWidth;
        double ratioY = frameHeight / viewHeight;
        double fit = Math.min(ratioX, ratioY);
        return new Scale((float) (ratioX / fit), (float) (ratioY / fit));
    }
}
