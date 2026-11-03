package forge.cardocr.assist;


/** Which cameras can run Assisted mode. Only compile-time constants come from CameraMetadata. */
public final class HardwareSupport {

    private HardwareSupport() {
    }

    /**
     * Preview, YUV and full-size JPEG together are guaranteed only on LIMITED or better. The level
     * constants are not in order of capability, so they can't be compared with {@code >=}.
     */
    public static boolean guaranteesPreviewYuvJpeg(Integer hardwareLevel) {
        if (hardwareLevel == null) {
            return false;
        }
        return switch (hardwareLevel) {
            case Camera2Values.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
                    Camera2Values.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                    Camera2Values.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> true;
            default -> false;
        };
    }

    /** Whether optical stabilisation can be turned on. */
    public static boolean hasOpticalStabilization(int[] modes) {
        if (modes != null) {
            for (int mode : modes) {
                if (mode == Camera2Values.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                    return true;
                }
            }
        }
        return false;
    }
}
