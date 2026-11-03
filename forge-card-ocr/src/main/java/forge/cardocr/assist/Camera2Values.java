package forge.cardocr.assist;

/**
 * The Camera2 values the capture logic reads, copied from {@code android.hardware.camera2.CaptureResult} and
 * {@code CameraMetadata}. They're part of the Android API and never change, and copying them keeps this module
 * free of Android types, so the logic runs and is tested on a desktop JVM.
 */
public final class Camera2Values {
    public static final int CONTROL_AF_STATE_INACTIVE = 0;
    public static final int CONTROL_AF_STATE_PASSIVE_SCAN = 1;
    public static final int CONTROL_AF_STATE_PASSIVE_FOCUSED = 2;
    public static final int CONTROL_AF_STATE_ACTIVE_SCAN = 3;
    public static final int CONTROL_AF_STATE_FOCUSED_LOCKED = 4;
    public static final int CONTROL_AF_STATE_NOT_FOCUSED_LOCKED = 5;
    public static final int CONTROL_AF_STATE_PASSIVE_UNFOCUSED = 6;

    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED = 0;
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_FULL = 1;
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY = 2;
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_3 = 3;
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL = 4;

    public static final int LENS_OPTICAL_STABILIZATION_MODE_ON = 1;

    private Camera2Values() {
    }
}
