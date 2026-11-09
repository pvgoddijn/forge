package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TrackingTest {

    private static CardQuad card(double x, double y) {
        return new CardQuad(new Corner(x, y), new Corner(x + 143, y), new Corner(x + 143, y + 200), new Corner(x, y + 200), 1, true);
    }

    private static CardQuad rough(double x, double y) {
        return new CardQuad(new Corner(x, y), new Corner(x + 143, y), new Corner(x + 143, y + 200), new Corner(x, y + 200), 1, false);
    }

    private static final CardQuad A = card(100, 100);
    private static final CardQuad B = card(400, 100);

    /** Frames 100 ms apart that take an outline past both confirmation limits. */
    private static final int CONFIRM = 5;
    /** Frames 100 ms apart that take a missing track past its time limit. */
    private static final int ENDS_TRACK = 5;
    private static final int BRIDGED = 3;

    private final CardTracker tracker = new CardTracker();
    private long now = 1000;

    private TrackedCard feed(CardQuad... candidates) {
        now += 100;
        return tracker.update(List.of(candidates), now);
    }

    private static final Corner AIM = new Corner(320, 240);
    /** Centred on the aim. */
    private static final CardQuad MIDDLE = card(249, 140);
    private static final CardQuad FAR_LEFT = card(0, 140);
    private static final CardQuad FAR_RIGHT = card(490, 140);

    @Test
    void withAnAimTheNearestCardWinsWhateverCameFirst() {
        tracker.setAim(AIM);
        TrackedCard card = feedTimes(CONFIRM, FAR_LEFT, MIDDLE, FAR_RIGHT);
        assertEquals(MIDDLE.c0().x(), card.quad().c0().x(), 3);
    }

    @Test
    void withAnAimAClearlyNearerCardTakesOverAfterAMoment() {
        tracker.setAim(AIM);
        assertEquals(FAR_LEFT.c0().x(), feedTimes(CONFIRM, FAR_LEFT).quad().c0().x(), 3);
        TrackedCard card = feedTimes(CONFIRM + (int) (CardTracker.RIVAL_MS / 100) + 1, FAR_LEFT, MIDDLE);
        assertEquals(MIDDLE.c0().x(), card.quad().c0().x(), 3);
    }

    @Test
    void withAnAimCardsAboutAsNearDontTradePlaces() {
        tracker.setAim(AIM);
        CardQuad left = card(160, 140);
        CardQuad right = card(330, 140);
        assertEquals(left.c0().x(), feedTimes(CONFIRM, left).quad().c0().x(), 3);
        TrackedCard card = feedTimes(30, left, right);
        assertEquals(left.c0().x(), card.quad().c0().x(), 3, "the other card is nearer, but not by enough to switch");
    }

    @Test
    void withAnAimAFaintOutlineNearTheMiddleDoesNotBeatARealCard() {
        tracker.setAim(AIM);
        CardQuad real = card(60, 140);
        for (int i = 0; i < 40; i++) {
            if (i % 5 == 0) {
                feed(real, MIDDLE);
            } else {
                feed(real);
            }
        }
        assertEquals(real.c0().x(), tracker.update(List.of(real), now += 100).quad().c0().x(), 3);
    }

    private TrackedCard feedTimes(int times, CardQuad... candidates) {
        TrackedCard out = null;
        for (int i = 0; i < times; i++) {
            out = feed(candidates);
        }
        return out;
    }

    @Test
    void oneFrameIsNotEnough() {
        assertNull(feed(A));
        assertNull(feedTimes(CONFIRM - 2, A));
        assertNotNull(feed(A));
    }

    @Test
    void flickeringFirstChoiceLosesToAPersistentSecondChoice() {
        TrackedCard out = null;
        for (int i = 0; i < 12; i++) {
            // A new wrong outline tops every frame, far from the others.
            CardQuad wrong = card(10 + (i % 6) * 50, 260);
            out = feed(wrong, B);
        }
        assertNotNull(out);
        assertEquals(B.c0(), out.quad().c0());
    }

    @Test
    void roughCandidatesNeverConfirm() {
        assertNull(feedTimes(10, rough(100, 100)));
    }

    @Test
    void roughCandidatesDoNotUseUpARankMatch() {
        assertNotNull(feedTimes(CONFIRM, rough(400, 300), A));
    }

    @Test
    void aHandfulOfMissedFramesKeepsTheTrack() {
        TrackedCard first = feedTimes(CONFIRM, A);
        TrackedCard after = feedTimes(BRIDGED);
        assertNotNull(after);
        assertEquals(first.id(), after.id());
    }

    @Test
    void tooManyMissesEndTheTrack() {
        feedTimes(CONFIRM, A);
        assertNull(feedTimes(ENDS_TRACK));
    }

    @Test
    void aFlippedOutlineIsTheSameTrackAndKeepsItsCornerOrder() {
        TrackedCard first = feedTimes(CONFIRM, A);
        TrackedCard after = feed(A.flipped());
        assertEquals(first.id(), after.id());
        assertEquals(A.c0(), after.quad().c0());
    }

    @Test
    void slowDriftFollowsTheCardAsOneTrack() {
        TrackedCard first = feedTimes(CONFIRM, A);
        TrackedCard last = null;
        for (int i = 1; i <= 30; i++) {
            last = feed(card(100 + i * 5, 100));
        }
        assertEquals(first.id(), last.id());
        assertEquals(250, last.quad().c0().x(), 1e-9);
    }

    @Test
    void aJumpStartsANewTrack() {
        TrackedCard first = feedTimes(CONFIRM, A);
        TrackedCard after = feedTimes(CONFIRM + ENDS_TRACK, B);
        assertNotEquals(first.id(), after.id());
    }

    @Test
    void jitterKeepsItSteadyAndRealMovementRestartsTheClock() {
        feedTimes(CONFIRM, A);
        long startedAt = 1100;
        TrackedCard jittery = null;
        for (int i = 0; i < 10; i++) {
            jittery = feed(card(100 + (i % 2) * 2, 100));
        }
        assertEquals(startedAt, jittery.steadySinceMs());
        // One frame is a suspected bad fit. The next in agreement moves the outline and the median soon follows.
        assertEquals(startedAt, feed(card(112, 100)).steadySinceMs());
        feed(card(112, 100));
        TrackedCard moved = feed(card(112, 100));
        assertEquals(now, moved.steadySinceMs());
        assertEquals(0, moved.steadyForMs(now));
    }

    @Test
    void slowCreepCountsAsMovement() {
        feedTimes(CONFIRM, A);
        TrackedCard last = null;
        for (int i = 1; i <= 8; i++) {
            last = feed(card(100 + i * 2, 100));
        }
        assertTrue(last.steadySinceMs() > 1100, "steady since " + last.steadySinceMs());
    }

    @Test
    void staysOnItsCardWhenANeighbourIsAboutAsStrong() {
        TrackedCard first = feedTimes(CONFIRM + 2, A, B);
        TrackedCard swapped = feedTimes(10, B, A);
        // B now out-ranks A, but only 2:1 at most after the running average has caught up.
        assertNotNull(swapped);
        assertTrue(swapped.id() == first.id() || swapped.quad().c0().equals(B.c0()));
        TrackedCard even = feedTimes(3, A, B);
        assertEquals(swapped.id(), even.id());
    }

    @Test
    void persistentArtBoxLosesToTheEnclosingCardEvenWhenRankedFirst() {
        CardQuad outer = new CardQuad(new Corner(100, 50), new Corner(300, 50), new Corner(300, 330), new Corner(100, 330), 1, true);
        CardQuad artBox = new CardQuad(new Corner(120, 90), new Corner(280, 90), new Corner(280, 220), new Corner(120, 220), 1, true);
        TrackedCard out = feedTimes(8, artBox, outer);
        assertEquals(outer.c0(), out.quad().c0());
    }

    @Test
    void refocusesFirstTimeThenOnlyOnRealMovement() {
        RefocusPolicy policy = new RefocusPolicy();
        assertFalse(policy.hasFocus());
        assertTrue(policy.shouldRefocus(A, 0));
        assertTrue(policy.hasFocus());
        assertFalse(policy.shouldRefocus(card(105, 100), 5000), "small shift");
        assertFalse(policy.shouldRefocus(card(130, 100), 500), "large shift too soon");
        assertFalse(policy.shouldRefocus(card(170, 100), 1500), "large shift, but only just");
        assertFalse(policy.shouldRefocus(card(100, 100), 1600), "and it came back");
        assertFalse(policy.shouldRefocus(card(170, 100), 1700), "the wait starts again");
        assertTrue(policy.shouldRefocus(card(170, 100), 1700 + RefocusPolicy.SUSTAIN_MS), "large shift that lasted");
    }

    @Test
    void refocusesWhenDistanceChanges() {
        RefocusPolicy policy = new RefocusPolicy();
        policy.shouldRefocus(A, 0);
        CardQuad nearer = new CardQuad(new Corner(84, 72), new Corner(259, 72), new Corner(259, 317), new Corner(84, 317), 1, true);
        assertFalse(policy.shouldRefocus(nearer, 2000));
        assertTrue(policy.shouldRefocus(nearer, 2000 + RefocusPolicy.SUSTAIN_MS));
    }

    @Test
    void aRebaseMeansANewOutlineOfTheSameCardIsNotMovement() {
        RefocusPolicy policy = new RefocusPolicy();
        policy.shouldRefocus(A, 0);
        CardQuad larger = new CardQuad(new Corner(84, 72), new Corner(259, 72), new Corner(259, 317), new Corner(84, 317), 1, true);
        policy.rebase(larger);
        assertFalse(policy.shouldRefocus(larger, 2000));
    }

    @Test
    void aConfirmedCardWhereFocusWasAskedForKeepsThatFocus() {
        RefocusPolicy policy = new RefocusPolicy();
        policy.shouldRefocus(A, 0);
        policy.adopt(A);
        assertFalse(policy.shouldRefocus(A, 2000));
        policy.adopt(card(300, 100));
        assertTrue(policy.shouldRefocus(card(300, 100), 2001), "a card elsewhere needs its own");
    }

    @Test
    void lockIsReusedOnlyWhileFreshAndHeld() {
        FocusSettler settler = new FocusSettler();
        assertFalse(settler.lockStillGood(0));
        settler.begin(1, true, 100);
        settler.onTriggerApplied();
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_FOCUSED_LOCKED, 300);
        assertTrue(settler.lockStillGood(400));
        assertFalse(settler.lockStillGood(300 + FocusSettler.LOCK_FRESH_MS + 1), "too old");
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_PASSIVE_SCAN, 500);
        assertFalse(settler.lockStillGood(500), "the camera no longer reports the lock");
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_FOCUSED_LOCKED, 600);
        settler.lockCancelled();
        assertFalse(settler.lockStillGood(600), "cancelled");
    }

    @Test
    void resetMakesThePolicyFocusAgain() {
        RefocusPolicy policy = new RefocusPolicy();
        policy.shouldRefocus(A, 0);
        policy.reset();
        assertTrue(policy.shouldRefocus(A, 1));
    }

    @Test
    void settlerIgnoresStaleLockUntilTheTriggerIsApplied() {
        FocusSettler settler = new FocusSettler();
        settler.begin(1, true, 100);
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_FOCUSED_LOCKED, 110);
        assertFalse(settler.status().settled());
        settler.onTriggerApplied();
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_ACTIVE_SCAN, 120);
        assertFalse(settler.status().settled());
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_FOCUSED_LOCKED, 350);
        assertEquals(new FocusStatus(1, FocusStatus.Outcome.FOCUSED, 250), settler.status());
    }

    @Test
    void settlerReportsFailedFocus() {
        FocusSettler settler = new FocusSettler();
        settler.begin(2, true, 0);
        settler.onTriggerApplied();
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, 40);
        assertEquals(FocusStatus.Outcome.NOT_FOCUSED, settler.status().outcome());
        assertTrue(settler.status().settled());
    }

    @Test
    void settlerTimesOutEvenIfTheTriggerResultNeverCame() {
        FocusSettler settler = new FocusSettler();
        settler.begin(3, true, 0);
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_INACTIVE, FocusSettler.TIMEOUT_MS - 1);
        assertFalse(settler.status().settled());
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_INACTIVE, FocusSettler.TIMEOUT_MS);
        assertEquals(FocusStatus.Outcome.TIMED_OUT, settler.status().outcome());
    }

    @Test
    void settlerHandlesNoAutofocusAndMissingState() {
        FocusSettler settler = new FocusSettler();
        settler.begin(4, false, 0);
        assertEquals(FocusStatus.Outcome.NO_AUTOFOCUS, settler.status().outcome());
        settler.begin(5, true, 0);
        settler.onTriggerApplied();
        settler.onAfState(null, 10);
        assertTrue(settler.status().settled());
    }

    @Test
    void settlerKeepsItsFirstOutcome() {
        FocusSettler settler = new FocusSettler();
        settler.begin(6, true, 0);
        settler.onTriggerApplied();
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_FOCUSED_LOCKED, 10);
        settler.onAfState(Camera2Values.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, 20);
        assertEquals(FocusStatus.Outcome.FOCUSED, settler.status().outcome());
    }

    @Test
    void levelsThatGuaranteeTheStreamsAreNotAnOrderedRange() {
        assertTrue(HardwareSupport.guaranteesPreviewYuvJpeg(Camera2Values.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED));
        assertTrue(HardwareSupport.guaranteesPreviewYuvJpeg(Camera2Values.INFO_SUPPORTED_HARDWARE_LEVEL_FULL));
        assertTrue(HardwareSupport.guaranteesPreviewYuvJpeg(Camera2Values.INFO_SUPPORTED_HARDWARE_LEVEL_3));
        assertFalse(HardwareSupport.guaranteesPreviewYuvJpeg(Camera2Values.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY));
        assertFalse(HardwareSupport.guaranteesPreviewYuvJpeg(Camera2Values.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL));
        assertFalse(HardwareSupport.guaranteesPreviewYuvJpeg(null));
    }

    @Test
    void aPersistingOutlineWithLowCardEvidenceIsNotConfirmed() {
        CardTracker tracker = new CardTracker();
        CardQuad low = new CardQuad(new Corner(100, 100), new Corner(200, 100), new Corner(200, 240), new Corner(100, 240), 1,
                true, CardQuad.MIN_CARD_EVIDENCE / 2);
        for (int i = 0; i < 20; i++) {
            assertNull(tracker.update(List.of(low), i * 100L));
        }
    }

    @Test
    void slowFinderStillConfirmsOnFrameCountAndKeepsItsTrackForThreeMisses() {
        long t = 0;
        TrackedCard card = null;
        for (int i = 0; i < CardTracker.CONFIRM_FRAMES; i++, t += 500) {
            card = tracker.update(List.of(A), t);
        }
        assertNotNull(card);
        for (int i = 0; i < CardTracker.MIN_MISSES; i++, t += 500) {
            assertNotNull(tracker.update(List.of(), t));
        }
        assertNull(tracker.update(List.of(), t));
    }

    @Test
    void confirmationTakesAbout350MsAtFifteenFramesPerSecond() {
        long confirmedAt = -1;
        for (long t = 0; t < 1000 && confirmedAt < 0; t += 66) {
            if (tracker.update(List.of(A), t) != null) {
                confirmedAt = t;
            }
        }
        assertTrue(confirmedAt >= 300 && confirmedAt <= 500, "confirmed at " + confirmedAt);
    }

    @Test
    void smallNoiseIsSmoothedAndRealMovementIsFollowedOnTheSecondFrame() {
        for (long t = 0; t < 700; t += 66) {
            tracker.update(List.of(A), t);
        }
        TrackedCard noisy = tracker.update(List.of(card(101, 100)), 700);
        assertTrue(noisy.quad().c0().x() - 100 < 0.5, "smoothed to " + noisy.quad().c0().x());
        assertEquals(101, noisy.seenQuad().c0().x(), 1e-9);
        assertTrue(tracker.update(List.of(card(112, 100)), 766).quad().c0().x() < 102, "one frame could be a bad fit");
        TrackedCard moved = tracker.update(List.of(card(112, 100)), 832);
        assertEquals(112, moved.quad().c0().x(), 1e-9);
    }

    @Test
    void anExternalMotionEstimateKeepsATrackThroughAJump() {
        CardQuad quad = new CardQuad(new Corner(100, 100), new Corner(200, 100), new Corner(200, 240), new Corner(100, 240), 1, true);
        CardTracker tracker = new CardTracker();
        TrackedCard card = null;
        for (int i = 0; i < 5; i++) {
            card = tracker.update(List.of(quad), i * 100L);
        }
        int id = card.id();
        // Moves much further than the matching tolerance allows without a hint.
        TrackedCard after = tracker.update(List.of(quad.shifted(60, 0)), 500, 60, 0);
        assertEquals(id, after.id());
    }
}
