package forge.cardocr.assist;

/**
 * What the camera reported for one Assisted photo, as CaptureResult values. Each is null if the camera
 * didn't report it.
 *
 * @param sensorTimestampNs matches the timestamp of the JPEG made from this frame
 * @param exposureNs SENSOR_EXPOSURE_TIME
 * @param iso SENSOR_SENSITIVITY
 * @param edgeMode EDGE_MODE
 * @param noiseReductionMode NOISE_REDUCTION_MODE
 * @param lensState LENS_STATE
 * @param afState CONTROL_AF_STATE
 * @param focusDistance LENS_FOCUS_DISTANCE, in diopters
 */
public record StillFrame(Long sensorTimestampNs, Long exposureNs, Integer iso, Integer edgeMode, Integer noiseReductionMode,
        Integer lensState, Integer afState, Float focusDistance) {
}
