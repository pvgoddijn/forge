package forge.cardocr.assist;

import forge.cardocr.*;
import forge.cardocr.finder.*;
import java.util.*;

/**
 * Maps analysis-frame points to view points in the portrait-locked activity. Matches what the
 * preview does: the SurfaceTexture rotates the stream to the display and stretches it to the view,
 * then {@link PreviewTransform} scales it around the view's centre.
 *
 * @param mirrored true for a front camera, whose preview is mirrored after rotating
 */
public record ViewMapper(Dim preview, Dim analysis, ActiveRect crop, int sensorOrientation,
        boolean mirrored, int viewWidth, int viewHeight) {

    public Corner toView(Corner analysisPoint) {
        Corner inPreview = SensorGeometry.streamToStream(analysisPoint, analysis, preview, crop);
        Corner upright = SensorGeometry.rotated(inPreview, preview, sensorOrientation);
        Dim uprightSize = SensorGeometry.rotatedSize(preview, sensorOrientation);
        double u = upright.x() / uprightSize.width();
        double v = upright.y() / uprightSize.height();
        if (mirrored) {
            u = 1 - u;
        }
        PreviewTransform.Scale scale = PreviewTransform.centerCrop(preview, sensorOrientation, viewWidth, viewHeight);
        return new Corner(viewWidth / 2.0 + (u - 0.5) * viewWidth * scale.x(),
                viewHeight / 2.0 + (v - 0.5) * viewHeight * scale.y());
    }

    public List<Corner> toView(CardQuad quad) {
        List<Corner> out = new ArrayList<>();
        for (Corner corner : quad.corners()) {
            out.add(toView(corner));
        }
        return out;
    }
}
