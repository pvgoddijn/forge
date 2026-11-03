package forge.cardocr.assist;

import forge.cardocr.finder.*;

/**
 * Refocuses only when the card has really moved or is a new one, because every refocus can make the
 * lens hunt and holds up capture. A noisy outline can cross a limit for a frame or two, so the
 * movement must last.
 */
public final class RefocusPolicy {

    /** Guess: centre shift, as a share of the long side. Sideways movement barely changes distance, so this is generous. */
    static final double MIN_SHIFT = 0.25;
    /** Guess: relative area change, which means a change of distance, that needs a refocus. */
    static final double MIN_AREA_CHANGE = 0.25;
    /** Guess: the shortest time between refocuses. */
    static final long MIN_INTERVAL_MS = 1000;
    /** Guess: the movement must hold this long, longer than the outline's noise lasts. */
    static final long SUSTAIN_MS = 300;

    private CardQuad focused;
    private long focusedAtMs;
    private long movedSinceMs = -1;

    /** Whether to refocus on this outline. If true, it is remembered as the focused one. Feed every frame. */
    public boolean shouldRefocus(CardQuad quad, long nowMs) {
        if (focused != null) {
            if (!movedFromFocused(quad)) {
                movedSinceMs = -1;
                return false;
            }
            if (movedSinceMs < 0) {
                movedSinceMs = nowMs;
            }
            if (nowMs - movedSinceMs < SUSTAIN_MS || nowMs - focusedAtMs < MIN_INTERVAL_MS) {
                return false;
            }
        }
        focused = quad;
        focusedAtMs = nowMs;
        movedSinceMs = -1;
        return true;
    }

    private boolean movedFromFocused(CardQuad quad) {
        double shift = Quads.centre(quad).distanceTo(Quads.centre(focused));
        double areaChange = Math.abs(Quads.area(quad) / Quads.area(focused) - 1);
        return shift > MIN_SHIFT * Quads.longSide(focused) || areaChange > MIN_AREA_CHANGE;
    }

    /** The same card now has this outline, but nothing moved, so it mustn't read as movement later. */
    public void rebase(CardQuad quad) {
        if (focused != null) {
            focused = quad;
            movedSinceMs = -1;
        }
    }

    /** A card was confirmed. If focus was already asked for around there, it needn't be asked for again. */
    public void adopt(CardQuad quad) {
        if (focused != null && movedFromFocused(quad)) {
            reset();
        }
    }

    public boolean hasFocus() {
        return focused != null;
    }

    public void reset() {
        focused = null;
        movedSinceMs = -1;
    }
}
