package forge.cardocr.assist;

import forge.cardocr.finder.*;
import java.util.*;

/**
 * The one outline on screen. It appears from the first plausible candidate, becomes the tracked card
 * when that's confirmed without moving, stays through short gaps, and filters out the fit noise that
 * read as flicker: in run3.log the outline moved 2.4 px per frame at the median and over 7.5 px on one
 * frame in ten, which is 5 and 17 screen pixels.
 */
public final class OutlineDisplay {

    /** With nothing supporting it for this long, the outline goes. */
    static final long HIDE_AFTER_MS = 500;
    /** A plausible outline elsewhere replaces an unsupported one after this long. */
    static final long SWITCH_AFTER_MS = 200;
    /** A candidate this close, as a share of the long side, is the shown outline. */
    private static final double SAME_OUTLINE = 0.25;
    /** Differences up to this share of the long side are noise, and followed slowly. */
    static final double NOISE_SHARE = 0.012;
    /** Differences from this share on are movement, and followed at once. */
    static final double MOVED_SHARE = 0.05;
    private static final double SLOW_TAU_MS = 180;

    /**
     * @param quad the outline to draw
     * @param confirmed true for the solid tracked outline, false for the thin provisional one
     * @param sightings frames that have supported it
     */
    public record Shown(CardQuad quad, boolean confirmed, int sightings) {
    }

    private CardQuad quad;
    private boolean confirmed;
    private int sightings;
    private long supportedAtMs;
    private long updatedAtMs;

    /** @return what to draw, or null for nothing */
    public Shown update(List<CardQuad> candidates, TrackedCard card, ImageMotion motion, long nowMs) {
        if (quad != null) {
            quad = OverlayCompensation.apply(quad, motion);
        }
        CardQuad target = card != null ? (card.seenNow() ? card.quad() : null) : plausible(candidates, nowMs);
        if (card != null) {
            confirmed = true;
        } else if (target != null) {
            confirmed = false;
        }
        if (target != null) {
            quad = quad == null ? target : follow(target, nowMs);
            supportedAtMs = nowMs;
            sightings++;
        } else if (card == null && quad != null && nowMs - supportedAtMs >= HIDE_AFTER_MS) {
            quad = null;
            confirmed = false;
            sightings = 0;
        }
        updatedAtMs = nowMs;
        return quad == null ? null : new Shown(quad, confirmed, sightings);
    }

    /** The candidate to show: the one matching the shown outline, or the best one if it may be replaced. */
    private CardQuad plausible(List<CardQuad> candidates, long nowMs) {
        CardQuad best = null;
        CardQuad closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (CardQuad candidate : candidates) {
            if (candidate.refined() && candidate.evidence() >= CardQuad.MIN_CARD_EVIDENCE) {
                best = best == null ? candidate : best;
                if (quad != null) {
                    double distance = Quads.distance(candidate, quad);
                    if (distance < closestDistance) {
                        closest = candidate;
                        closestDistance = distance;
                    }
                }
            }
        }
        if (quad == null) {
            return best;
        }
        if (closest != null && closestDistance <= SAME_OUTLINE * Quads.longSide(quad)) {
            return closest;
        }
        if (best != null && nowMs - supportedAtMs >= SWITCH_AFTER_MS) {
            quad = null;
            sightings = 0;
            return best;
        }
        return null;
    }

    private CardQuad follow(CardQuad target, long nowMs) {
        CardQuad aligned = Quads.alignedTo(target, quad);
        double share = Quads.distance(aligned, quad) / Quads.longSide(quad);
        double slow = 1 - Math.exp(-(nowMs - updatedAtMs) / SLOW_TAU_MS);
        double moved = Math.max(0, Math.min(1, (share - NOISE_SHARE) / (MOVED_SHARE - NOISE_SHARE)));
        return Quads.between(quad, aligned, slow + (1 - slow) * moved);
    }
}
