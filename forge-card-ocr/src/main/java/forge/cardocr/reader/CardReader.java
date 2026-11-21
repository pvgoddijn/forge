package forge.cardocr.reader;

import forge.cardocr.assist.*;
import forge.cardocr.finder.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Reads a card from a still: finds outlines, reads their title bands both ways up, and scores every
 * card name against those readings. Depending on the budget, it also reads all text in the image.
 * A card's result is its best score from any reading. Ported from the OCR spike's harness.
 *
 * <p>Not safe to share between threads, as the finder and scorer hold buffers.
 */
public final class CardReader {

    /** The length bonus in title scoring, and in whole-image scoring, which sees more fragments. */
    private static final float TITLE_BETA = 3f;
    private static final float WHOLE_BETA = 2f;
    /** Whole-image scores count for a little less than a title's. */
    private static final float WHOLE_PENALTY = 0.15f;
    /** Further from the image's middle counts for less, per image diagonal. */
    private static final double OFF_CENTRE_PENALTY = 1.2;
    /** Only the surest readings get scored against the names. */
    private static final int MAX_SCORED_READINGS = 12;
    private static final int KEEP = 3;
    /** Guess: a little more than the outline, because a fitted edge can land just inside the card. */
    private static final double CROP_GROWTH = 1.03;
    private static final int MAX_CROP_SIDE = 1200;
    /**
     * Guess: the text boxes nearest a flattened card's short edges, where the title is whichever way up
     * the card lies. Longest first kept the rules text and dropped the title. One foil card gave 40 boxes
     * in the full frame, which took 5 s to read on the phone without changing the answer.
     */
    private static final int MAX_CROP_BOXES = 8;
    /**
     * Read when the budget's windows gave a weak title: a shorter band makes the name taller in the recogniser's input,
     * which helps a blurred title. On the phone captures it fixed 2 of 15 misses at a third of the cost of always
     * reading them.
     */
    private static final List<TitleBands.Window> RETRY_WINDOWS = List.of(TitleBands.WIDE_HIGH, TitleBands.WIDE_LOW);

    private final TextModels models;
    private final CardNames names;
    private final CharClasses classes;
    private final NameScorer scorer;
    private final CardFinder finder = new CardFinder();

    public CardReader(TextModels models, CardNames names, NameTrie trie, CharClasses classes) {
        this.models = models;
        this.names = names;
        this.classes = classes;
        this.scorer = new NameScorer(trie);
    }

    private record Reading(CardQuad quad, float[][] raw, double confidence, String text) {
    }

    /** Origins of scores: an index into the readings, or this for the whole-image pass. */
    private static final int FROM_WHOLE_BASE = 1_000_000;

    public ReadResult read(ArgbImage image, ReadBudget budget) {
        return read(image, budget, null);
    }

