package forge.cardocr.assist;

import forge.cardocr.finder.*;
import java.util.*;

/**
 * The per-frame decisions of Assisted mode: tracking, glare, when to refocus and when to capture.
 * It runs only on the analyser thread. Android-free, so a whole session can be replayed in a test.
 */
public final class AssistedPipeline {

    /** Frames in a row that must show glare, so one odd frame doesn't flash the warning. */
    static final int GLARE_FRAMES = 2;
    /** Guess: and for this long, because two frames are very quick at 15 frames/s. */
    static final long GLARE_MS = 200;
    /** Guess: with no card for this long, metering goes back to the whole scene. */
    static final long RELEASE_FOCUS_AFTER_MS = 1000;

    public enum FocusAction {
        NONE,
        FOCUS_ON_CARD,
        RELEASE
    }

    /**
     * One frame's inputs. {@code captureInFlight} covers the time until the run has been saved.
     * {@code motion} is null while there's no working gyroscope. {@code exposureNs} is the camera's latest
     * exposure, or null if not known.
     */
    public record Input(FindResult found, byte[] luma, int width, int height, long nowMs,
            FocusStatus focus, boolean autoEnabled, long steadyMs, boolean captureInFlight, FrameMotion motion, Long exposureNs) {

        public Input(FindResult found, byte[] luma, int width, int height, long nowMs,
                FocusStatus focus, boolean autoEnabled, long steadyMs, boolean captureInFlight, FrameMotion motion) {
            this(found, luma, width, height, nowMs, focus, autoEnabled, steadyMs, captureInFlight, motion, null);
        }

        public Input(FindResult found, byte[] luma, int width, int height, long nowMs,
                FocusStatus focus, boolean autoEnabled, long steadyMs, boolean captureInFlight) {
            this(found, luma, width, height, nowMs, focus, autoEnabled, steadyMs, captureInFlight, null, null);
        }
    }

    /**
     * One frame's decisions.
     *
     * @param topCandidate the finder's first choice this frame, or null
     * @param card the tracked card, or null
     * @param signature the tracked card's appearance, or null
     * @param focusGeneration the refocus number that {@code focusAction} and status checks refer to
     * @param motion this frame's gyroscope reading, or null without a working gyroscope
     * @param phoneSteadyMs how long the gyroscope has called the phone steady
     * @param steadyWaitMs on the frame that captures, how long steadiness was the only thing missing
     * @param audit this frame's predicted against observed outline shift, or null
     * @param auditSummary the same over recent frames, with outline jitter
     * @param shown the outline to draw, or null
     * @param focusQuad the outline {@code focusAction} refers to, or null
     * @param timeline where the time has gone since the card appeared, or null
     * @param blockedMs how long the block on recapturing a card has held captures back so far
     * @param blockReleasedBy what last lifted that block, or null
     */
    public record Outcome(CardQuad topCandidate, TrackedCard card, float[] signature,
            double finderMs, double framesPerSecond, FinderStats.Summary timings,
            boolean glareNow, boolean glareWarnedForCard, double glareShare,
            FocusAction focusAction, int focusGeneration, boolean focusSettled,
            boolean latched, boolean captureNow,
            FrameMotion motion, long phoneSteadyMs, Long steadyWaitMs,
            MotionAudit.Step audit, MotionAudit.Summary auditSummary,
            OutlineDisplay.Shown shown, CardQuad focusQuad, CaptureTimeline timeline, long blockedMs, String blockReleasedBy) {
    }

    private final CardTracker tracker = new CardTracker();
    private final FinderStats stats = new FinderStats();
    private final RefocusPolicy refocus = new RefocusPolicy();
    private final CaptureGate gate;
    private final PhoneSteadiness phone = new PhoneSteadiness();
    private final MotionAudit motionAudit = new MotionAudit();
    private final LocalTracking localTracking = new LocalTracking();
    private final OutlineDisplay display = new OutlineDisplay();
    private final CaptureTimeline.Recorder timeline = new CaptureTimeline.Recorder();

    private int focusGeneration;
    private long absentSinceMs = -1;
    private int glareStreak;
    private long glareSinceMs;
    private boolean glareWarned;
    private int lastCardId;
    private int focusCardId;
    private int focusRevision;

    /** @param capturedCard signature of the card last captured, or null */
    public AssistedPipeline(float[] capturedCard) {
        gate = new CaptureGate(capturedCard);
    }

    public void latch(float[] signature) {
        gate.latch(signature);
    }

    public void releaseLatch() {
        gate.release();
    }

    /** Runs the finder on a frame: a full search, or only a refit around what's already known. */
    public FindResult find(CardFinder finder, byte[] luma, int width, int height, long nowMs) {
        return find(finder, luma, width, height, nowMs, quad -> true);
    }

    /** @param visible whether an outline can be seen on the screen. Only those can become the card */
    public FindResult find(CardFinder finder, byte[] luma, int width, int height, long nowMs, java.util.function.Predicate<CardQuad> visible) {
        List<CardQuad> seeds = tracker.seeds(nowMs);
        FindResult found = finder.find(luma, width, height, localTracking.useLocal(seeds, tracker.holding(), nowMs) ? seeds : null);
        if (found.local()) {
            found = new FindResult(tracker.undrifted(found.candidates()), found.searchNanos(), found.refineNanos(), found.elapsedNanos(), true);
        }
        localTracking.onResult(found, seeds, nowMs);
        List<CardQuad> accepted = new ArrayList<>(CardShape.acceptedOnly(found.candidates()));
        accepted.removeIf(visible.negate());
        return new FindResult(accepted, found.searchNanos(), found.refineNanos(), found.elapsedNanos(), found.local());
    }

