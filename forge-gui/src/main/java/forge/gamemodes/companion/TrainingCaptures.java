package forge.gamemodes.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Photos kept for the OCR benchmark (slice 8), only while the hidden "Keep photos for training" setting
 * ({@code CompanionPreferences.CPref.KEEP_PHOTOS}) is on: the one exception to AC-7, which otherwise deletes each photo once it's
 * read. Laid out as {@code CardNameBenchmark} reads a folder of capture runs:
 * <pre>
 * companion-captures/
 *   labels.csv                    run,card (one row per run, rewritten when a run is labelled again)
 *   20261107-153012-123/
 *     photo.jpg                   the photo as the camera stored it
 *     run.json                    what was known about it: size, EXIF orientation, the tracked outline, the read
 * </pre>
 * The outline in {@code run.json} is {@code finalOutline}, in the stored photo's pixels before any EXIF rotation, so
 * {@code analysisWidth} is the photo's own width and the benchmark's scale from the analysis frame is 1.
 * <p>
 * Thread-safe: a run is created on the capture's thread, read on a background thread and labelled on the UI thread.
 */
public final class TrainingCaptures {
    /** The folder under the app's files that holds the runs. */
    public static final String FOLDER = "companion-captures";
    public static final String LABELS = "labels.csv";
    public static final String PHOTO = "photo.jpg";
    public static final String RUN = "run.json";
    /** The card of a run whose photo was cancelled or replaced by another before a card was picked. */
    public static final String UNLABELLED = "UNLABELLED";
    private static final String HEADER = "run,card";
    private static final DateTimeFormatter RUN_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final File root;
    private final ZoneId zone;

    /** @param root0 the {@value #FOLDER} folder; made when the first run is kept */
    public TrainingCaptures(File root0) {
        this(root0, ZoneId.systemDefault());
    }

    TrainingCaptures(File root0, ZoneId zone0) {
        root = root0;
        zone = zone0;
    }

    public File root() {
        return root;
    }

    /**
     * What is known about a photo when it's taken.
     *
     * @param takenMs when, in epoch milliseconds; also names the run's folder
     * @param device the phone, such as "Google Pixel 7"
     * @param imageWidth the stored photo's width, before any EXIF rotation
     * @param imageHeight its height
     * @param exifOrientation its EXIF orientation tag, 0 if it has none
     * @param corners the tracked card's outline as {@link CardPhoto#cardCorners()} gives it, or null if none was tracked
     */
    public record Capture(long takenMs, String device, int imageWidth, int imageHeight, int exifOrientation,
                          float[] corners) {
        public Capture {
            if (corners != null && corners.length != 8) {
                throw new IllegalArgumentException("corners needs 4 corners, as 8 numbers");
            }
        }
    }

    /**
     * Moves {@code photo} into a new run's folder as {@value #PHOTO} and writes its {@value #RUN}. The photo isn't
     * labelled yet: {@link Run#label} does that once a card is picked for it.
     */
    public Run keep(File photo, Capture capture) {
        File dir = newRunFolder(capture.takenMs());
        File kept = new File(dir, PHOTO);
        try {
            // copied, not moved: on Android the cache and the external files are different file systems
            Files.copy(photo.toPath(), kept.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not keep " + photo + " in " + dir, e);
        }
        // one left behind is cleared with the capture screen's other old photos
        photo.delete();
        Run run = new Run(dir, capture);
        run.write();
        return run;
    }

    private synchronized File newRunFolder(long takenMs) {
        String name = RUN_NAME.format(Instant.ofEpochMilli(takenMs).atZone(zone));
        File dir = new File(root, name);
        for (int i = 2; dir.exists(); i++) {
            dir = new File(root, name + "-" + i);
        }
        if (!dir.mkdirs()) {
            throw new UncheckedIOException(new IOException("Could not create " + dir));
        }
        return dir;
    }

    /** Sets {@code run}'s card in {@value #LABELS}, replacing its row if it has one; the file and header made if new. */
    synchronized void label(String run, String card) {
        File csv = new File(root, LABELS);
        List<String> lines = new ArrayList<>();
        try {
            if (csv.isFile()) {
                lines.addAll(Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8));
            }
            if (lines.isEmpty()) {
                lines.add(HEADER);
            }
            String row = csvField(run) + "," + csvField(card);
            String prefix = csvField(run) + ",";
            boolean replaced = false;
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).startsWith(prefix)) {
                    lines.set(i, row);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(row);
            }
            File temp = new File(root, LABELS + ".tmp");
            Files.write(temp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(temp.toPath(), csv.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not label " + run + " in " + csv, e);
        }
    }

