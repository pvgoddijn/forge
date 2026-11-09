package forge.cardocr.assist;

import forge.cardocr.finder.*;
import java.util.*;

/**
 * Decides which outline is the card by following all refined candidates across frames, not only
 * the finder's first choice. An outline counts once it has persisted for a moment.
 *
 * <p>This removes errors that flicker between frames. It can't remove a wrong outline the finder
 * prefers on every frame of a still scene, because that one persists too. For the known case of an
 * art box it does prefer the enclosing outline. All tolerances are guesses.
 *
 * <p>The finder often offers several outlines for one card, such as the sleeve, the card edge and
 * the printed frame, and which of them wins changes from frame to frame. So what's reported is a
 * separate held outline, not any one track. Candidates sharing its centre and largely overlapping
 * it are the same card. They update it by smoothing, and only the outermost one that keeps turning
 * up can reshape it. Moving the same size means the card moved. Changing size around the same centre
 * means another outline, unless that lasts.
 */
public final class CardTracker {

    /**
     * An outline matches a track when its worst corner moved less than this share of its long side.
     * With a finder at 15 frames/s this is plenty for one frame: at a long side of 300 pixels it allows
     * 24 pixels, about 40 degrees/s of uncompensated turning.
     */
    static final double MATCH_TOLERANCE = 0.07;
    /** The tolerance grows by this share per ms since the track was last seen, since it can have moved further. */
    static final double MATCH_GROWTH_PER_MS = 0.00018;
    static final double MAX_MATCH_TOLERANCE = 0.16;
    /**
     * An outline stays "steady" while within this share of its long side of where it settled. Raised
     * from 2.5%: the anchor moves with the gyroscope, but moving the phone sideways doesn't show there,
     * and at card distance 2 to 5 mm of that is 2 to 4% of a card filling the frame's height.
     */
    static final double STEADY_TOLERANCE = 0.04;
    /** Frames an outline must be seen in before it can be the card. Keeps a slow finder from confirming on one frame. */
    static final int CONFIRM_FRAMES = 4;
    /**
     * Of those, how many must come from a search. A refit starts from the outline itself, so it can't
     * say on its own that the outline would be found again.
     */
    static final int CONFIRM_SEARCHES = 2;
    /** How long an outline must have been around to be the card, which is 6 or 7 frames at 15 frames/s. */
    static final long CONFIRM_MS = 350;
    /** A track survives at least this many unseen frames, however slow the finder. */
    static final int MIN_MISSES = 3;
    /** Time without a sighting after which a track ends, which is 6 frames at 15 frames/s. */
    static final long MAX_GAP_MS = 400;
    private static final int MAX_TRACKS = 10;
    /** How many outlines to refit while nothing is held, so an art box and its card go on competing. */
    private static final int MAX_SEEDS = 3;
    /**
     * A refit may differ from the area a search last found by this factor. The fit prefers the outermost line,
     * so refit after refit can otherwise walk out onto the background, one small step at a time.
     */
    static final double MAX_REFIT_GROWTH = 1.06;
    /**
     * Time constant of a track's running support and card evidence, so one odd frame can't decide. A
     * frame every 100 ms weighs 0.3, and one every 66 ms weighs 0.2.
     */
    private static final double RUNNING_TAU_MS = 280;
    /** Time constant for smoothing tiny differences between an outline and where it's expected. */
    private static final double SMOOTH_TAU_MS = 150;
    /** Differences up to this share of the long side are treated as all noise. */
    static final double NOISE_SHARE = 0.006;
    /** Differences from this share on are real movement, followed at once so the outline doesn't lag. */
    static final double MOVED_SHARE = 0.025;
    /** A different outline takes over only with this much more support than the current one. */
    private static final double SWITCH_MARGIN = 1.5;
    /** Share of an enclosing card's area that a printed art box covers. */
    private static final double MIN_ART_SHARE = 0.28;
    private static final double MAX_ART_SHARE = 0.48;
    /** A candidate is the held card if its centre is within this share of the long side... */
    static final double SAME_CARD_CENTRE = 0.12;
    /** ...and its worst corner within this share. An art box or a sideways rectangle is further out. */
    static final double SAME_CARD_CORNER = 0.25;
    /** A candidate this close, as a share of the long side, is the held outline itself. */
    static final double SAME_OUTLINE = 0.03;
    /** Size may differ by this share and still count as the same outline moved rather than another one. */
    private static final double RIGID_SIZE = 0.02;
    /** The centre must account for this much of the worst corner's move for the outline to count as moved. */
    private static final double RIGID_CENTRE = 0.6;
    /** A centre this far off, as a share of the long side, is the card moving even if the outline's size differs. */
    private static final double CENTRE_MOVED = 0.025;
    /** A move must go on from where the last frame's fit was, to within this share of the long side. */
    private static final double MOVE_CONSISTENT = 0.07;
    /** With only other outlines around for this long, the card did change size, or the held outline has gone. */
    static final long HELD_OUTLINE_GONE_MS = 300;
    /**
     * An outer outline must have held still this long before the held one grows to it. A card coming
     * closer also looks like a larger outline, but that one keeps changing.
     */
    static final long OUTER_MIN_AGE_MS = 500;
    /** And it must show up in this share of recent frames. */
    static final double OUTER_MIN_PRESENCE = 0.35;
    /**
     * An outer outline has its worst corner within this share of the long side. It covers a sleeve around
     * a card, and a card around its printed frame. Further out it's more likely table than card.
     */
    static final double OUTER_MAX_CORNER = 0.10;
    /** An outer outline must contain the held one, allowing this much for fits that wander. */
    private static final double OUTER_ROOM = 1.02;
    /** An outer outline must be this much larger by area to count as another outline. */
    private static final double OUTER_MIN_GROWTH = 1.02;
    /** It may have this much less evidence than the held outline. */
    private static final double OUTER_EVIDENCE_MARGIN = 0.25;
    /** Time constant of a track's share of recent frames. */
    private static final double PRESENCE_TAU_MS = 500;
    /** Time constant for reshaping the held outline when it's the same place seen another way. */
    static final double RESHAPE_TAU_MS = 250;
    /** Steadiness takes the median over this long, so a brief outlier can't reset it. */
    static final long STEADY_WINDOW_MS = 300;
    private static final int MIN_STEADY_SAMPLES = 3;
    /** A different card must out-rate the held one for this long before it takes over. */
    static final long RIVAL_MS = 600;
    /**
     * Guess: how much nearer the aim another card must be to take over, as a share of its long side.
     * Enough that two cards side by side, with the aim between them, don't trade places.
     */
    static final double NEARER_SHARE = 0.25;
    /** Guess: the support an outline needs, against the best, to be chosen for being nearer the aim. */
    static final double MIN_SHARE_OF_BEST = 0.5;
    /** A card seen again this soon where the last one was is the same one, with the same identity. */
    static final long RESUME_MS = 1000;

