package forge.cardocr.assist;

import java.util.*;

/**
 * Where one capture's time went. Each value is ms from {@code t0}, the first refined candidate since
 * the scene last offered none, or null if it never happened. Conditions that came and went
 * give the time they last became true.
 */
public record CaptureTimeline(long t0Ms, Long provisionalShownMs, Long confirmedMs, Long focusStartMs, Long focusSettledMs,
        Long cardSteadyMs, Long phoneSteadyMs, Long triggerMs, Long photoDoneMs) {

    public CaptureTimeline withTrigger(long atMs) {
        return new CaptureTimeline(t0Ms, provisionalShownMs, confirmedMs, focusStartMs, focusSettledMs, cardSteadyMs, phoneSteadyMs, atMs - t0Ms, photoDoneMs);
    }

    public CaptureTimeline withPhotoDone(long atMs) {
        return new CaptureTimeline(t0Ms, provisionalShownMs, confirmedMs, focusStartMs, focusSettledMs, cardSteadyMs, phoneSteadyMs, triggerMs, atMs - t0Ms);
    }

    /** One line for the log. */
    public String describe() {
        return String.format(Locale.ROOT, "provisional=%s confirmed=%s focusStart=%s focusSettled=%s cardSteady=%s phoneSteady=%s trigger=%s photoDone=%s",
                provisionalShownMs, confirmedMs, focusStartMs, focusSettledMs, cardSteadyMs, phoneSteadyMs, triggerMs, photoDoneMs);
    }

    /** Builds a timeline frame by frame. */
    static final class Recorder {
        private long t0 = -1;
        private Long shown;
        private Long confirmed;
        private Long focusStart;
        private Long focusSettled;
        private Long cardSteady;
        private Long phoneSteady;

        void frame(boolean anyCandidate, boolean isShown, boolean isConfirmed, boolean focusAsked, boolean settled, boolean cardIsSteady, boolean phoneIsSteady, long nowMs) {
            if (!anyCandidate && !isShown) {
                t0 = -1;
                shown = confirmed = focusStart = focusSettled = cardSteady = phoneSteady = null;
                return;
            }
            if (t0 < 0) {
                t0 = nowMs;
            }
            long at = nowMs - t0;
            shown = isShown ? (shown == null ? (Long) at : shown) : null;
            confirmed = isConfirmed ? (confirmed == null ? (Long) at : confirmed) : null;
            if (focusAsked) {
                focusStart = at;
                focusSettled = null;
            }
            focusSettled = settled && focusStart != null ? (focusSettled == null ? (Long) at : focusSettled) : null;
            cardSteady = cardIsSteady ? (cardSteady == null ? (Long) at : cardSteady) : null;
            phoneSteady = phoneIsSteady ? (phoneSteady == null ? (Long) at : phoneSteady) : null;
        }

        /** @return the timeline so far, or null if there's been no candidate */
        CaptureTimeline snapshot() {
            return t0 < 0 ? null : new CaptureTimeline(t0, shown, confirmed, focusStart, focusSettled, cardSteady, phoneSteady, null, null);
        }
    }
}
