package forge.cardocr.assist;

import forge.cardocr.finder.*;

/**
 * Decides when to capture automatically, and stops the same card being captured twice in a row: after a
 * capture the gate stays latched until the card has left the frame, looks different, has moved
 * well away from where it was, or {@link #HOLD_MAX_MS} has passed. Appearance alone can't be
 * relied on: on the phone, two different cards measured 22.1 where the limit is 22.
 */
public final class CaptureGate {

    /** Guess: how long with no card counts as "left". Longer than the tracker's tolerance for gaps. */
    static final long LEFT_AFTER_MS = 1000;
    /** Guess: mean luma difference, in levels of 255, between signatures of different cards. */
    static final double CHANGED_DISTANCE = 22;
    /** Consecutive frames that must look different, so one bad frame or a passing hand doesn't count. */
    static final int CHANGED_FRAMES = 3;
    /** Guess: and for this long, because at 15 frames/s three frames is hardly any time for a passing hand. */
    static final long CHANGED_MS = 500;
    /** Guess: with a gyroscope, the card itself must have held still this long, since a hand can move it. */
    static final long CARD_STILL_MS = 300;

    /**
     * What the gyroscope says about the phone.
     *
     * @param speedRadS the current smoothed angular speed
     * @param steadyForMs how long it has been steady
     */
    public record PhoneState(double speedRadS, long steadyForMs) {
    }

    /** Guess: how far the card's centre must move, as a share of its long side, to count as aimed elsewhere. */
    static final double MOVED_SHARE = 0.5;
    /**
     * Guess: the longest a card stays blocked. 3 s made every new card wait, because appearance
     * often misses a change. The price is the same card being captured again if it stays in view.
     */
    public static final long HOLD_MAX_MS = 1000;
    /**
     * Guess: with no hold time set, faster than this the phone is plainly being moved, not just held by a shaky
     * hand. Some photos at this speed blur too far to read, which a retake then deals with.
     */
    static final double MOVING_RAD_S = 0.25;
    /** Without a gyroscope nothing else shows a moving phone, so a little outline steadiness is always asked for. */
    static final long NO_GYRO_MIN_STEADY_MS = 300;

    private float[] capturedCard;
    private Corner anchor;
    private long heldSinceMs = -1;
    private long blockedSinceMs = -1;
    private long blockedMs;
    private String releasedBy;
    private long lastUpdateMs;
    private boolean latched;
    private long absentSinceMs = -1;
    private int changedFrames;
    private long changedSinceMs;
    private long waitingSinceMs = -1;
    private long lastWaitMs = -1;

    /** @param capturedCard signature of a card already captured, or null. Survives a pause. */
    public CaptureGate(float[] capturedCard) {
        this.capturedCard = capturedCard;
        this.latched = capturedCard != null;
    }

    /** Latches for a capture started elsewhere, such as the shutter button. */
    public void latch(float[] signature) {
        endBlock("shutter");
        capturedCard = signature;
        latched = true;
        absentSinceMs = -1;
        changedFrames = 0;
        anchor = null;
        heldSinceMs = -1;
        blockedSinceMs = -1;
    }

    /** Re-arms after a capture that failed. */
    public void release() {
        release("capture failed");
    }

    private void release(String reason) {
        endBlock(reason);
        latched = false;
        capturedCard = null;
        anchor = null;
        heldSinceMs = -1;
    }

    private void endBlock(String reason) {
        if (latched) {
            blockedMs += blockedSinceMs < 0 ? 0 : lastUpdateMs - blockedSinceMs;
            blockedSinceMs = -1;
            releasedBy = reason;
        }
    }

    public boolean isLatched() {
        return latched;
    }

    /** How long the block has held a capture back, over the periods that have ended. */
    public long blockedMs() {
        return blockedMs;
    }

    /** What lifted the block last, or null if nothing has. */
    public String releasedBy() {
        return releasedBy;
    }

    /** How long the last automatic capture waited with everything but steadiness in place. */
    public long lastWaitMs() {
        return lastWaitMs;
    }

