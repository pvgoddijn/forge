package forge.cardocr;

import forge.cardocr.assist.Luma;
import forge.cardocr.finder.CardFinder;
import forge.cardocr.finder.CardQuad;
import forge.cardocr.finder.Corner;
import forge.cardocr.reader.ArgbImage;
import forge.cardocr.reader.CardNames;
import forge.cardocr.reader.CardReader;
import forge.cardocr.reader.CharClasses;
import forge.cardocr.reader.NameTrie;
import forge.cardocr.reader.OnnxModels;
import forge.cardocr.reader.ReadBudget;
import forge.cardocr.reader.ReadResult;
import forge.cardocr.reader.ReadVerdict;
import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.CardPhoto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Reads a card's name with PP-OCR models through ONNX Runtime, on the camera PoC's default budget. With the camera's
 * outline, only that card is read: the outline is cut out with a margin and flattened, its edges are fitted again in
 * the crop, and its title is read there, then all text on the crop if the title read weakly. Nothing outside the crop
 * is searched (owner's decision, 2026-11-07). Without an outline, the whole photo: the card finder's outlines, then all
 * text, each only if the last read weakly.
 * <p>
 * The models and the name index load on the first read, which takes a second or two, and stay loaded. Reads happen one
 * at a time.
 */
public final class OcrCardNameReader implements CardNameReader {
    /** Decoded at this long side or more: the camera PoC's replay evaluation chose it. */
    private static final int READ_LONG_SIDE = 2000;
    private static final String MODELS = "/forge/cardocr/ocr/";
    /** The flattened card's size, as the camera PoC saved it: 10 pixels per millimetre. */
    static final int FLAT_WIDTH = 630;
    static final int FLAT_HEIGHT = 880;
    /** Guess: room around the camera's outline for a card it cut short, as a share of the card's sides. */
    private static final double CROP_MARGIN = 0.06;

    private final Supplier<Collection<String>> names;
    private final PhotoDecoder decoder;
    private final CardFinder finder = new CardFinder();
    private CardReader reader;

    /**
     * @param names every card face name to match against, asked for once, on the first read
     * @param decoder this backend's JPEG decoder
     */
    public OcrCardNameReader(Supplier<Collection<String>> names, PhotoDecoder decoder) {
        this.names = names;
        this.decoder = decoder;
    }

    @Override
    public synchronized Reading read(CardPhoto photo) {
        try {
            PhotoDecoder.Decoded decoded = decoder.decode(photo.jpeg(), READ_LONG_SIDE);
            if (decoded == null) {
                return Reading.NOTHING;
            }
            ReadBudget budget = ReadBudget.ON_PHONE.get(0);
            CardQuad tracked = quad(photo.cardCorners(), decoded.scale());
            ReadResult result;
            if (tracked != null) {
                // With an outline from the camera, read only the card, cropped and flattened: neighbouring cards are
                // normal at a table, and the person chose this card
                result = readCrop(decoded.image(), tracked, budget);
            } else {
                // no card outline was found, so the whole photo is all there is
                result = reader().read(decoded.image(), budget, null);
            }
            return reading(result);
        } finally {
            if (photo.temporary() && !photo.jpeg().delete()) {
                // Android never exits normally, so the capture screen also clears old photos when it opens
                photo.jpeg().deleteOnExit();
            }
        }
    }

    static Reading reading(ReadResult result) {
        List<String> cards = new ArrayList<>();
        for (ReadResult.Match match : result.matches()) {
            if (cards.size() < MAX_SUGGESTIONS) {
                cards.add(match.name());
            }
        }
        String text = result.textRead() == null ? "" : result.textRead().trim();
        List<ReadResult.Match> matches = result.matches();
        double best = matches.isEmpty() ? 0 : matches.get(0).score();
        double second = matches.size() < 2 ? Double.NaN : matches.get(1).score();
        return new Reading(text, cards, !ReadVerdict.unreadable(matches), best, second);
    }

    private static CardQuad quad(float[] corners, double scale) {
        if (corners == null) {
            return null;
        }
        return new CardQuad(new Corner(corners[0], corners[1]), new Corner(corners[2], corners[3]),
                new Corner(corners[4], corners[5]), new Corner(corners[6], corners[7]), 1, true).scaled(scale);
    }

    /**
     * Reads the card cut out at the camera's outline and flattened, with a margin: the outline comes from a small
     * preview frame, so it is often a little off, and the card's edges are fitted again in the crop. The title is read
     * both ways up; if that's weak, the outline is cut out again turned a quarter, both ways up, because a squarish
     * outline can leave the card sideways. The stronger of the two reads is kept whole, so its scores stay one read's.
     */
    private ReadResult readCrop(ArgbImage photo, CardQuad tracked, ReadBudget budget) {
        CardQuad card = shortSideFirst(tracked);
        ReadResult upright = readCropAs(photo, card, budget);
        if (!upright.strength().weak()) {
            return upright;
        }
        ReadResult sideways = readCropAs(photo, quarterTurned(card), budget);
        return bestScore(sideways) > bestScore(upright) ? sideways : upright;
    }

    /** Flattens the outline with c0-c1 as the crop's top edge, fits its edges in the crop, and reads only the crop. */
    private ReadResult readCropAs(ArgbImage photo, CardQuad card, ReadBudget budget) {
        int marginX = (int) Math.round(FLAT_WIDTH * CROP_MARGIN);
        int marginY = (int) Math.round(FLAT_HEIGHT * CROP_MARGIN);
        int width = FLAT_WIDTH + 2 * marginX;
        int height = FLAT_HEIGHT + 2 * marginY;
        ArgbImage crop = new ArgbImage(CardFinder.warp(photo.pixels(), photo.width(), photo.height(), grown(card, CROP_MARGIN),
                width, height), width, height);
        CardQuad inCrop = whole(FLAT_WIDTH, FLAT_HEIGHT).shifted(marginX, marginY);
        List<CardQuad> fitted = finder.refit(Luma.fromArgb(crop.pixels()), width, height, List.of(inCrop)).candidates();
        return reader().readCardOnly(crop, budget, fitted.isEmpty() ? inCrop : fitted.get(0));
    }

    private static double bestScore(ReadResult result) {
        return result.matches().isEmpty() ? Double.NEGATIVE_INFINITY : result.matches().get(0).score();
    }

    /** The same outline starting one corner on, so the card is read turned a quarter. */
    static CardQuad quarterTurned(CardQuad q) {
        return new CardQuad(q.c1(), q.c2(), q.c3(), q.c0(), q.score(), q.refined(), q.evidence());
    }

    /** The same outline with c0-c1 a short side, as the reader expects; a camera's outline may start on a long one. */
    static CardQuad shortSideFirst(CardQuad q) {
        if (q.c0().distanceTo(q.c1()) <= q.c0().distanceTo(q.c3())) {
            return q;
        }
        return quarterTurned(q);
    }

    /** The outline grown by this share of its sides on every side. */
    static CardQuad grown(CardQuad q, double share) {
        Corner u = new Corner(q.c1().x() - q.c0().x(), q.c1().y() - q.c0().y());
        Corner v = new Corner(q.c3().x() - q.c0().x(), q.c3().y() - q.c0().y());
        Corner u2 = new Corner(q.c2().x() - q.c3().x(), q.c2().y() - q.c3().y());
        Corner v2 = new Corner(q.c2().x() - q.c1().x(), q.c2().y() - q.c1().y());
        return new CardQuad(
                new Corner(q.c0().x() - share * (u.x() + v.x()), q.c0().y() - share * (u.y() + v.y())),
                new Corner(q.c1().x() + share * (u.x() - v2.x()), q.c1().y() + share * (u.y() - v2.y())),
                new Corner(q.c2().x() + share * (u2.x() + v2.x()), q.c2().y() + share * (u2.y() + v2.y())),
                new Corner(q.c3().x() + share * (v.x() - u2.x()), q.c3().y() + share * (v.y() - u2.y())),
                q.score(), q.refined(), q.evidence());
    }

    /** An outline around a whole image, top edge first. */
    static CardQuad whole(int width, int height) {
        return new CardQuad(new Corner(0, 0), new Corner(width, 0), new Corner(width, height), new Corner(0, height), 1, true);
    }

    private CardReader reader() {
        if (reader == null) {
            CardNames cardNames = CardNames.of(names.get());
            CharClasses classes = new CharClasses(lines("languages_english_dict.txt"));
            OnnxModels models = new OnnxModels(bytes("languages_english_rec.onnx"), bytes("detection_v3_det.onnx"));
            ReadBudget budget = ReadBudget.ON_PHONE.get(0);
            models.prepare(budget.recogniser(), budget.detector());
            reader = new CardReader(models, cardNames, new NameTrie(cardNames), classes);
        }
        return reader;
    }

    private static List<String> lines(String model) {
        return List.of(new String(bytes(model), StandardCharsets.UTF_8).split("\\R"));
    }

    private static byte[] bytes(String model) {
        try (InputStream in = OcrCardNameReader.class.getResourceAsStream(MODELS + model)) {
            if (in == null) {
                throw new IllegalStateException("The OCR model " + model + " isn't in the build");
            }
            // a loop, not InputStream.transferTo: that needs Android API 33, and Forge runs from API 26
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[1 << 16];
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the OCR model " + model, e);
        }
    }
}
