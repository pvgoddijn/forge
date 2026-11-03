package forge.cardocr.assist;


/**
 * Follows one AF trigger to a settled state.
 *
 * <p>Results for frames already in flight when the trigger was sent can still report the previous
 * lock, so states only count from the trigger's own result onward. Camera2 delivers results in
 * frame order, which makes that safe. Only compile-time constants come from CaptureResult.
 */
public final class FocusSettler {

    /** Guess: how long to wait for AF. Long enough for a slow scan in dim light. */
    static final long TIMEOUT_MS = 2000;
    /** Guess: a lock this old is trusted. A shutter press long after could otherwise use a stale one. */
    static final long LOCK_FRESH_MS = 3000;

    private int generation;
    private long startMs;
    private boolean triggerApplied;
    private FocusStatus status = FocusStatus.NONE;
    private long settledAtMs;
    private Integer lastAfState;
    private boolean lockCancelled = true;

    /** Starts following a new AF trigger. */
    public void begin(int newGeneration, boolean hasAutofocus, long nowMs) {
        generation = newGeneration;
        startMs = nowMs;
        triggerApplied = false;
        lockCancelled = false;
        status = hasAutofocus
                ? new FocusStatus(generation, FocusStatus.Outcome.PENDING, -1)
                : new FocusStatus(generation, FocusStatus.Outcome.NO_AUTOFOCUS, 0);
    }

    /** Call just before feeding the trigger request's own result. */
    public void onTriggerApplied() {
        triggerApplied = true;
    }

    /** Feed each result's AF state. A null state means the device doesn't report it. */
    public void onAfState(Integer afState, long nowMs) {
        lastAfState = afState;
        if (status.settled() || status.generation() == 0) {
            return;
        }
        if (triggerApplied) {
            if (afState == null) {
                settle(FocusStatus.Outcome.TIMED_OUT, nowMs);
                return;
            }
            if (afState == Camera2Values.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                settle(FocusStatus.Outcome.FOCUSED, nowMs);
                return;
            }
            if (afState == Camera2Values.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                settle(FocusStatus.Outcome.NOT_FOCUSED, nowMs);
                return;
            }
        }
        if (nowMs - startMs >= TIMEOUT_MS) {
            settle(FocusStatus.Outcome.TIMED_OUT, nowMs);
        }
    }

    private void settle(FocusStatus.Outcome outcome, long nowMs) {
        status = new FocusStatus(generation, outcome, nowMs - startMs);
        settledAtMs = nowMs;
    }

    /** Something cancelled the AF lock, so a capture must focus for itself. */
    public void lockCancelled() {
        lockCancelled = true;
    }

    /**
     * Whether a capture can use the current lock instead of scanning again, which saves a scan of about
     * 200 ms. The refocus logic has already judged that the card hasn't moved far from it.
     */
    public boolean lockStillGood(long nowMs) {
        return !lockCancelled && status.outcome() == FocusStatus.Outcome.FOCUSED && nowMs - settledAtMs <= LOCK_FRESH_MS
                && lastAfState != null && lastAfState == Camera2Values.CONTROL_AF_STATE_FOCUSED_LOCKED;
    }

    public FocusStatus status() {
        return status;
    }
}