    private Corner aim;

    private static final class Track {
        final int id;
        final long firstSeenMs;
        CardQuad quad;
        CardQuad anchor;
        CardQuad lastSeen;
        long steadySinceMs;
        long lastSeenMs;
        long lastUpdateMs;
        int hits = 1;
        int searchHits = 1;
        int misses;
        double support;
        double evidence;
        double presence = 0.5;
        double searchArea;

        Track(int id, CardQuad quad, double weight, long nowMs) {
            this.id = id;
            this.quad = quad;
            this.anchor = quad;
            this.lastSeen = quad;
            this.firstSeenMs = nowMs;
            this.steadySinceMs = nowMs;
            this.lastSeenMs = nowMs;
            this.lastUpdateMs = nowMs;
            this.support = weight;
            this.evidence = quad.evidence();
            this.searchArea = Quads.area(quad);
        }

        void see(CardQuad seen, double weight, long nowMs, boolean local) {
            searchHits += local ? 0 : 1;
            searchArea = local ? searchArea : Quads.area(seen);
            double rate = runningRate(nowMs);
            presence += presenceRate(nowMs) * (1 - presence);
            lastSeen = Quads.alignedTo(seen, quad);
            quad = Quads.between(quad, lastSeen, smoothing(lastSeen, nowMs));
            hits++;
            misses = 0;
            lastSeenMs = nowMs;
            lastUpdateMs = nowMs;
            support += rate * (weight - support);
            evidence += rate * (seen.evidence() - evidence);
            if (Quads.distance(quad, anchor) > STEADY_TOLERANCE * Quads.longSide(quad)) {
                anchor = quad;
                steadySinceMs = nowMs;
            }
        }