    /**
     * @param tracked the card's outline if the camera already followed it, in this image's pixels. A
     *     budget that trusts it skips the finder unless the title read from it comes out weak
     */
    public ReadResult read(ArgbImage image, ReadBudget budget, CardQuad tracked) {
        long visitedBefore = scorer.visitedNodes();
        Pass pass = new Pass(new BestMatches(names.size(), KEEP));
        CardQuad first = null;
        boolean usedTracked = tracked != null && budget.trustTracked();
        if (usedTracked) {
            first = tracked;
            readTitles(image, List.of(tracked), budget, pass);
        }
        boolean finderRan = false;
        if (!usedTracked || strength(pass).weak()) {
            finderRan = true;
            long start = System.nanoTime();
            FindResult found = finder.find(Luma.fromArgb(image.pixels()), image.width(), image.height());
            pass.finder = ms(System.nanoTime() - start);
            List<CardQuad> quads = found.candidates().subList(0, Math.min(budget.candidates(), found.candidates().size()));
            if (first == null && !quads.isEmpty()) {
                first = quads.get(0);
            }
            readTitles(image, quads, budget, pass);
            retryWindows(image, quads, budget, pass);
        }

        List<ReadResult.Match> titleOnly = toMatches(pass.best);
        TitleStrength strength = strength(pass);
        boolean runWhole = budget.whole() == ReadBudget.Whole.ALWAYS
                || budget.whole() == ReadBudget.Whole.IF_WEAK && strength.weak();
        WholeRun whole = WholeRun.NONE;
        if (runWhole) {
            // the text on the flattened card first: in a cluttered frame its title box is found where the whole frame's isn't
            WholeRun flat = first == null ? WholeRun.NONE : readFlatCard(image, first, budget, pass.best, FROM_WHOLE_BASE);
            whole = flat.and(readWholeImage(image, budget, pass.best, true, Integer.MAX_VALUE, FROM_WHOLE_BASE + flat.texts().size()));
        }

        List<ReadResult.Match> matches = toMatches(pass.best);
        String text = "";
        CardQuad card = pass.surest == null ? first : pass.surest.quad();
        List<BestMatches.Entry> entries = pass.best.top();
        if (!entries.isEmpty() && entries.get(0).origin() < FROM_WHOLE_BASE) {
            Reading source = pass.scored.get(entries.get(0).origin());
            text = source.text();
            card = source.quad();
        } else if (!entries.isEmpty()) {
            text = whole.texts().get(entries.get(0).origin() - FROM_WHOLE_BASE);
        } else if (pass.surest != null) {
            text = pass.surest.text();
        }
        ReadResult.Steps steps = new ReadResult.Steps(pass.finder, pass.rectify, pass.recognise, pass.match, whole.detect(),
                whole.rectify(), whole.recognise(), whole.match());
        return new ReadResult(matches, titleOnly, text, card, steps, pass.quads, pass.bands, whole.found(), whole.read(),
                runWhole, scorer.visitedNodes() - visitedBefore, usedTracked, finderRan, strength);
    }

    /**
     * Reads only inside one outline, so a neighbouring card or anything else in the frame can't
     * become the answer. With no tracked outline, the finder's first choice in this photo is used. A
     * tracked outline can be off by the time of the photo, so a weak title gets one more outline: what
     * the finder sees at the same place in this photo, never another card elsewhere in the frame. If the
     * outline is still wrong, the card isn't read at all, and the result says so by its low score.
     */
    public ReadResult readCardOnly(ArgbImage image, ReadBudget budget, CardQuad tracked) {
        long visitedBefore = scorer.visitedNodes();
        Pass pass = new Pass(new BestMatches(names.size(), KEEP));
        boolean usedTracked = tracked != null;
        boolean finderRan = false;
        CardQuad card = tracked;
        if (usedTracked) {
            readTitles(image, List.of(tracked), budget, pass);
        }
        if (!usedTracked || strength(pass).weak()) {
            finderRan = true;
            long start = System.nanoTime();
            List<CardQuad> found = CardShape.acceptedOnly(finder.find(Luma.fromArgb(image.pixels()), image.width(), image.height()).candidates());
            pass.finder = ms(System.nanoTime() - start);
            CardQuad fresh = usedTracked ? sameCard(found, tracked) : nearestMiddle(found, image);
            if (fresh != null) {
                card = fresh;
                readTitles(image, List.of(fresh), budget, pass);
                retryWindows(image, List.of(fresh), budget, pass);
            }
        }
        List<ReadResult.Match> titleOnly = toMatches(pass.best);
        TitleStrength strength = strength(pass);
        boolean runWhole = card != null && (budget.whole() == ReadBudget.Whole.ALWAYS
                || budget.whole() == ReadBudget.Whole.IF_WEAK && strength.weak());
        WholeRun whole = runWhole ? readFlatCard(image, card, budget, pass.best, FROM_WHOLE_BASE) : WholeRun.NONE;
        List<BestMatches.Entry> entries = pass.best.top();
        String text = "";
        if (!entries.isEmpty()) {
            int origin = entries.get(0).origin();
            text = origin < FROM_WHOLE_BASE ? pass.scored.get(origin).text() : whole.texts().get(origin - FROM_WHOLE_BASE);
        }
        ReadResult.Steps steps = new ReadResult.Steps(pass.finder, pass.rectify, pass.recognise, pass.match, whole.detect(),
                whole.rectify(), whole.recognise(), whole.match());
        return new ReadResult(toMatches(pass.best), titleOnly, text, card, steps, pass.quads, pass.bands, whole.found(), whole.read(),
                runWhole, scorer.visitedNodes() - visitedBefore, usedTracked, finderRan, strength);
    }

