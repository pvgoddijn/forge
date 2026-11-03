package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.finder.*;
import java.util.*;
import org.junit.jupiter.api.*;

class LocalTrackingTest {

    private static final CardQuad A = new CardQuad(new Corner(100, 100), new Corner(300, 100), new Corner(300, 380), new Corner(100, 380), 1, true, 0.8);
    private static final CardQuad FAR = A.shifted(200, 0);
    private static final List<CardQuad> SEEDS = List.of(A);

    private static FindResult local(CardQuad... fits) {
        return new FindResult(List.of(fits), 0, 1, 1, true);
    }

    private static FindResult search() {
        return new FindResult(List.of(A), 1, 1, 2);
    }

    @Test
    void searchesWithNothingToRefitOrBeforeAnySearch() {
        LocalTracking tracking = new LocalTracking();
        assertFalse(tracking.useLocal(SEEDS, true, 0));
        tracking.onResult(search(), List.of(), 0);
        assertFalse(tracking.useLocal(List.of(), true, 10));
        assertTrue(tracking.useLocal(SEEDS, true, 10));
    }

    @Test
    void searchesAgainOnSchedule() {
        LocalTracking tracking = new LocalTracking();
        tracking.onResult(search(), List.of(), 0);
        assertTrue(tracking.useLocal(SEEDS, true, LocalTracking.HELD_SEARCH_PERIOD_MS - 1));
        assertFalse(tracking.useLocal(SEEDS, true, LocalTracking.HELD_SEARCH_PERIOD_MS + 2));
        assertFalse(tracking.useLocal(SEEDS, false, LocalTracking.CANDIDATE_SEARCH_PERIOD_MS + 2));
    }

    @Test
    void aFailedRefitSearchesOnTheNextFrame() {
        LocalTracking tracking = new LocalTracking();
        tracking.onResult(search(), List.of(), 0);
        tracking.onResult(local(A), SEEDS, 33);
        assertTrue(tracking.useLocal(SEEDS, true, 66));
        tracking.onResult(local(FAR), SEEDS, 66);
        assertFalse(tracking.useLocal(SEEDS, true, 99));
    }

    @Test
    void refitsDoNotChargeOtherTracksOrStartNewOnes() {
        CardTracker tracker = new CardTracker();
        long t = 0;
        for (int i = 0; i < 5; i++, t += 100) {
            tracker.update(List.of(A, FAR), t);
        }
        for (int i = 0; i < 20; i++, t += 33) {
            tracker.update(List.of(A), t, ImageMotion.NONE, true);
        }
        assertTrue(tracker.holding());
        tracker.update(List.of(A.shifted(0, 300)), t, ImageMotion.NONE, true);
        assertEquals(1, tracker.seeds(t).size());
    }

    @Test
    void aRefitThatGrewFromWhatASearchFoundIsDropped() {
        CardTracker tracker = new CardTracker();
        long t = 0;
        for (int i = 0; i < 5; i++, t += 100) {
            tracker.update(List.of(A), t);
        }
        assertTrue(tracker.holding());
        CardQuad near = new CardQuad(new Corner(99, 99), new Corner(301, 99), new Corner(301, 381), new Corner(99, 381), 1, true, 0.8);
        CardQuad grown = new CardQuad(new Corner(90, 90), new Corner(310, 90), new Corner(310, 390), new Corner(90, 390), 1, true, 0.8);
        assertEquals(List.of(near), tracker.undrifted(List.of(near, grown)));
    }

    @Test
    void refitsAloneDoNotConfirm() {
        CardTracker tracker = new CardTracker();
        tracker.update(List.of(A), 0);
        for (long t = 33; t < 1000; t += 33) {
            assertNull(tracker.update(List.of(A), t, ImageMotion.NONE, true), "no second search yet");
        }
        assertNotNull(tracker.update(List.of(A), 1000));
    }
}