        void miss(long nowMs) {
            misses++;
            support -= runningRate(nowMs) * support;
            presence -= presenceRate(nowMs) * presence;
            lastUpdateMs = nowMs;
        }

        boolean expired(long nowMs) {
            return misses > MIN_MISSES && nowMs - lastSeenMs > MAX_GAP_MS;
        }

        boolean confirmed(long nowMs) {
            return hits >= CONFIRM_FRAMES && searchHits >= CONFIRM_SEARCHES && nowMs - firstSeenMs >= CONFIRM_MS && evidence >= CardQuad.MIN_CARD_EVIDENCE;
        }

        double matchTolerance(long nowMs) {
            return Math.min(MAX_MATCH_TOLERANCE, MATCH_TOLERANCE + MATCH_GROWTH_PER_MS * (nowMs - lastSeenMs));
        }

        private double runningRate(long nowMs) {
            return 1 - Math.exp(-(nowMs - lastUpdateMs) / RUNNING_TAU_MS);
        }

        private double presenceRate(long nowMs) {
            return 1 - Math.exp(-(nowMs - lastUpdateMs) / PRESENCE_TAU_MS);
        }

        /** How far to follow an outline: slowly for what looks like noise, fully for real movement. */
        private double smoothing(CardQuad aligned, long nowMs) {
            double share = Quads.distance(aligned, quad) / Quads.longSide(quad);
            double slow = 1 - Math.exp(-(nowMs - lastUpdateMs) / SMOOTH_TAU_MS);
            double moved = Math.max(0, Math.min(1, (share - NOISE_SHARE) / (MOVED_SHARE - NOISE_SHARE)));
            return slow + (1 - slow) * moved;
        }
    }

    /** The outline reported as the card. It belongs to no track, so no one track can swing it. */
    private static final class Held {
        final int id;
        final long firstSeenMs;
        CardQuad quad;
        CardQuad anchor;
        CardQuad pendingMove;
        long steadySinceMs;
        long lastSeenMs;
        long otherOutlinesSinceMs = -1;
        int misses;
        int revision;
        double searchArea;
        final ArrayDeque<double[]> steadySamples = new ArrayDeque<>();

        Held(int id, long firstSeenMs, CardQuad quad, long steadySinceMs, long nowMs) {
            this.id = id;
            this.firstSeenMs = firstSeenMs;
            this.quad = quad;
            this.anchor = quad;
            this.steadySinceMs = steadySinceMs;
            this.lastSeenMs = nowMs;
            this.searchArea = Quads.area(quad);
        }

        /** Moves the outline without that counting as card movement, because the anchor comes along. */
        void reshape(CardQuad target, double share) {
            CardQuad next = Quads.between(quad, target, share);
            anchor = Quads.offset(anchor, quad, next);
            quad = next;
            searchArea = Quads.area(next);
            revision++;
        }

        void restartSteady(long nowMs) {
            anchor = quad;
            steadySinceMs = nowMs;
            steadySamples.clear();
        }

