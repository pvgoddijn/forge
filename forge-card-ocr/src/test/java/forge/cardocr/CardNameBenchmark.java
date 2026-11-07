package forge.cardocr;

import static org.junit.jupiter.api.Assumptions.*;

import forge.CardStorageReader;
import forge.StaticData;
import forge.cardocr.finder.PhotoFiles;
import forge.cardocr.reader.ArgbImage;
import forge.gamemodes.companion.AutoAddPolicy;
import forge.gamemodes.companion.CardNameMatcher;
import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.CardPhoto;
import forge.gamemodes.companion.DeckListNames;
import forge.util.Lang;
import forge.util.Localizer;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Slice 8's accuracy and speed benchmark (AC-31, AC-32): reads labelled photos with the real pipeline and prints how
 * often the right card is suggested. Run by hand, never in CI: it skips itself unless the system property
 * {@code forge.companion.benchmarkPhotos} names one or more photo folders (separated by the path separator):
 * <pre>
 * mvn test -pl forge-card-ocr -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CardNameBenchmark \
 *     -Dforge.companion.benchmarkPhotos=/photos/set-a:/photos/set-b
 * </pre>
 * <b>Labels.</b> A folder with a {@code names.csv} or {@code labels.csv} lists its photos, one per row: the photo (a
 * file in the folder, or a subfolder holding one photo, as the camera PoC's capture runs do), its card's name, and
 * optionally its group. A header row names the columns ({@code file}, {@code photo} or {@code run}; {@code name} or
 * {@code card}; {@code group}); without one they're in that order. A name of {@code NO_CARD} or nothing marks a photo
 * with no card, reported on its own. A folder without a csv takes every photo in it and its subfolders, its card named
 * by the file name ("ashnods-altar.jpg", "fire-ice-2.jpg" for a second copy), its group by the subfolder.
 * <p>
 * <b>Decks.</b> A photo's group is its csv group or subfolder, else {@code default}. If {@code decks/<group>.txt}
 * exists in the photo folder (or in {@code forge.companion.benchmarkDecks}, if set), the photo is also ranked with that
 * decklist preferred, as the photo dialog does with the seat's deck loaded (slice 6), for the second figure.
 * <p>
 * Suggestions are what the photo dialog shows: for a confident read, {@link CardNameMatcher#mergeReading} of the
 * reader's names and the matches for the text read; otherwise the name search with the text read. Top 3 counts the
 * first 3 of them.
 * <p>
 * <b>Images.</b> A capture run's folder holds the raw camera frame ({@code photo.jpg}) and, if the camera PoC found the
 * card, the card cut out at the camera's outline and flattened ({@code flat.jpg}). By default the benchmark reads what
 * the app reads after an Assisted capture: the flattened card, read inside its outline; without one, the raw frame with
 * the run's final tracked outline, which the reader flattens the same way; without that, the whole raw frame.
 * {@code -Dforge.companion.benchmarkImage=outline} always reads the raw frame with the run's final tracked outline, the
 * app's own input; {@code photo} reads the raw frames with no outline, for reference. Other
 * photos are read from the file with no outline, as the desktop dev build reads a file.
 */
class CardNameBenchmark {

    static final String PHOTOS = "forge.companion.benchmarkPhotos";
    static final String DECKS = "forge.companion.benchmarkDecks";
    /**
     * Which image of a capture run to read: {@code flat} (the default) reads the flattened card as the app does after an
     * Assisted capture, {@code photo} the raw camera frame with no outline, for reference.
     */
    static final String IMAGE = "forge.companion.benchmarkImage";
    /** A capture run's flattened card, cut out at the camera's outline. */
    private static final String FLAT = "flat.jpg";
    private static final String RAW = "photo.jpg";
    private static final String NO_CARD = "NO_CARD";
    private static final String DEFAULT_GROUP = "default";
    private static final int TOP = 3;
    /** As the photo dialog lists. */
    private static final int MAX_MATCHES = 30;
    /** Forge's card data, from this module's folder. */
    private static final String RES = "../forge-gui/res/";

    /**
     * A labelled photo; {@code card} is null for a photo with no card.
     *
     * @param corners the card's outline in the file's pixels, as the camera hands it over, or null for none
     */
    record Photo(File file, String label, String card, String group, float[] corners) {
        Photo(File file, String label, String card, String group) {
            this(file, label, card, group, null);
        }

        /** What the reader was given: the flattened card, a full frame with the camera's outline, or a full frame alone. */
        String input() {
            if (file.getName().equals(FLAT)) {
                return "flat crop";
            }
            return corners == null ? "full frame, no outline" : "full frame with outline";
        }
    }

    /** One photo's outcome. */
    record Result(Photo photo, CardNameReader.Reading reading, long millis, List<String> plain, List<String> withDeck,
                  Optional<String> autoAdd) {
    }

    private static final PhotoDecoder DECODER = (jpeg, longSide) -> {
        PhotoFiles.Argb argb;
        try {
            argb = PhotoFiles.load(jpeg);
        } catch (RuntimeException e) {
            return null;
        }
        ArgbImage image = new ArgbImage(argb.pixels(), argb.width(), argb.height());
        double scale = 1;
        // halved like the dev build's decoder and Android's inSampleSize
        while (Math.max(image.width(), image.height()) / 2 >= longSide) {
            image = image.scaledTo(image.width() / 2, image.height() / 2);
            scale /= 2;
        }
        return new PhotoDecoder.Decoded(image, scale);
    };

    @Test
    void benchmark() {
        String folders = System.getProperty(PHOTOS, "");
        assumeFalse(folders.isBlank(), "set -D" + PHOTOS + "=<folder> to run the benchmark");
        loadCardDatabase();
        List<String> faces = CardNameMatcher.cardFaceNames();
        assumeFalse(faces.isEmpty(), "Forge's card database loaded no cards");
        System.out.printf("BENCH matching against %d card faces%n", faces.size());
        Map<String, String> bySlug = new HashMap<>();
        for (String face : faces) {
            bySlug.putIfAbsent(slug(face), face);
        }
        CardNameMatcher matcher = CardNameMatcher.fromStaticData();
        CardNameReader reader = new OcrCardNameReader(() -> faces, DECODER);

        List<List<Result>> sets = new ArrayList<>();
        List<String> setNames = new ArrayList<>();
        boolean warm = false;
        for (String path : folders.split(File.pathSeparator)) {
            File folder = new File(path.trim());
            List<Photo> photos = photos(folder, bySlug);
            if (photos.isEmpty()) {
                System.out.println("BENCH no photos in " + folder);
                continue;
            }
            if (!warm) {
                long start = System.nanoTime();
                reader.read(new CardPhoto(photos.get(0).file(), photos.get(0).corners(), false));
                System.out.printf("BENCH first read (models and name index load): %d ms%n", millisSince(start));
                warm = true;
            }
            Map<String, Set<String>> decks = decks(folder, photos);
            List<Result> results = new ArrayList<>();
            for (Photo photo : photos) {
                long start = System.nanoTime();
                CardNameReader.Reading reading = reader.read(new CardPhoto(photo.file(), photo.corners(), false));
                long millis = millisSince(start);
                Set<String> deck = decks.get(photo.group());
                results.add(new Result(photo, reading, millis, suggestions(reading, matcher, Set.of()),
                        deck == null ? null : suggestions(reading, matcher, deck),
                        AutoAddPolicy.cardToAutoAdd(reading, AutoAddPolicy.THRESHOLD)));
            }
            report(folder.getPath(), results, decks.keySet());
            sets.add(results);
            setNames.add(folder.getName());
        }
        if (sets.size() > 1) {
            List<Result> all = new ArrayList<>();
            sets.forEach(all::addAll);
            System.out.println();
            System.out.println("BENCH ===== all sets together: " + String.join(" + ", setNames) + " =====");
            summary(all);
        }
    }

    /** What the photo dialog lists for {@code reading}, as CardPicker does, with {@code deck} preferred. */
    static List<String> suggestions(CardNameReader.Reading reading, CardNameMatcher matcher, Set<String> deck) {
        List<CardNameMatcher.Suggestion> matches = matcher.match(reading.textRead(), MAX_MATCHES, deck);
        if (reading.confident() && !reading.cards().isEmpty()) {
            return CardNameMatcher.mergeReading(reading.cards(), matches, deck);
        }
        List<String> names = new ArrayList<>();
        matches.forEach(match -> names.add(match.card()));
        return names;
    }

    private static void report(String folder, List<Result> results, Set<String> deckGroups) {
        System.out.println();
        System.out.println("BENCH ===== " + folder + " =====");
        Map<String, Integer> inputs = new TreeMap<>();
        results.forEach(r -> inputs.merge(r.photo().input(), 1, Integer::sum));
        System.out.println("BENCH images read: " + inputs);
        if (!deckGroups.isEmpty()) {
            System.out.println("BENCH decks for groups: " + deckGroups);
        }
        for (Result r : results) {
            if (r.photo().card() == null) {
                System.out.printf("BENCH  no card  %-40s read '%s' -> %s%s%n", r.photo().label(), r.reading().textRead(),
                        top(r.plain()), r.autoAdd().map(card -> " AUTO-ADD " + card).orElse(""));
                continue;
            }
            int plain = rank(r.photo().card(), r.plain());
            int deck = r.withDeck() == null ? -1 : rank(r.photo().card(), r.withDeck());
            if (plain != 0 || r.withDeck() != null && deck != 0) {
                System.out.printf("BENCH  FAIL %-40s is %s; read '%s' (%s, %.2f/%s) -> %s%s%s%n", r.photo().label(),
                        r.photo().card(), r.reading().textRead(), r.reading().confident() ? "confident" : "unsure",
                        r.reading().bestScore(), lead(r.reading()), top(r.plain()),
                        r.withDeck() == null ? "" : "; with deck -> " + top(r.withDeck()),
                        r.autoAdd().map(card -> " AUTO-ADD " + card).orElse(""));
            }
        }
        summary(results);
    }

    private static void summary(List<Result> results) {
        List<Result> cards = new ArrayList<>();
        List<Result> decked = new ArrayList<>();
        int noCard = 0;
        int noCardConfident = 0;
        int noCardAuto = 0;
        for (Result r : results) {
            if (r.photo().card() == null) {
                noCard++;
                noCardConfident += r.reading().confident() ? 1 : 0;
                noCardAuto += r.autoAdd().isPresent() ? 1 : 0;
            } else {
                cards.add(r);
                if (r.withDeck() != null) {
                    decked.add(r);
                }
            }
        }
        int n = cards.size();
        System.out.printf("BENCH photos of cards: %d; without a card: %d (%d confident, %d auto-added)%n", n, noCard,
                noCardConfident, noCardAuto);
        if (n == 0) {
            return;
        }
        System.out.printf("BENCH reader's own list : top-1 %s, top-3 %s%n",
                share(count(cards, r -> rank(r.photo().card(), r.reading().cards()), 1), n),
                share(count(cards, r -> rank(r.photo().card(), r.reading().cards()), TOP), n));
        System.out.printf("BENCH no deck           : top-1 %s, top-3 %s%n",
                share(count(cards, r -> rank(r.photo().card(), r.plain()), 1), n),
                share(count(cards, r -> rank(r.photo().card(), r.plain()), TOP), n));
        if (!decked.isEmpty()) {
            System.out.printf("BENCH with deck         : top-1 %s, top-3 %s (of the %d photos with a deck)%n",
                    share(count(decked, r -> rank(r.photo().card(), r.withDeck()), 1), decked.size()),
                    share(count(decked, r -> rank(r.photo().card(), r.withDeck()), TOP), decked.size()),
                    decked.size());
        }

        List<Long> times = new ArrayList<>();
        results.forEach(r -> times.add(r.millis()));
        Collections.sort(times);
        System.out.printf("BENCH read time per photo, models loaded: median %d ms, p90 %d ms, max %d ms (n=%d)%n",
                percentile(times, 50), percentile(times, 90), times.get(times.size() - 1), times.size());

        // the reader's first card against the truth, by score, for confident reads: what auto mode decides on
        double[] edges = {0.10, 0.25, AutoAddPolicy.THRESHOLD, 0.75, 1.00, 1.50};
        int[] right = new int[edges.length + 1];
        int[] wrong = new int[edges.length + 1];
        int confident = 0;
        for (Result r : cards) {
            if (!r.reading().confident() || r.reading().cards().isEmpty()) {
                continue;
            }
            confident++;
            int bucket = 0;
            while (bucket < edges.length && r.reading().bestScore() >= edges[bucket]) {
                bucket++;
            }
            if (rank(r.photo().card(), r.reading().cards()) == 0) {
                right[bucket]++;
            } else {
                wrong[bucket]++;
            }
        }
        System.out.printf("BENCH confident reads: %d of %d; reader's first card by best score (right/wrong):%n",
                confident, n);
        for (int b = 0; b <= edges.length; b++) {
            String range = b == 0 ? String.format("< %.2f", edges[0]) : b == edges.length
                    ? String.format(">= %.2f", edges[b - 1]) : String.format("%.2f-%.2f", edges[b - 1], edges[b]);
            System.out.printf("BENCH   score %-10s right %3d  wrong %3d%n", range, right[b], wrong[b]);
        }
        int autoAdds = 0;
        int wrongAdds = 0;
        for (Result r : results) {
            if (r.autoAdd().isPresent()) {
                autoAdds++;
                if (r.photo().card() == null || !same(r.photo().card(), r.autoAdd().get())) {
                    wrongAdds++;
                    System.out.printf("BENCH   wrong auto-add: %s is %s, added %s (%.2f/%s)%n", r.photo().label(),
                            r.photo().card() == null ? NO_CARD : r.photo().card(), r.autoAdd().get(),
                            r.reading().bestScore(), lead(r.reading()));
                }
            }
        }
        System.out.printf("BENCH auto mode at threshold %.2f, lead %.2f: %d auto-adds of %d photos, %d wrong = %.1f wrong"
                        + " per 100 auto-adds%s%n", AutoAddPolicy.THRESHOLD, AutoAddPolicy.MIN_LEAD, autoAdds,
                results.size(), wrongAdds, autoAdds == 0 ? 0 : 100.0 * wrongAdds / autoAdds,
                wrongAdds == 0 && autoAdds > 0 ? String.format(" (95%% upper bound %.1f)", 300.0 / autoAdds) : "");
    }

    private interface Ranker {
        int rank(Result r);
    }

    private static int count(List<Result> results, Ranker ranker, int top) {
        int hits = 0;
        for (Result r : results) {
            int rank = ranker.rank(r);
            if (rank >= 0 && rank < top) {
                hits++;
            }
        }
        return hits;
    }

    /** Where {@code card} is in {@code names}, a face of a split card matching it too; -1 if missing. */
    static int rank(String card, List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            if (same(card, names.get(i))) {
                return i;
            }
        }
        return -1;
    }

    static boolean same(String card, String name) {
        String a = slug(card);
        String b = slug(name);
        if (a.equals(b)) {
            return true;
        }
        for (String[] pair : new String[][] {{card, name}, {name, card}}) {
            if (pair[0].contains("//")) {
                for (String face : pair[0].split("//")) {
                    if (slug(face).equals(slug(pair[1]))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String share(int hits, int n) {
        return String.format("%d/%d = %.1f %%", hits, n, 100.0 * hits / n);
    }

    private static String lead(CardNameReader.Reading reading) {
        return Double.isNaN(reading.secondScore()) ? "no 2nd"
                : String.format("lead %.2f", reading.bestScore() - reading.secondScore());
    }

    private static String top(List<String> names) {
        return names.subList(0, Math.min(TOP, names.size())).toString();
    }

    private static long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** A name or file name as lower-case words joined by dashes, apostrophes and accents dropped. */
    static String slug(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("['’]", "");
        return plain.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    // ---- photos and labels

    static List<Photo> photos(File folder, Map<String, String> bySlug) {
        File csv = new File(folder, "names.csv");
        if (!csv.isFile()) {
            csv = new File(folder, "labels.csv");
        }
        List<Photo> photos = new ArrayList<>();
        if (csv.isFile()) {
            fromCsv(folder, csv, photos);
        } else {
            fromFileNames(folder, null, bySlug, photos);
        }
        photos.sort(Comparator.comparing(photo -> photo.file().getPath()));
        return photos;
    }

    private static void fromCsv(File folder, File csv, List<Photo> photos) {
        List<String> lines;
        try {
            lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + csv, e);
        }
        int fileColumn = 0;
        int nameColumn = 1;
        int groupColumn = 2;
        int first = 0;
        if (!lines.isEmpty()) {
            List<String> header = csvFields(lines.get(0));
            List<String> lower = new ArrayList<>();
            header.forEach(h -> lower.add(h.trim().toLowerCase(Locale.ROOT)));
            int file = firstIndex(lower, "file", "photo", "run");
            int name = firstIndex(lower, "name", "card");
            if (file >= 0 && name >= 0) {
                fileColumn = file;
                nameColumn = name;
                groupColumn = lower.indexOf("group");
                first = 1;
            }
        }
        for (String line : lines.subList(first, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = csvFields(line);
            String entry = field(fields, fileColumn);
            String name = field(fields, nameColumn);
            String group = groupColumn < 0 ? "" : field(fields, groupColumn);
            File run = new File(folder, entry);
            File file = photoFile(run);
            if (file == null) {
                System.out.println("BENCH no photo for " + csv.getName() + " row: " + line);
                continue;
            }
            String card = name.isEmpty() || name.equalsIgnoreCase(NO_CARD) ? null : name;
            photos.add(new Photo(file, entry, card, group.isEmpty() ? DEFAULT_GROUP : group, corners(run, file)));
        }
    }

    /** {@code entry} itself, with an image extension added if missing, or the one photo in a folder. */
    private static File photoFile(File entry) {
        if (entry.isFile()) {
            return entry;
        }
        for (String extension : List.of(".jpg", ".jpeg", ".png")) {
            File withExtension = new File(entry.getPath() + extension);
            if (withExtension.isFile()) {
                return withExtension;
            }
        }
        if (entry.isDirectory()) {
            File flat = new File(entry, FLAT);
            File raw = new File(entry, RAW);
            if (raw.isFile()) {
                return readFlat() && flat.isFile() ? flat : raw;
            }
            File[] images = entry.listFiles(CardNameBenchmark::isImage);
            if (images != null && images.length == 1) {
                return images[0];
            }
            File photo = new File(entry, "photo.jpg");
            return photo.isFile() ? photo : null;
        }
        return null;
    }

    private static String mode() {
        return System.getProperty(IMAGE, "flat").toLowerCase(Locale.ROOT);
    }

    private static boolean readFlat() {
        return mode().equals("flat");
    }

    /**
     * The outline the camera would hand over with this image: all of a flattened card, or a capture run's final
     * tracked outline scaled to its photo when the run saved no flattened card. Null when reading raw frames.
     */
    static float[] corners(File run, File image) {
        if (mode().equals("photo")) {
            return null;
        }
        if (image.getName().equals(FLAT)) {
            PhotoFiles.Argb flat = PhotoFiles.load(image);
            return new float[] {0, 0, flat.width(), 0, flat.width(), flat.height(), 0, flat.height()};
        }
        File json = new File(run, "run.json");
        if (!json.isFile()) {
            return null;
        }
        String text;
        try {
            text = Files.readString(json.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + json, e);
        }
        Matcher outline = Pattern.compile("\"finalOutline\":\\s*\\[(.*?)\\]\\s*\\]").matcher(text);
        Matcher analysis = Pattern.compile("\"analysisWidth\":\\s*(\\d+)").matcher(text);
        Matcher photo = Pattern.compile("\"imageWidth\":\\s*(\\d+)").matcher(text);
        if (!outline.find() || !analysis.find() || !photo.find()) {
            return null;
        }
        String[] numbers = outline.group(1).replaceAll("[\\[\\]\\s]", "").split(",");
        if (numbers.length != 8) {
            return null;
        }
        double scale = Double.parseDouble(photo.group(1)) / Double.parseDouble(analysis.group(1));
        float[] corners = new float[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = (float) (Double.parseDouble(numbers[i]) * scale);
        }
        return corners;
    }

    private static void fromFileNames(File folder, String group, Map<String, String> bySlug, List<Photo> photos) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory() && group == null && !file.getName().equals("decks")) {
                fromFileNames(file, file.getName(), bySlug, photos);
            } else if (isImage(file)) {
                String label = (group == null ? "" : group + "/") + file.getName();
                String card = cardFromFileName(file.getName(), bySlug);
                if (card == null) {
                    System.out.println("BENCH skipped, no card is named like " + label);
                    continue;
                }
                photos.add(new Photo(file, label, card, group == null ? DEFAULT_GROUP : group));
            }
        }
    }

    /** The card a file is named after: "ashnods-altar.jpg", "fire-ice-2.jpg"; null if none is. */
    static String cardFromFileName(String fileName, Map<String, String> bySlug) {
        String base = slug(fileName.replaceFirst("\\.[A-Za-z]+$", ""));
        String card = bySlug.get(base);
        if (card == null) {
            card = bySlug.get(base.replaceFirst("-\\d+$", ""));
        }
        return card;
    }

    private static boolean isImage(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return file.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"));
    }

    private static int firstIndex(List<String> list, String... names) {
        for (String name : names) {
            int index = list.indexOf(name);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    private static String field(List<String> fields, int index) {
        return index < fields.size() ? fields.get(index).trim() : "";
    }

    /** One csv line's fields, with double quotes around a field holding commas, and "" for a quote. */
    static List<String> csvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /** Each group's decklist names, from {@code decks/<group>.txt}, for the groups the photos have. */
    private static Map<String, Set<String>> decks(File folder, List<Photo> photos) {
        String override = System.getProperty(DECKS, "");
        File decks = override.isBlank() ? new File(folder, "decks") : new File(override);
        Map<String, Set<String>> byGroup = new TreeMap<>();
        for (Photo photo : photos) {
            File list = new File(decks, photo.group() + ".txt");
            if (!byGroup.containsKey(photo.group()) && list.isFile()) {
                try {
                    byGroup.put(photo.group(), DeckListNames.cardNames(Files.readString(list.toPath())));
                } catch (IOException e) {
                    throw new UncheckedIOException("Can't read " + list, e);
                }
            }
        }
        return byGroup;
    }

    /** Forge's card database, as the app loads it, for the card faces to match and the decklists' names. */
    private static void loadCardDatabase() {
        if (StaticData.instance() != null) {
            return;
        }
        File res = new File(RES);
        assumeTrue(new File(res, "cardsfolder").isDirectory(), "no Forge card data at " + res.getAbsolutePath());
        File customEditions;
        try {
            customEditions = Files.createTempDirectory("benchmark-editions").toFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        customEditions.deleteOnExit();
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", RES + "languages/");
        new StaticData(new CardStorageReader(RES + "cardsfolder/", null, false),
                new CardStorageReader(RES + "tokenscripts/", null, false), null, null, RES + "editions/",
                customEditions.getPath(), RES + "blockdata/", "", "Latest Art All Editions", true, false, false,
                false);
    }
}