    /** The believable outline nearest the photo's middle, where the person aimed, or else the finder's first choice. */
    private static CardQuad nearestMiddle(List<CardQuad> found, ArgbImage image) {
        Corner middle = new Corner(image.width() / 2.0, image.height() / 2.0);
        CardQuad best = found.isEmpty() ? null : found.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (CardQuad quad : found) {
            double distance = centre(quad).distanceTo(middle);
            if (quad.evidence() >= CardQuad.MIN_CARD_EVIDENCE && distance < bestDistance) {
                best = quad;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The finder's best outline around the tracked card's centre, or null. A neighbour's centre lies outside it. */
    private static CardQuad sameCard(List<CardQuad> found, CardQuad tracked) {
        Corner centre = centre(tracked);
        double reach = 0.25 * Math.max(tracked.c0().distanceTo(tracked.c3()), tracked.c1().distanceTo(tracked.c2()));
        for (CardQuad quad : found) {
            if (centre(quad).distanceTo(centre) <= reach) {
                return quad;
            }
        }
        return null;
    }

    private static Corner centre(CardQuad q) {
        double x = 0;
        double y = 0;
        for (Corner corner : q.corners()) {
            x += corner.x() / 4;
            y += corner.y() / 4;
        }
        return new Corner(x, y);
    }

    private WholeRun readFlatCard(ArgbImage image, CardQuad card, ReadBudget budget, BestMatches best, int originBase) {
        long start = System.nanoTime();
        CardQuad grown = grown(card);
        double longSide = Math.max(grown.c0().distanceTo(grown.c3()), grown.c1().distanceTo(grown.c2()));
        double scale = Math.min(1, MAX_CROP_SIDE / longSide);
        int height = Math.max(1, (int) Math.round(longSide * scale));
        int width = Math.max(1, (int) Math.round(height * 63.0 / 88.0));
        ArgbImage flat = new ArgbImage(CardFinder.warp(image.pixels(), image.width(), image.height(), grown, width, height), width, height);
        double warped = ms(System.nanoTime() - start);
        WholeRun run = readWholeImage(flat, budget, best, false, MAX_CROP_BOXES, originBase);
        return new WholeRun(run.detect(), warped + run.rectify(), run.recognise(), run.match(), run.found(), run.read(), run.texts());
    }

    private static CardQuad grown(CardQuad q) {
        Corner middle = centre(q);
        Corner[] c = new Corner[4];
        for (int i = 0; i < 4; i++) {
            Corner corner = q.corners().get(i);
            c[i] = new Corner(middle.x() + (corner.x() - middle.x()) * CROP_GROWTH, middle.y() + (corner.y() - middle.y()) * CROP_GROWTH);
        }
        return new CardQuad(c[0], c[1], c[2], c[3], q.score(), q.refined(), q.evidence());
    }

    /** What the title reads so far add up to. */
    private static final class Pass {
        final BestMatches best;
        final List<Reading> scored = new ArrayList<>();
        Reading surest;
        double finder;
        double rectify;
        double recognise;
        double match;
        int quads;
        int bands;

        Pass(BestMatches best) {
            this.best = best;
        }
    }

    /** Reads the title again through the windows the budget hasn't read, if what it read is weak. */
    private void retryWindows(ArgbImage image, List<CardQuad> quads, ReadBudget budget, Pass pass) {
        List<TitleBands.Window> windows = new ArrayList<>(RETRY_WINDOWS);
        windows.removeAll(budget.windows());
        if (!windows.isEmpty() && !quads.isEmpty() && strength(pass).weak()) {
            readTitles(image, quads, windows, budget, pass);
        }
    }

    private void readTitles(ArgbImage image, List<CardQuad> quads, ReadBudget budget, Pass pass) {
        readTitles(image, quads, budget.windows(), budget, pass);
        pass.quads += quads.size();
    }

    private void readTitles(ArgbImage image, List<CardQuad> quads, List<TitleBands.Window> windows, ReadBudget budget, Pass pass) {
        long start = System.nanoTime();
        List<CardQuad> orientedQuads = new ArrayList<>();
        List<TitleBands.Band> bands = new ArrayList<>();
        for (CardQuad quad : quads) {
            for (CardQuad oriented : List.of(quad, quad.flipped())) {
                for (TitleBands.Window window : windows) {
                    orientedQuads.add(oriented);
                    bands.add(TitleBands.title(image, oriented, window));
                }
            }
        }
        long afterRectify = System.nanoTime();

        List<float[][]> raws = recogniseAll(bands, budget);
        List<Reading> readings = new ArrayList<>();
        for (int i = 0; i < raws.size(); i++) {
            float[][] raw = raws.get(i);
            readings.add(new Reading(orientedQuads.get(i), raw, CharClasses.confidence(raw), classes.greedy(raw)));
        }
        readings.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        List<Reading> kept = readings.subList(0, Math.min(MAX_SCORED_READINGS, readings.size()));
        long afterRecognise = System.nanoTime();

        for (Reading reading : kept) {
            int origin = pass.scored.size();
            pass.scored.add(reading);
            scorer.score(classes.reduce(reading.raw()), TITLE_BETA, 0, origin, pass.best);
            if (pass.surest == null || reading.confidence() > pass.surest.confidence()) {
                pass.surest = reading;
            }
        }
        long afterMatch = System.nanoTime();
        pass.rectify += ms(afterRectify - start);
        pass.recognise += ms(afterRecognise - afterRectify);
        pass.match += ms(afterMatch - afterRecognise);
        pass.bands += bands.size();
    }

    /** How far to believe the best title match: see {@link TitleStrength}. */
    private TitleStrength strength(Pass pass) {
        List<BestMatches.Entry> top = pass.best.top();
        if (top.isEmpty()) {
            return TitleStrength.NONE;
        }
        BestMatches.Entry first = top.get(0);
        Reading source = pass.scored.get(first.origin());
        String name = names.normalised(first.card());
        int frames = source.raw().length;
        double logPerChar = (first.score() * frames - TITLE_BETA * name.length()) / name.length();
        double margin = top.size() > 1 ? first.score() - top.get(1).score() : Double.POSITIVE_INFINITY;
        return new TitleStrength(first.score(), logPerChar, margin, CardNames.normalise(source.text()).equals(name));
    }

    private record WholeRun(double detect, double rectify, double recognise, double match, int found, int read,
            List<String> texts) {
        static final WholeRun NONE = new WholeRun(0, 0, 0, 0, 0, 0, List.of());

        /** Both runs, the other's texts after these. */
        WholeRun and(WholeRun other) {
            List<String> both = new ArrayList<>(texts);
            both.addAll(other.texts);
            return new WholeRun(detect + other.detect, rectify + other.rectify, recognise + other.recognise, match + other.match,
                    found + other.found, read + other.read, both);
        }
    }

    /**
     * @param penaliseOffCentre whether text far from the middle counts for less, which suits a whole
     *     frame. On a flattened card the title is at the edge
     * @param maxBoxes how many text boxes to read, nearest the top or bottom edge first
     * @param originBase the origin of the first reading's scores; the rest follow it
     */
    private WholeRun readWholeImage(ArgbImage image, ReadBudget budget, BestMatches best, boolean penaliseOffCentre, int maxBoxes,
            int originBase) {
        long start = System.nanoTime();
        List<TextBox> detected = detectBoxes(image, budget);
        List<TextBox> boxes = detected;
        if (detected.size() > maxBoxes) {
            boxes = new ArrayList<>(detected);
            boxes.sort(Comparator.comparingDouble(b -> Math.min(b.centreY(), image.height() - b.centreY())));
            boxes = boxes.subList(0, maxBoxes);
        }
        double diagonal = Math.hypot(image.width(), image.height());
        double midX = image.width() / 2.0;
        double midY = image.height() / 2.0;
        int found = detected.size();
        long afterDetect = System.nanoTime();

        List<TitleBands.Band> bands = new ArrayList<>();
        for (TextBox box : boxes) {
            bands.add(TitleBands.box(image, box, false));
            bands.add(TitleBands.box(image, box, true));
        }
        long afterRectify = System.nanoTime();
        List<float[][]> raws = recogniseAll(bands, budget);
        long afterRecognise = System.nanoTime();

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < raws.size(); i++) {
            TextBox box = boxes.get(i / 2);
            float offCentre = penaliseOffCentre ? (float) (OFF_CENTRE_PENALTY * Math.hypot(box.centreX() - midX, box.centreY() - midY) / diagonal) : 0;
            texts.add(classes.greedy(raws.get(i)));
            scorer.score(classes.reduce(raws.get(i)), WHOLE_BETA, offCentre + WHOLE_PENALTY, originBase + i, best);
        }
        long end = System.nanoTime();
        return new WholeRun(ms(afterDetect - start), ms(afterRectify - afterDetect), ms(afterRecognise - afterRectify),
                ms(end - afterRecognise), found, boxes.size(), texts);
    }

    private List<TextBox> detectBoxes(ArgbImage image, ReadBudget budget) {
        TextDetection.InputSize size = TextDetection.inputSize(image.width(), image.height(), budget.detectorLongSide());
        ArgbImage scaled = image.scaledTo(size.width(), size.height());
        float[] map = models.detect(ModelInput.forDetector(scaled.pixels()), size.height(), size.width(), budget.detector());
        return TextDetection.boxes(map, size.width(), size.height(), (double) image.width() / size.width(),
                (double) image.height() / size.height());
    }

    private List<float[][]> recogniseAll(List<TitleBands.Band> bands, ReadBudget budget) {
        if (budget.workers() <= 1 || bands.size() < 2) {
            List<float[][]> out = new ArrayList<>();
            for (TitleBands.Band band : bands) {
                out.add(recognise(band, budget));
            }
            return out;
        }
        ExecutorService pool = Executors.newFixedThreadPool(budget.workers());
        try {
            List<Future<float[][]>> running = new ArrayList<>();
            for (TitleBands.Band band : bands) {
                running.add(pool.submit(() -> recognise(band, budget)));
            }
            List<float[][]> out = new ArrayList<>();
            for (Future<float[][]> future : running) {
                out.add(future.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("A recognition failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private float[][] recognise(TitleBands.Band band, ReadBudget budget) {
        float[] input = ModelInput.forRecogniser(band.rgb());
        return models.recognise(input, band.height(), band.width(), budget.recogniser());
    }

    private List<ReadResult.Match> toMatches(BestMatches best) {
        List<ReadResult.Match> out = new ArrayList<>();
        for (BestMatches.Entry entry : best.top()) {
            out.add(new ReadResult.Match(names.name(entry.card()), entry.score(), entry.origin() < FROM_WHOLE_BASE));
        }
        return out;
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }
}