        /** Resets the clock only when most of a short window has been away from the anchor. */
        void updateSteady(long nowMs) {
            steadySamples.addLast(new double[] {nowMs, Quads.medianCornerDistance(quad, anchor) / Quads.longSide(quad)});
            while (steadySamples.size() > MIN_STEADY_SAMPLES && nowMs - steadySamples.peekFirst()[0] > STEADY_WINDOW_MS) {
                steadySamples.removeFirst();
            }
            double[] values = steadySamples.stream().mapToDouble(sample -> sample[1]).sorted().toArray();
            if (values[values.length / 2] > STEADY_TOLERANCE) {
                restartSteady(nowMs);
            }
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private Held held;
    private Held lastEnded;
    private Track rival;
    private long rivalSinceMs;
    private int nextId = 1;
    private int nextCardId = 1;

    /** Feeds one frame's candidates, best first. Returns the card, or null if none has persisted. */
    public TrackedCard update(List<CardQuad> candidates, long nowMs) {
        return update(candidates, nowMs, 0, 0);
    }

    /**
     * As {@link #update(List, long)}, with a pure shift from outside the image.
     *
     * @param shiftX how far, in image pixels, the scene moved in x since the previous frame
     * @param shiftY how far, in image pixels, the scene moved in y since the previous frame
     */
    public TrackedCard update(List<CardQuad> candidates, long nowMs, double shiftX, double shiftY) {
        return update(candidates, nowMs, shiftX == 0 && shiftY == 0 ? ImageMotion.NONE : ImageMotion.shift(shiftX, shiftY));
    }

    /**
     * As {@link #update(List, long)}, with a motion estimate from outside the image such as a
     * gyroscope. Unlike a shift it can move each corner differently, which is what roll does.
     *
     * @param motion how the scene moved since the previous frame
     */
    public TrackedCard update(List<CardQuad> candidates, long nowMs, ImageMotion motion) {
        return update(candidates, nowMs, motion, false);
    }

    /** The outlines worth refitting on the next frame: the held one, or the likeliest few. */
    /** Where the person is aiming, in frame pixels. With it, the card nearest this wins, rather than the best supported. */
    public void setAim(Corner aim) {
        this.aim = aim;
    }

    public List<CardQuad> seeds(long nowMs) {
        if (held != null) {
            return List.of(held.quad);
        }
        return tracks.stream().filter(t -> t.quad.refined() && t.evidence >= CardQuad.MIN_CARD_EVIDENCE && nowMs - t.lastSeenMs <= MAX_GAP_MS)
                .sorted(Comparator.comparingDouble((Track t) -> -t.support)).limit(MAX_SEEDS).map(t -> t.quad).collect(java.util.stream.Collectors.toList());
    }

    /** The refits that haven't drifted in size from what a search last found. */
    public List<CardQuad> undrifted(List<CardQuad> refits) {
        List<CardQuad> kept = new ArrayList<>();
        for (CardQuad refit : refits) {
            double reference = held != null ? held.searchArea : tracks.stream()
                    .min(Comparator.comparingDouble(t -> Quads.distance(refit, t.quad))).map(t -> t.searchArea).orElse(Quads.area(refit));
            double ratio = Quads.area(refit) / reference;
            if (ratio <= MAX_REFIT_GROWTH && ratio >= 1 / MAX_REFIT_GROWTH) {
                kept.add(refit);
            }
        }
        return kept;
    }

    public boolean holding() {
        return held != null;
    }

    /**
     * As {@link #update(List, long, ImageMotion)}.
     *
     * @param local true if the candidates are only refits, so a track that isn't among them wasn't looked for
     */
    public TrackedCard update(List<CardQuad> candidates, long nowMs, ImageMotion motion, boolean local) {
        if (motion != ImageMotion.NONE) {
            for (Track track : tracks) {
                track.quad = motion.moved(track.quad);
                track.anchor = motion.moved(track.anchor);
            }
            if (held != null) {
                held.quad = motion.moved(held.quad);
                held.anchor = motion.moved(held.anchor);
                held.pendingMove = held.pendingMove == null ? null : motion.moved(held.pendingMove);
            }
            if (lastEnded != null) {
                lastEnded.quad = motion.moved(lastEnded.quad);
            }
        }
        Set<Track> seen = new HashSet<>();
        for (int rank = 0; rank < candidates.size(); rank++) {
            CardQuad candidate = candidates.get(rank);
            if (!candidate.refined()) {
                continue; // Rough rectangles are only approximate, so they prove nothing.
            }
            // Rank alone would treat a clear winner and a close call alike.
            double weight = candidate.score() / (rank + 1);
            Track match = closestUnseen(candidate, seen, nowMs);
            if (match != null) {
                match.see(candidate, weight, nowMs, local);
            } else if (local) {
                continue;
            } else {
                match = new Track(nextId++, candidate, weight, nowMs);
                makeRoom();
                tracks.add(match);
            }
            seen.add(match);
        }
        for (Iterator<Track> it = tracks.iterator(); it.hasNext();) {
            Track track = it.next();
            if (!seen.contains(track) && !local) {
                track.miss(nowMs);
                if (track.expired(nowMs)) {
                    it.remove();
                }
            }
        }
        CardQuad seenQuad = held == null ? null : followHeld(candidates, nowMs, local);
        chooseHeld(nowMs);
        if (held == null) {
            return null;
        }
        if (seenQuad == null && held.lastSeenMs == nowMs) {
            seenQuad = held.quad; // Just started, on an outline seen this frame.
        }
        return new TrackedCard(held.id, held.quad, held.firstSeenMs, held.steadySinceMs, seenQuad != null, seenQuad, held.revision);
    }

    /**
     * Updates the held outline from this frame's candidates.
     *
     * @return this frame's measure of the card's place, or null if the frame didn't show it
     */
    private CardQuad followHeld(List<CardQuad> candidates, long nowMs, boolean local) {
        CardQuad match = closestSameCard(candidates);
        CardQuad seen = null;
        if (match == null) {
            held.misses++;
        } else {
            held.misses = 0;
            held.searchArea = local ? held.searchArea : Quads.area(match);
            long sinceLast = nowMs - held.lastSeenMs;
            held.lastSeenMs = nowMs;
            seen = applyMatch(match, sinceLast, nowMs);
        }
        reshapeToOuter(nowMs);
        held.updateSteady(nowMs);
        return seen;
    }

    private CardQuad applyMatch(CardQuad match, long sinceLastMs, long nowMs) {
        double side = Quads.longSide(held.quad);
        double gap = Quads.distance(match, held.quad) / side;
        double slow = 1 - Math.exp(-sinceLastMs / RESHAPE_TAU_MS);
        boolean rigid = sameSizeMoved(match, gap);
        double size = Math.sqrt(Quads.area(match) / Quads.area(held.quad));
        if (gap <= SAME_OUTLINE && Math.abs(size - 1) <= RIGID_SIZE) {
            double moved = rigid ? Math.max(0, Math.min(1, (gap - NOISE_SHARE) / (MOVED_SHARE - NOISE_SHARE))) : 0;
            held.quad = Quads.between(held.quad, match, slow + (1 - slow) * moved);
            held.pendingMove = null;
            held.otherOutlinesSinceMs = -1;
            return match;
        }
        double shift = Quads.centre(match).distanceTo(Quads.centre(held.quad)) / side;
        if (rigid || shift >= CENTRE_MOVED) {
            // A second frame in agreement tells a moving card from one bad fit. Outlines of one card share
            // a centre, so even a different outline shows the card moving.
            boolean goesOn = held.pendingMove != null
                    && Quads.centre(match).distanceTo(Quads.centre(held.pendingMove)) <= MOVE_CONSISTENT * side;
            held.pendingMove = match;
            if (goesOn) {
                Corner from = Quads.centre(held.quad);
                Corner to = Quads.centre(match);
                held.quad = rigid ? match : held.quad.shifted(to.x() - from.x(), to.y() - from.y());
                held.pendingMove = null;
                held.otherOutlinesSinceMs = -1;
                return rigid ? match : held.quad;
            }
            return held.quad;
        }
        held.pendingMove = null;
        if (held.otherOutlinesSinceMs < 0) {
            held.otherOutlinesSinceMs = nowMs;
        } else if (nowMs - held.otherOutlinesSinceMs >= HELD_OUTLINE_GONE_MS) {
            held.quad = match;
            held.otherOutlinesSinceMs = -1;
            held.restartSteady(nowMs);
            return match;
        }
        return held.quad;
    }

    private boolean sameSizeMoved(CardQuad match, double gap) {
        double size = Math.sqrt(Quads.area(match) / Quads.area(held.quad));
        double shift = Quads.centre(match).distanceTo(Quads.centre(held.quad)) / Quads.longSide(held.quad);
        return Math.abs(size - 1) <= RIGID_SIZE && shift >= RIGID_CENTRE * gap;
    }

    /** The refined candidate closest to the held outline among those that are the same card, lined up with it. */
    private CardQuad closestSameCard(List<CardQuad> candidates) {
        CardQuad best = null;
        double bestDistance = Double.MAX_VALUE;
        for (CardQuad candidate : candidates) {
            if (candidate.refined() && sameCard(candidate, held.quad)) {
                double distance = Quads.distance(candidate, held.quad);
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best == null ? null : Quads.alignedTo(best, held.quad);
    }

    private static boolean sameCard(CardQuad a, CardQuad b) {
        double side = Quads.longSide(b);
        return Quads.centre(a).distanceTo(Quads.centre(b)) <= SAME_CARD_CENTRE * side
                && Quads.distance(a, b) <= SAME_CARD_CORNER * side;
    }

    /** Sleeve, card edge and frame merge into the outermost that keeps turning up, not into whichever won this frame. */
    private void reshapeToOuter(long nowMs) {
        Track outer = outermost(held.quad, held.quad.evidence(), nowMs);
        if (outer != null) {
            double share = 1 - Math.exp(-(nowMs - held.lastSeenMs + 100) / RESHAPE_TAU_MS);
            held.reshape(Quads.alignedTo(outer.quad, held.quad), share);
            held.otherOutlinesSinceMs = -1;
        }
    }

    /** The largest persistent same-card outline clearly bigger than the given one, or null. */
    private Track outermost(CardQuad quad, double evidence, long nowMs) {
        Track best = null;
        double bestArea = Quads.area(quad) * OUTER_MIN_GROWTH;
        for (Track track : tracks) {
            double area = Quads.area(track.quad);
            if (area > bestArea && track.confirmed(nowMs) && nowMs - track.steadySinceMs >= OUTER_MIN_AGE_MS
                    && track.presence >= OUTER_MIN_PRESENCE && track.evidence >= evidence - OUTER_EVIDENCE_MARGIN
                    && Quads.distance(track.quad, quad) <= OUTER_MAX_CORNER * Quads.longSide(quad)
                    && Quads.enclosesWithRoom(track.quad, quad, OUTER_ROOM)) {
                best = track;
                bestArea = area;
            }
        }
        return best;
    }

    private Track closestUnseen(CardQuad candidate, Set<Track> seen, long nowMs) {
        Track best = null;
        double bestDistance = Double.MAX_VALUE;
        double longSide = Quads.longSide(candidate);
        for (Track track : tracks) {
            double distance = Quads.distance(candidate, track.quad);
            if (!seen.contains(track) && distance < track.matchTolerance(nowMs) * longSide && distance < bestDistance) {
                best = track;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void makeRoom() {
        if (tracks.size() >= MAX_TRACKS) {
            Track weakest = Collections.min(tracks, Comparator.comparingDouble(t -> t.support));
            tracks.remove(weakest);
        }
    }

    /** Ends the held card when it has gone or lost out, and starts one if there's none. */
    private void chooseHeld(long nowMs) {
        if (held != null && held.misses > MIN_MISSES && nowMs - held.lastSeenMs > MAX_GAP_MS) {
            endHeld();
        }
        if (held != null) {
            Track other = bestOtherCard(nowMs);
            if (other == null || !outRatesHeld(other, nowMs)) {
                rival = null;
            } else {
                if (rival != other) {
                    rival = other;
                    rivalSinceMs = nowMs;
                }
                if (heldRating(nowMs) == 0 || nowMs - rivalSinceMs >= RIVAL_MS) {
                    endHeld();
                }
            }
        }
        if (held == null) {
            Track best = bestConfirmed(nowMs);
            if (best != null) {
                startHeld(best, nowMs);
            }
        }
    }

    private void endHeld() {
        lastEnded = held;
        held = null;
        rival = null;
    }

    private void startHeld(Track track, long nowMs) {
        CardQuad quad = track.quad;
        Track outer = outermost(quad, track.evidence, nowMs);
        quad = outer == null ? quad : outer.quad;
        Held earlier = lastEnded != null && nowMs - lastEnded.lastSeenMs <= RESUME_MS && sameCard(quad, lastEnded.quad) ? lastEnded : null;
        held = new Held(earlier != null ? earlier.id : nextCardId++, earlier != null ? earlier.firstSeenMs : track.firstSeenMs, quad, track.steadySinceMs, nowMs);
        lastEnded = null;
    }

    private Track bestConfirmed(long nowMs) {
        Track best = null;
        double bestRating = 0;
        for (Track track : tracks) {
            double rating = rating(track, nowMs);
            if (track.confirmed(nowMs) && rating > 0 && (best == null || betterThan(track, rating, best, bestRating))) {
                best = track;
                bestRating = rating;
            }
        }
        return best;
    }

    /** The outermost of a card's outlines shares its centre, so nearest still finds that card. */
    private boolean betterThan(Track track, double rating, Track best, double bestRating) {
        if (aim == null) {
            return rating > bestRating;
        }
        // Near isn't enough: a faint outline by the middle mustn't beat a real card just beside it.
        if (rating < MIN_SHARE_OF_BEST * bestRating) {
            return false;
        }
        return bestRating < MIN_SHARE_OF_BEST * rating || aimDistance(track.quad) < aimDistance(best.quad);
    }

    private double aimDistance(CardQuad quad) {
        return Quads.centre(quad).distanceTo(aim);
    }

    private Track bestOtherCard(long nowMs) {
        Track best = null;
        double bestRating = 0;
        for (Track track : tracks) {
            double rating = rating(track, nowMs);
            if (track.confirmed(nowMs) && !sameCard(track.quad, held.quad) && rating > 0 && (best == null || betterThan(track, rating, best, bestRating))) {
                best = track;
                bestRating = rating;
            }
        }
        return best;
    }

    private boolean outRatesHeld(Track other, long nowMs) {
        if (aim != null) {
            double heldRating = heldRating(nowMs);
            return heldRating == 0 || rating(other, nowMs) >= MIN_SHARE_OF_BEST * heldRating
                    && aimDistance(other.quad) < aimDistance(held.quad) - NEARER_SHARE * Quads.longSide(held.quad);
        }
        return rating(other, nowMs) > SWITCH_MARGIN * heldRating(nowMs);
    }

    /** The best running support among the outlines that are the held card. */
    private double heldRating(long nowMs) {
        double best = 0;
        for (Track track : tracks) {
            if (sameCard(track.quad, held.quad)) {
                best = Math.max(best, rating(track, nowMs));
            }
        }
        return best;
    }

    /** Running support, or 0 for a track that sits inside a persisting outline like an art box. */
    private double rating(Track track, long nowMs) {
        for (Track other : tracks) {
            if (other != track && other.confirmed(nowMs) && isArtBoxOf(track.quad, other.quad)) {
                return 0;
            }
        }
        return track.support;
    }

    private static boolean isArtBoxOf(CardQuad inner, CardQuad outer) {
        double share = Quads.area(inner) / Quads.area(outer);
        return share >= MIN_ART_SHARE && share <= MAX_ART_SHARE && Quads.encloses(outer, inner);
    }
}
