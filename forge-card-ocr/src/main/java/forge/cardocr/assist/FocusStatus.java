package forge.cardocr.assist;

/**
 * Progress of the latest refocus.
 *
 * @param generation which refocus request this describes
 * @param settleMs from the request until the lens settled, or -1 until then
 */
public record FocusStatus(int generation, FocusStatus.Outcome outcome, long settleMs) {

    public enum Outcome {
        PENDING,
        FOCUSED,
        /** The scan finished without sharp focus. Low-contrast scenes can report this. */
        NOT_FOCUSED,
        /** The device never reported a result in time. */
        TIMED_OUT,
        NO_AUTOFOCUS
    }

    public static final FocusStatus NONE = new FocusStatus(0, Outcome.PENDING, -1);

    public boolean settled() {
        return outcome != Outcome.PENDING;
    }
}
