package forge.app.camera;

import android.media.Image;
import forge.cardocr.*;
import forge.cardocr.assist.*;

/**
 * A live analyser of camera frames. When one is passed to CameraController.start, the controller
 * adds a low-resolution YUV_420_888 ImageReader stream to the session.
 */
public interface FrameAnalyser {

    /** The largest frame size wanted. The controller picks a stream that fits, at the photo's ratio. */
    Dim maxFrameSize();

    /**
     * Called on the camera thread, before frames and again if the crop region changes.
     *
     * @param mapping turns phone rotation into motion in the frame, or null if it can't be worked out
     * @param realtimeClock true if image timestamps are on the elapsedRealtimeNanos clock
     */
    void onOptics(GyroMapping mapping, boolean realtimeClock);

    /**
     * Receives the latest frame on the camera thread. Return quickly and copy anything to keep:
     * the controller closes the image on return, and slow work here delays the capture sequence.
     */
    void onFrame(Image frame);
}
