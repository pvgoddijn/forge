package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * The pattern seen on the phone: one still card, with the finder's top choices alternating between
 * the sleeve, the card edge and the printed frame, and a fit of the art box among them. Runs through
 * the whole pipeline, at the phone's 110 ms per frame with its 220 ms focus time.
 */
class OutlineAlternationTest {

    private static final int W = 640;
    private static final int H = 480;
    private static final long FRAME_MS = 110;
    private static final long STEADY_MS = 800;
    private static final long FOCUS_MS = 220;
    private static final byte[] LUMA = new byte[W * H];

    /** A card with its centre at (x, y) and this height, turned so that it's upright. */
    private static CardQuad outline(double x, double y, double height, double score) {
        double halfH = height / 2;
        double halfW = halfH * 63 / 88;
        return new CardQuad(new Corner(x - halfW, y - halfH), new Corner(x + halfW, y - halfH), new Corner(x + halfW, y + halfH),
                new Corner(x - halfW, y + halfH), score, true);
    }

    private static final double CARD_H = 300;
    private static final double SLEEVE_H = 316;
    private static final double FRAME_H = 272;
    private static final double ART_H = 180;

    /** Every frame has some of the outlines, and which leads keeps changing. */
    private static List<CardQuad> alternating(int frame, double x, double y) {
        CardQuad card = outline(x, y, CARD_H, 1);
        CardQuad sleeve = outline(x, y, SLEEVE_H, 0.9);
        CardQuad printed = outline(x, y, FRAME_H, 1.1);
        CardQuad art = outline(x, y - 25, ART_H, 0.8);
        return switch (frame % 5) {
            case 0 -> List.of(card, printed, art);
            case 1 -> List.of(printed, sleeve);
            case 2 -> List.of(sleeve, card, art);
            case 3 -> List.of(printed, art);
            default -> List.of(sleeve, printed);
        };
    }

    private final AssistedPipeline pipeline = new AssistedPipeline(null);
    private long now = 10_000;
    private long start = now;
    private int generation;
    private long focusAskedAt;
    private int refocuses;

    private AssistedPipeline.Outcome step(List<CardQuad> candidates) {
        now += FRAME_MS;
        FocusStatus focus = generation == 0 ? FocusStatus.NONE
                : now - focusAskedAt >= FOCUS_MS ? new FocusStatus(generation, FocusStatus.Outcome.FOCUSED, FOCUS_MS)
                : new FocusStatus(generation, FocusStatus.Outcome.PENDING, -1);
        AssistedPipeline.Outcome out = pipeline.onFrame(new AssistedPipeline.Input(new FindResult(candidates, 0, 0, 0), LUMA, W, H, now, focus, true,
                STEADY_MS, false, new FrameMotion(ImageMotion.NONE, 0.03, 0.05)));
        if (out.focusAction() == AssistedPipeline.FocusAction.FOCUS_ON_CARD) {
            generation = out.focusGeneration();
            focusAskedAt = now;
            refocuses++;
        }
        return out;
    }

    private static double height(CardQuad q) {
        return q.c0().distanceTo(q.c3());
    }

    @Test
    void anAlternatingOutlineKeepsOneSteadyCardAndCapturesPromptly() {
        long captureAt = -1;
        Set<Integer> ids = new HashSet<>();
        double smallest = Double.MAX_VALUE;
        double largest = 0;
        long lastSteadySince = -1;
        int resets = 0;
        int refocusesAtCapture = -1;
        for (int i = 0; i < 70; i++) {
            AssistedPipeline.Outcome out = step(alternating(i, 320, 240));
            if (out.captureNow() && captureAt < 0) {
                captureAt = now - start;
                refocusesAtCapture = refocuses;
            }
            TrackedCard card = out.card();
            if (card == null) {
                continue;
            }
            ids.add(card.id());
            if (now - start > 1500) {
                // Once it has settled on the outermost.
                smallest = Math.min(smallest, height(card.quad()));
                largest = Math.max(largest, height(card.quad()));
                resets += lastSteadySince >= 0 && card.steadySinceMs() != lastSteadySince ? 1 : 0;
                lastSteadySince = card.steadySinceMs();
            }
        }
        assertEquals(1, ids.size(), "one card, one identity");
        assertEquals(0, resets, "steady clock resets");
        assertTrue(largest / smallest < 1.01, smallest + " to " + largest);
        assertEquals(SLEEVE_H, largest, 3, "settles on the outermost outline");
        assertTrue(captureAt > 0 && captureAt <= 1500, "captured at " + captureAt);
        assertTrue(refocusesAtCapture <= 2, "refocuses before capture " + refocusesAtCapture);
        assertTrue(refocuses <= 2, "refocuses overall " + refocuses);
    }