    /** As the full update, without a gyroscope. */
    public boolean update(TrackedCard card, float[] signature, long nowMs, boolean focusSettled,
            long steadyMs, boolean autoEnabled, boolean captureInFlight) {
        return update(card, signature, nowMs, focusSettled, steadyMs, autoEnabled, captureInFlight, null);
    }

    /**
     * Feed every frame. Returns true when a capture should start now, and latches.
     *
     * Phone, card, focus and confirmation are all measured from when they began, so they overlap rather
     * than adding up. The photo's exposure comes hundreds of ms after this, so picking a calm instant
     * here wouldn't help: in run3.log speed at trigger and at the photo were unrelated.
     *
     * @param phone null without a working gyroscope, which leaves only the outline to judge steadiness
     */
    public boolean update(TrackedCard card, float[] signature, long nowMs, boolean focusSettled,
            long steadyMs, boolean autoEnabled, boolean captureInFlight, PhoneState phone) {
        lastUpdateMs = nowMs;
        updateLatch(card, signature, nowMs, captureInFlight);
        if (card == null || latched || captureInFlight || !autoEnabled || !focusSettled) {
            waitingSinceMs = -1;
            return false;
        }
        if (waitingSinceMs < 0) {
            waitingSinceMs = nowMs;
        }
        if (!card.seenNow()) {
            return false; // A frame's miss doesn't end the wait, but a capture needs the card in view.
        }
        // The exposure starts after this and steadiness now doesn't predict it, so by default nothing is held
        // for: a blurred photo shows up as a failed read, which gets a retake.
        boolean fire;
        if (phone == null) {
            fire = card.steadyForMs(nowMs) >= Math.max(steadyMs, NO_GYRO_MIN_STEADY_MS);
        } else if (steadyMs <= 0) {
            fire = phone.speedRadS() <= MOVING_RAD_S;
        } else {
            fire = phone.steadyForMs() >= steadyMs && card.steadyForMs(nowMs) >= Math.min(steadyMs, CARD_STILL_MS);
        }
        if (fire) {
            lastWaitMs = nowMs - waitingSinceMs;
            latch(signature);
        }
        return fire;
    }

    private void updateLatch(TrackedCard card, float[] signature, long nowMs, boolean captureInFlight) {
        if (!latched || captureInFlight) {
            return; // The time limit starts once the capture has finished.
        }
        if (blockedSinceMs < 0) {
            blockedSinceMs = nowMs;
        }
        if (card == null) {
            changedFrames = 0;
            if (absentSinceMs < 0) {
                absentSinceMs = nowMs;
            } else if (nowMs - absentSinceMs >= LEFT_AFTER_MS) {
                release("card left");
            }
            return;
        }
        absentSinceMs = -1;
        if (heldSinceMs < 0) {
            heldSinceMs = nowMs;
            anchor = centre(card.quad());
        }
        if (nowMs - heldSinceMs >= HOLD_MAX_MS) {
            release("time limit");
            return;
        }
        if (capturedCard == null) {
            // Captured with nothing to compare against; adopt the first card seen.
            capturedCard = signature;
        } else if (signature != null && (CardSignature.distance(capturedCard, signature) > CHANGED_DISTANCE || movedAway(card.quad()))) {
            if (changedFrames == 0) {
                changedSinceMs = nowMs;
            }
            if (++changedFrames >= CHANGED_FRAMES && nowMs - changedSinceMs >= CHANGED_MS) {
                release(movedAway(card.quad()) ? "card moved" : "card changed");
            }
        } else {
            changedFrames = 0;
        }
    }

    private boolean movedAway(CardQuad quad) {
        Corner[] c = quad.corners().toArray(new Corner[0]);
        double longSide = Math.max(c[0].distanceTo(c[3]), c[1].distanceTo(c[2]));
        return anchor != null && centre(quad).distanceTo(anchor) > MOVED_SHARE * longSide;
    }

    private static Corner centre(CardQuad quad) {
        double x = 0;
        double y = 0;
        for (Corner corner : quad.corners()) {
            x += corner.x() / 4;
            y += corner.y() / 4;
        }
        return new Corner(x, y);
    }
}
