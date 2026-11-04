package forge.cardocr;

import forge.cardocr.finder.CardQuad;
import forge.cardocr.finder.Corner;
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
 * Reads a card's name with PP-OCR models through ONNX Runtime, on the camera PoC's default budget: the title band at
 * the tracked outline first, then the card finder, then all text in the photo, each only if the last read weakly.
 * <p>
 * The models and the name index load on the first read, which takes a second or two, and stay loaded. Reads happen one
 * at a time.
 */
public final class OcrCardNameReader implements CardNameReader {
    /** Decoded at this long side or more: the camera PoC's replay evaluation chose it. */
    private static final int READ_LONG_SIDE = 2000;
    private static final String MODELS = "/forge/cardocr/ocr/";

    private final Supplier<Collection<String>> names;
    private final PhotoDecoder decoder;
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
            // With an outline from the camera, read only inside the card: neighbouring cards are normal at a table.
            ReadResult result = tracked != null
                    ? reader().readCardOnly(decoded.image(), budget, tracked)
                    : reader().read(decoded.image(), budget, null);
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
        return new Reading(text, cards, !ReadVerdict.unreadable(result.matches()));
    }

    private static CardQuad quad(float[] corners, double scale) {
        if (corners == null) {
            return null;
        }
        return new CardQuad(new Corner(corners[0], corners[1]), new Corner(corners[2], corners[3]),
                new Corner(corners[4], corners[5]), new Corner(corners[6], corners[7]), 1, true).scaled(scale);
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