    @Test
    void aCardReallyMovingWhileOutlinesAlternateResetsSteadyAndRefocuses() {
        int i = 0;
        for (; i < 25; i++) {
            step(alternating(i, 200, 240));
        }
        int before = refocuses;
        TrackedCard last = null;
        // 12 pixels a frame, about a third of the card's height per second.
        double x = 200;
        long resetAfterMoveStart = -1;
        long moveStart = now;
        long since = step(alternating(i++, x, 240)).card().steadySinceMs();
        for (int j = 0; j < 20; j++) {
            x += 12;
            last = step(alternating(i++, x, 240)).card();
            assertNotNull(last, "lost at step " + j);
            if (resetAfterMoveStart < 0 && last.steadySinceMs() != since) {
                resetAfterMoveStart = now - moveStart;
            }
        }
        assertTrue(resetAfterMoveStart > 0 && resetAfterMoveStart <= 500, "reset after " + resetAfterMoveStart);
        assertTrue(refocuses > before, "a real move refocuses");
        assertEquals(x, (last.quad().c0().x() + last.quad().c2().x()) / 2, 15, "follows the card");
    }

    @Test
    void aDifferentCardElsewhereIsFollowedPromptly() {
        int i = 0;
        for (; i < 15; i++) {
            step(alternating(i, 170, 240));
        }
        int firstId = step(alternating(i++, 170, 240)).card().id();
        int before = refocuses;
        long swappedAt = now;
        long followedAfter = -1;
        for (int j = 0; j < 20 && followedAfter < 0; j++) {
            TrackedCard card = step(alternating(i++, 470, 240)).card();
            if (card != null && card.id() != firstId) {
                followedAfter = now - swappedAt;
            }
        }
        assertTrue(followedAfter > 0 && followedAfter <= 1000, "followed after " + followedAfter);
        assertTrue(refocuses > before, "a new card refocuses");
    }

    @Test
    void aBriefOutlineElsewhereDoesNotTakeOver() {
        int i = 0;
        TrackedCard card = null;
        for (; i < 15; i++) {
            card = step(alternating(i, 170, 240)).card();
        }
        int id = card.id();
        long since = card.steadySinceMs();
        for (int j = 0; j < 4; j++) {
            List<CardQuad> both = new ArrayList<>();
            both.add(outline(470, 240, 300, 3));
            both.addAll(alternating(i++, 170, 240));
            card = step(both).card();
        }
        assertEquals(id, card.id());
        assertEquals(since, card.steadySinceMs());
    }

    @Test
    void anOutlierOnOneFrameDoesNotResetSteadyButACloserCardDoes() {
        CardTracker tracker = new CardTracker();
        CardQuad held = outline(320, 240, 300, 1);
        TrackedCard card = null;
        long t = 0;
        for (; t < 1000; t += 100) {
            card = tracker.update(List.of(held), t);
        }
        long since = card.steadySinceMs();
        assertEquals(since, tracker.update(List.of(outline(330, 240, 300, 1)), t).steadySinceMs(), "one moved fit");
        t += 100;
        assertEquals(since, tracker.update(List.of(held), t).steadySinceMs());
        // Nearer and staying nearer: another size, for longer than a variant outline would be alone.
        long steadyAfter = since;
        for (int i = 0; i < 8; i++) {
            t += 100;
            steadyAfter = tracker.update(List.of(outline(320, 240, 345, 1)), t).steadySinceMs();
        }
        assertNotEquals(since, steadyAfter);
    }

    @Test
    void aTransientOrSkewedOuterOutlineDoesNotReshapeTheCard() {
        CardTracker tracker = new CardTracker();
        CardQuad held = outline(320, 240, 300, 1);
        CardQuad wedge = new CardQuad(new Corner(165, 50), new Corner(428, 85), new Corner(450, 400), new Corner(205, 400), 0.5, true);
        TrackedCard card = null;
        long t = 0;
        for (; t < 2000; t += 100) {
            card = tracker.update(List.of(held, wedge), t);
        }
        assertEquals(300, height(card.quad()), 3, "the wedge reaches too far out");
        for (int i = 0; i < 2; i++, t += 100) {
            card = tracker.update(List.of(held, outline(320, 240, 316, 0.5)), t);
        }
        assertEquals(300, height(card.quad()), 3, "not yet around for long");
    }

    @Test
    void outlinesGoingAwayForLongLetACardComeBackWithItsIdentity() {
        CardTracker tracker = new CardTracker();
        CardQuad held = outline(320, 240, 300, 1);
        TrackedCard card = null;
        long t = 0;
        for (; t < 1000; t += 100) {
            card = tracker.update(List.of(held), t);
        }
        int id = card.id();
        for (int i = 0; i < 5; i++, t += 100) {
            tracker.update(List.of(), t);
        }
        TrackedCard back = null;
        for (int i = 0; i < 5; i++, t += 100) {
            back = tracker.update(List.of(held), t);
        }
        assertEquals(id, back.id());
    }
}
