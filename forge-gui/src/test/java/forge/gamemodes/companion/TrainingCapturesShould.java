package forge.gamemodes.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Photos kept for training, on real files in a temporary folder: the run folders, run.json and labels.csv. */
public class TrainingCapturesShould {
    // 2026-11-07 15:30:12.123 UTC
    private static final long TAKEN = 1794065412123L;
    private static final float[] CORNERS = {10, 20, 410, 22, 405, 590, 8, 585};

    private File root;
    private TrainingCaptures captures;

    @BeforeMethod
    public void createRoot() throws IOException {
        File parent = Files.createTempDirectory("training-captures").toFile();
        parent.deleteOnExit();
        root = new File(parent, TrainingCaptures.FOLDER);
        captures = new TrainingCaptures(root, ZoneOffset.UTC);
    }

    private File photo(String content) {
        try {
            File photo = Files.createTempFile("card-", ".jpg").toFile();
            Files.write(photo.toPath(), content.getBytes(StandardCharsets.UTF_8));
            return photo;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TrainingCaptures.Capture capture(float[] corners) {
        return new TrainingCaptures.Capture(TAKEN, "Google Pixel 7", 3000, 4000, 6, corners);
    }

    private static JsonObject runJson(TrainingCaptures.Run run) throws IOException {
        return JsonParser.parseString(Files.readString(new File(run.dir(), TrainingCaptures.RUN).toPath()))
                .getAsJsonObject();
    }

    private List<String> labels() throws IOException {
        return Files.readAllLines(new File(root, TrainingCaptures.LABELS).toPath(), StandardCharsets.UTF_8);
    }

    @Test
    public void moveThePhotoIntoAFolderNamedByTheTimeItWasTaken() throws IOException {
        File photo = photo("jpeg bytes");

        TrainingCaptures.Run run = captures.keep(photo, capture(CORNERS));

        assertThat(run.dir()).isEqualTo(new File(root, "20261107-153012-123"));
        assertThat(run.photo()).isEqualTo(new File(run.dir(), "photo.jpg"));
        assertThat(Files.readString(run.photo().toPath())).isEqualTo("jpeg bytes");
        assertThat(photo).doesNotExist();
        assertThat(run.labelled()).isFalse();
        assertThat(new File(root, TrainingCaptures.LABELS)).doesNotExist();
    }

    @Test
    public void giveTwoPhotosTakenInTheSameMillisecondTheirOwnFolders() {
        TrainingCaptures.Run first = captures.keep(photo("a"), capture(null));
        TrainingCaptures.Run second = captures.keep(photo("b"), capture(null));

        assertThat(first.dir().getName()).isEqualTo("20261107-153012-123");
        assertThat(second.dir().getName()).isEqualTo("20261107-153012-123-2");
    }

    @Test
    public void writeWhatIsKnownAboutThePhotoWithTheOutlineInItsOwnPixels() throws IOException {
        TrainingCaptures.Run run = captures.keep(photo("x"), capture(CORNERS));

        JsonObject json = runJson(run);
        assertThat(json.get("timestamp").getAsString()).isEqualTo("2026-11-07T15:30:12.123Z");
        assertThat(json.get("timestampMs").getAsLong()).isEqualTo(TAKEN);
        assertThat(json.get("device").getAsString()).isEqualTo("Google Pixel 7");
        assertThat(json.get("imageWidth").getAsInt()).isEqualTo(3000);
        assertThat(json.get("imageHeight").getAsInt()).isEqualTo(4000);
        assertThat(json.get("exifOrientation").getAsInt()).isEqualTo(6);
        assertThat(json.get("analysisWidth").getAsInt()).isEqualTo(3000);
        assertThat(json.get("analysisHeight").getAsInt()).isEqualTo(4000);
        assertThat(json.get("tracked").getAsBoolean()).isTrue();
        JsonArray outline = json.getAsJsonArray("finalOutline");
        assertThat(outline).hasSize(4);
        assertThat(outline.get(0).getAsJsonArray().get(0).getAsFloat()).isEqualTo(10f);
        assertThat(outline.get(2).getAsJsonArray().get(1).getAsFloat()).isEqualTo(590f);
        assertThat(json.has("reading")).isFalse();
    }

    @Test
    public void writeTheOutlineOnOneLineAsTheBenchmarkFindsIt() throws IOException {
        TrainingCaptures.Run run = captures.keep(photo("x"), capture(CORNERS));

        String text = Files.readString(new File(run.dir(), TrainingCaptures.RUN).toPath());
        assertThat(text).containsPattern("\"finalOutline\":\\s*\\[(.*?)\\]\\s*\\]");
        assertThat(text.trim()).doesNotContain("\n");
    }

    @Test
    public void leaveTheOutlineOutWhenNoCardWasTracked() throws IOException {
        TrainingCaptures.Run run = captures.keep(photo("x"), capture(null));

        JsonObject json = runJson(run);
        assertThat(json.get("tracked").getAsBoolean()).isFalse();
        assertThat(json.has("finalOutline")).isFalse();
    }

    @Test
    public void addTheReadersResult() throws IOException {
        TrainingCaptures.Run run = captures.keep(photo("x"), capture(CORNERS));

        run.recordReading(new CardNameReader.Reading("Lightning Bol", List.of("Lightning Bolt", "Lightning Blast"),
                true, 1.25, 0.4));

        JsonObject reading = runJson(run).getAsJsonObject("reading");
        assertThat(reading.get("textRead").getAsString()).isEqualTo("Lightning Bol");
        assertThat(reading.getAsJsonArray("cards")).hasSize(2);
        assertThat(reading.getAsJsonArray("cards").get(0).getAsString()).isEqualTo("Lightning Bolt");
        assertThat(reading.get("confident").getAsBoolean()).isTrue();
        assertThat(reading.get("bestScore").getAsDouble()).isEqualTo(1.25);
        assertThat(reading.get("secondScore").getAsDouble()).isEqualTo(0.4);
        assertThat(runJson(run).getAsJsonArray("finalOutline")).hasSize(4);
    }

    @Test
    public void leaveOutASecondScoreThereIsNone() throws IOException {
        TrainingCaptures.Run run = captures.keep(photo("x"), capture(null));

        run.recordReading(CardNameReader.Reading.NOTHING);

        JsonObject reading = runJson(run).getAsJsonObject("reading");
        assertThat(reading.has("secondScore")).isFalse();
        assertThat(reading.getAsJsonArray("cards")).isEmpty();
    }

    @Test
    public void startTheLabelsWithAHeaderAndAddARowPerRun() throws IOException {
        TrainingCaptures.Run first = captures.keep(photo("a"), capture(null));
        TrainingCaptures.Run second = captures.keep(photo("b"), capture(null));

        first.label("Lightning Bolt");
        second.label("Counterspell");

        assertThat(labels()).containsExactly("run,card", "20261107-153012-123,Lightning Bolt",
                "20261107-153012-123-2,Counterspell");
        assertThat(first.labelled()).isTrue();
    }

    @Test
    public void replaceARunsLabelWhenItsPickedAgain() throws IOException {
        TrainingCaptures.Run first = captures.keep(photo("a"), capture(null));
        TrainingCaptures.Run second = captures.keep(photo("b"), capture(null));
        first.label("Lightning Bolt");
        second.label("Counterspell");

        first.label("Lightning Blast");

        assertThat(labels()).containsExactly("run,card", "20261107-153012-123,Lightning Blast",
                "20261107-153012-123-2,Counterspell");
    }

    @Test
    public void markAPhotoLeftWithoutACardUnlabelled() throws IOException {
        TrainingCaptures.Run run = captures.keep(photo("a"), capture(null));

        run.label(TrainingCaptures.UNLABELLED);

        assertThat(labels()).containsExactly("run,card", "20261107-153012-123,UNLABELLED");
    }

    @Test
    public void keepACardAlreadyPickedWhenThePhotoIsLaterLeft() throws IOException {
        TrainingCaptures.Run run = captures.keep(photo("a"), capture(null));
        run.label("Lightning Bolt");

        run.label(TrainingCaptures.UNLABELLED);

        assertThat(labels()).containsExactly("run,card", "20261107-153012-123,Lightning Bolt");
    }

    @Test
    public void quoteACardNameWithACommaOrQuote() throws IOException {
        TrainingCaptures.Run run = captures.keep(photo("a"), capture(null));

        run.label("Borrowing 100,000 Arrows");

        assertThat(labels()).containsExactly("run,card", "20261107-153012-123,\"Borrowing 100,000 Arrows\"");
        assertThat(TrainingCaptures.csvField("Say \"hi\"")).isEqualTo("\"Say \"\"hi\"\"\"");
        assertThat(TrainingCaptures.csvField("Plain")).isEqualTo("Plain");
    }

    @Test
    public void refuseAnOutlineWithoutFourCorners() {
        assertThatThrownBy(() -> capture(new float[6])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void refuseATemporaryPhotoKeptForTraining() {
        TrainingCaptures.Run run = captures.keep(photo("a"), capture(null));

        assertThatThrownBy(() -> new CardPhoto(run.photo(), null, true, run))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CardPhoto(run.photo(), null, false, run).training()).isSameAs(run);
    }
}