    public Outcome onFrame(Input in) {
        FindResult found = in.found();
        stats.add(found.elapsedMillis(), in.nowMs());
        FrameMotion motion = in.motion();
        ImageMotion imageMotion = motion == null ? ImageMotion.NONE : motion.image();
        // The preview is centred on the frame, so the frame's middle is the screen's.
        tracker.setAim(new Corner(in.width() / 2.0, in.height() / 2.0));
        TrackedCard card = tracker.update(found.candidates(), in.nowMs(), imageMotion, found.local());
        OutlineDisplay.Shown shown = display.update(found.candidates(), card, imageMotion, in.nowMs());
        MotionAudit.Step step = motionAudit.observe(card, imageMotion);

        float[] signature = null;
        double glareShare = 0;
        boolean glareNow = false;
        if (card != null && !card.seenNow()) {
            // A gap being bridged. The frame may not show the card, so don't measure it. A wrong
            // signature here would read as a different card.
        } else if (card != null) {
            signature = CardSignature.of(in.luma(), in.width(), in.height(), card.seenQuad());
            GlareDetector.Reading glare = GlareDetector.measure(in.luma(), in.width(), in.height(), card.seenQuad());
            glareShare = glare.worstShare();
            if (card.id() != lastCardId) {
                lastCardId = card.id();
                glareWarned = false;
                glareStreak = 0;
            }
            if (glare.warns() && glareStreak == 0) {
                glareSinceMs = in.nowMs();
            }
            glareStreak = glare.warns() ? glareStreak + 1 : 0;
            glareNow = glareStreak >= GLARE_FRAMES && in.nowMs() - glareSinceMs >= GLARE_MS;
            glareWarned |= glareNow;
        } else {
            glareStreak = 0;
        }

        FocusAction action = in.captureInFlight() ? FocusAction.NONE : focusAction(card, shown, in.nowMs());
        boolean settled = in.focus().generation() == focusGeneration && in.focus().settled();
        CaptureGate.PhoneState phoneState = null;
        if (motion == null) {
            phone.reset();
        } else {
            phone.update(motion.speedRadS(), BlurBudget.limitRadS(in.exposureNs()), in.nowMs());
            phoneState = new CaptureGate.PhoneState(motion.speedRadS(), phone.steadyForMs(in.nowMs()));
        }
        boolean capture = gate.update(card, signature, in.nowMs(), settled, in.steadyMs(),
                in.autoEnabled(), in.captureInFlight(), phoneState);
        long phoneSteadyFor = phoneState == null ? 0 : phoneState.steadyForMs();
        boolean cardSteady = card != null && card.steadyForMs(in.nowMs()) >= Math.min(in.steadyMs(), CaptureGate.CARD_STILL_MS);
        timeline.frame(found.candidates().stream().anyMatch(CardQuad::refined), shown != null, card != null,
                action == FocusAction.FOCUS_ON_CARD, settled, cardSteady, phoneSteadyFor >= in.steadyMs(), in.nowMs());
        CaptureTimeline times = timeline.snapshot();
        CardQuad top = found.candidates().isEmpty() ? null : found.candidates().get(0);
        return new Outcome(top, card, signature, found.elapsedMillis(), stats.framesPerSecond(),
                stats.summary(), glareNow, glareWarned, glareShare, action, focusGeneration, settled,
                gate.isLatched(), capture, motion, phoneState == null ? 0 : phoneState.steadyForMs(),
                capture ? (Long) gate.lastWaitMs() : null, step, motionAudit.summary(), shown,
                focusSubject(card, shown), capture && times != null ? times.withTrigger(in.nowMs()) : times, gate.blockedMs(), gate.releasedBy());
    }

    /** Focus can start before the card is confirmed, so that the two overlap. A second sighting avoids chasing one odd frame. */
    private static CardQuad focusSubject(TrackedCard card, OutlineDisplay.Shown shown) {
        if (card != null) {
            return card.quad();
        }
        return shown != null && shown.sightings() >= 2 ? shown.quad() : null;
    }

    private FocusAction focusAction(TrackedCard card, OutlineDisplay.Shown shown, long nowMs) {
        CardQuad subject = focusSubject(card, shown);
        if (subject != null) {
            absentSinceMs = -1;
            if (card == null) {
                // Not confirmed yet.
            } else if (card.id() != focusCardId) {
                focusCardId = card.id();
                focusRevision = card.outlineRevision();
                refocus.adopt(card.quad());
            } else if (card.outlineRevision() != focusRevision) {
                focusRevision = card.outlineRevision();
                refocus.rebase(card.quad());
            }
            if (refocus.shouldRefocus(subject, nowMs)) {
                focusGeneration++;
                return FocusAction.FOCUS_ON_CARD;
            }
            return FocusAction.NONE;
        }
        if (!refocus.hasFocus()) {
            return FocusAction.NONE;
        }
        if (absentSinceMs < 0) {
            absentSinceMs = nowMs;
        } else if (nowMs - absentSinceMs >= RELEASE_FOCUS_AFTER_MS) {
            refocus.reset();
            absentSinceMs = -1;
            return FocusAction.RELEASE;
        }
        return FocusAction.NONE;
    }
}
