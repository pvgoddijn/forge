package forge.app.camera;

import android.hardware.camera2.CaptureResult;

/**
 * Decides when a still can be taken, from the AF and AE states in each preview result: lock focus,
 * let auto exposure settle if it hasn't, then shoot. Timeouts stop a device that never reports the
 * expected state from blocking the shot.
 *
 * <p>Only compile-time constants come from CaptureResult, so this runs in JVM unit tests.
 */
final class CaptureSequence {

    enum Action {
        NONE,
        TRIGGER_PRECAPTURE,
        TAKE_PHOTO
    }

    private enum Phase {
        AWAIT_FOCUS,
        AWAIT_PRECAPTURE_START,
        AWAIT_PRECAPTURE_END,
        DONE
    }

    static final long FOCUS_TIMEOUT_MS = 1500;
    static final long EXPOSURE_TIMEOUT_MS = 800;

    private final boolean hasAutofocus;
    private Phase phase = Phase.AWAIT_FOCUS;
    private long phaseStartMs;

    CaptureSequence(boolean hasAutofocus, long nowMs) {
        this.hasAutofocus = hasAutofocus;
        this.phaseStartMs = nowMs;
    }

    /** Feed each completed preview result. A null state means the device doesn't report it. */
    Action onResult(Integer afState, Integer aeState, long nowMs) {
        return switch (phase) {
            case AWAIT_FOCUS -> onFocusResult(afState, aeState, nowMs);
            case AWAIT_PRECAPTURE_START -> onPrecaptureStartResult(aeState, nowMs);
            case AWAIT_PRECAPTURE_END -> onPrecaptureEndResult(aeState, nowMs);
            case DONE -> Action.NONE;
        };
    }

    private Action onFocusResult(Integer afState, Integer aeState, long nowMs) {
        if (!focusSettled(afState) && !timedOut(nowMs, FOCUS_TIMEOUT_MS)) {
            return Action.NONE;
        }
        if (exposureSettled(aeState)) {
            return takePhoto();
        }
        enter(Phase.AWAIT_PRECAPTURE_START, nowMs);
        return Action.TRIGGER_PRECAPTURE;
    }

    private Action onPrecaptureStartResult(Integer aeState, long nowMs) {
        if (exposureSettled(aeState) || timedOut(nowMs, EXPOSURE_TIMEOUT_MS)) {
            return takePhoto();
        }
        if (aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
            // The exposure budget covers both precapture phases, so phaseStartMs is kept.
            phase = Phase.AWAIT_PRECAPTURE_END;
        }
        return Action.NONE;
    }

    private Action onPrecaptureEndResult(Integer aeState, long nowMs) {
        boolean finished = aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE;
        return finished || timedOut(nowMs, EXPOSURE_TIMEOUT_MS) ? takePhoto() : Action.NONE;
    }

    private boolean focusSettled(Integer afState) {
        return !hasAutofocus
                || afState == null
                || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;
    }

    private static boolean exposureSettled(Integer aeState) {
        return aeState == null
                || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
    }

    private boolean timedOut(long nowMs, long timeoutMs) {
        return nowMs - phaseStartMs >= timeoutMs;
    }

    private void enter(Phase next, long nowMs) {
        phase = next;
        phaseStartMs = nowMs;
    }

    private Action takePhoto() {
        phase = Phase.DONE;
        return Action.TAKE_PHOTO;
    }
}