    /** A csv field, quoted when it holds a comma, a quote or a line break, as a card name can ("100,000"). */
    static String csvField(String text) {
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /** One kept photo: its folder, and its label once a card is picked. */
    public final class Run {
        private final File dir;
        private final Capture capture;
        private CardNameReader.Reading reading; // null until read
        private String card; // null until labelled

        private Run(File dir0, Capture capture0) {
            dir = dir0;
            capture = capture0;
        }

        /** The run's folder; its name is the run's name in {@value #LABELS}. */
        public File dir() {
            return dir;
        }

        public File photo() {
            return new File(dir, PHOTO);
        }

        /** Adds what the reader made of the photo to {@value #RUN}. */
        public void recordReading(CardNameReader.Reading reading0) {
            synchronized (TrainingCaptures.this) {
                reading = reading0;
                write();
            }
        }

        /**
         * The card picked for this photo. Picked again, as after an edit, its row is replaced. Labelling it
         * {@value #UNLABELLED} keeps a card already picked: that marks a photo left without one.
         */
        public void label(String card0) {
            synchronized (TrainingCaptures.this) {
                if (UNLABELLED.equals(card0) && card != null) {
                    return;
                }
                card = card0;
                TrainingCaptures.this.label(dir.getName(), card0);
            }
        }

        /** Whether a card, or {@value #UNLABELLED}, was set for this photo. */
        public boolean labelled() {
            synchronized (TrainingCaptures.this) {
                return card != null;
            }
        }

        private void write() {
            JsonObject json = new JsonObject();
            json.addProperty("timestamp", Instant.ofEpochMilli(capture.takenMs()).toString());
            json.addProperty("timestampMs", capture.takenMs());
            json.addProperty("device", capture.device());
            json.addProperty("imageWidth", capture.imageWidth());
            json.addProperty("imageHeight", capture.imageHeight());
            json.addProperty("exifOrientation", capture.exifOrientation());
            // the outline is already in the photo's pixels: the analysis frame is the photo itself
            json.addProperty("analysisWidth", capture.imageWidth());
            json.addProperty("analysisHeight", capture.imageHeight());
            json.addProperty("outlineSpace", "stored photo pixels, before EXIF rotation");
            float[] corners = capture.corners();
            json.addProperty("tracked", corners != null);
            if (corners != null) {
                JsonArray outline = new JsonArray();
                for (int i = 0; i < 4; i++) {
                    JsonArray corner = new JsonArray();
                    corner.add(corners[2 * i]);
                    corner.add(corners[2 * i + 1]);
                    outline.add(corner);
                }
                json.add("finalOutline", outline);
            }
            if (reading != null) {
                JsonObject read = new JsonObject();
                read.addProperty("textRead", reading.textRead());
                JsonArray cards = new JsonArray();
                reading.cards().forEach(cards::add);
                read.add("cards", cards);
                read.addProperty("confident", reading.confident());
                read.addProperty("bestScore", reading.bestScore());
                if (!Double.isNaN(reading.secondScore())) {
                    read.addProperty("secondScore", reading.secondScore());
                }
                json.add("reading", read);
            }
            // compact, on one line: the benchmark finds finalOutline with a single-line pattern
            File file = new File(dir, RUN);
            File temp = new File(dir, RUN + ".tmp");
            try {
                Files.write(temp.toPath(), (json + "\n").getBytes(StandardCharsets.UTF_8));
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not write " + file, e);
            }
        }
    }
}
