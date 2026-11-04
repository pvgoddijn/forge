package forge.cardocr.finder;

import static org.junit.jupiter.api.Assertions.*;

import forge.cardocr.assist.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** With no card in view nothing may be confirmed, while cards with awkward corners must still be found. */
class NoCardTest {

    private static final int FRAMES = 10;
    private final CardFinder finder = new CardFinder();

    private TrackedCard lastTracked(byte[] luma, int width, int height) {
        CardTracker tracker = new CardTracker();
        TrackedCard card = null;
        for (int i = 0; i < FRAMES; i++) {
            card = tracker.update(finder.find(luma, width, height).candidates(), i * 100L);
        }
        return card;
    }

    @Test
    void drawnScenesWithNoCardAreNeverConfirmed() {
        List<String> confirmed = new ArrayList<>();
        for (NoCardScenes.Scene scene : NoCardScenes.drawn()) {
            if (lastTracked(scene.luma(), scene.width(), scene.height()) != null) {
                confirmed.add(scene.name());
            }
        }
        assertEquals(List.of(), confirmed);
    }

    @Test
    void realCropsWithNoCardAreNeverConfirmed() {
        List<NoCardScenes.Scene> crops = NoCardScenes.crops();
        Assumptions.assumeFalse(crops.isEmpty(), "No photos");
        List<String> confirmed = new ArrayList<>();
        for (NoCardScenes.Scene scene : crops) {
            if (lastTracked(scene.luma(), scene.width(), scene.height()) != null) {
                confirmed.add(scene.name());
            }
        }
        assertEquals(List.of(), confirmed);
    }

    /** Refits start from an outline, so they mustn't talk a pipeline into confirming a scene with no card. */
    @Test
    void localTrackingConfirmsNothingOnScenesWithNoCardAndReportsWhatItShows() {
        List<NoCardScenes.Scene> scenes = new ArrayList<>(NoCardScenes.drawn());
        scenes.addAll(NoCardScenes.crops());
        List<String> confirmed = new ArrayList<>();
        int shownScenes = 0;
        for (NoCardScenes.Scene scene : scenes) {
            AssistedPipeline pipeline = new AssistedPipeline(null);
            boolean shown = false;
            long now = 10_000;
            for (int i = 0; i < 40; i++, now += 40) {
                FindResult found = pipeline.find(finder, scene.luma(), scene.width(), scene.height(), now);
                AssistedPipeline.Outcome out = pipeline.onFrame(new AssistedPipeline.Input(found, scene.luma(), scene.width(), scene.height(), now,
                        new FocusStatus(0, FocusStatus.Outcome.PENDING, -1), true, 300, false));
                shown |= out.shown() != null;
                if (out.card() != null) {
                    confirmed.add(scene.name());
                    break;
                }
            }
            shownScenes += shown ? 1 : 0;
        }
        System.out.println("No-card scenes with a provisional outline: " + shownScenes + " of " + scenes.size());
        assertEquals(List.of(), confirmed);
    }

    @Test
    void autoCaptureDoesNotFireOnAComputerCase() {
        NoCardScenes.Scene scene = NoCardScenes.drawn().stream()
                .filter(s -> s.name().startsWith("case-on-desk")).findFirst().orElseThrow();
        AssistedPipeline pipeline = new AssistedPipeline(null);
        for (int i = 0; i < 30; i++) {
            AssistedPipeline.Outcome out = pipeline.onFrame(new AssistedPipeline.Input(
                    finder.find(scene.luma(), scene.width(), scene.height()), scene.luma(), scene.width(),
                    scene.height(), 10_000 + i * 100L, new FocusStatus(0, FocusStatus.Outcome.FOCUSED, 50), true, 800,
                    false));
            assertFalse(out.captureNow(), "frame " + i);
            assertEquals(AssistedPipeline.FocusAction.NONE, out.focusAction(), "frame " + i);
        }
    }

    @Test
    void squareCornersWithDarkBorderOnDarkBackgroundAreStillFound() {
        CardQuad truth = SyntheticCard.corners(320, 240, 320, 8, 0);
        byte[] luma = SyntheticCard.blank(640, 480, 38);
        SyntheticCard.draw(luma, 640, 480, truth, 12, 150, 90, 0);
        FindResult found = finder.find(luma, 640, 480);
        assertFalse(found.candidates().isEmpty());
        assertTrue(SyntheticCard.cornerError(found.candidates().get(0), truth) < 8,
                "error " + SyntheticCard.cornerError(found.candidates().get(0), truth));
        TrackedCard card = lastTracked(luma, 640, 480);
        assertNotNull(card, "a square card has to be confirmable from its other evidence");
        assertTrue(SyntheticCard.cornerError(card.quad(), truth) < 8);
    }

    @Test
    void uprightCardBeatsItsArtBoxAndASidewaysCardStaysSideways() {
        CardQuad upright = SyntheticCard.corners(320, 240, 380, 3, 0);
        byte[] luma = SyntheticCard.blank(640, 480, 190);
        SyntheticCard.draw(luma, 640, 480, upright, 20, 170, 60);
        CardQuad top = finder.find(luma, 640, 480).candidates().get(0);
        assertTrue(SyntheticCard.cornerError(top, upright) < 8, "upright, error " + SyntheticCard.cornerError(top, upright));

        CardQuad sideways = SyntheticCard.corners(320, 240, 380, 93, 0);
        byte[] turned = SyntheticCard.blank(640, 480, 190);
        SyntheticCard.draw(turned, 640, 480, sideways, 20, 170, 60);
        CardQuad topTurned = finder.find(turned, 640, 480).candidates().get(0);
        assertTrue(SyntheticCard.cornerError(topTurned, sideways) < 8,
                "sideways, error " + SyntheticCard.cornerError(topTurned, sideways));
    }
}
