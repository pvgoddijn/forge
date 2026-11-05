package forge.gamemodes.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Keeps each tracked game in its own folder, {@code <id>/}:
 * <ul>
 * <li>{@code setup.json}: the seats, written once when the game starts;</li>
 * <li>{@code states/<n>.txt}: the settled state after step {@code n}, in Forge's game-state format, one file per
 * step for undo. Each is written whole then renamed into place, so a crash leaves either the old file or the new one.
 * The highest step is the current state. Every step is kept, so undo always reaches step 0, the game's start;</li>
 * <li>{@code state.txt}: a game saved before there were steps has its one state here. It reads as step 0, and becomes
 * {@code states/0.txt} at the next save;</li>
 * <li>{@code log.txt}: what happened, one {@link CompanionLogEntry} per line, as JSON. A line from a log written
 * before entries is plain text, and reads as a notice.</li>
 * </ul>
 * A folder with no {@code ended} file is the active game.
 * <p>
 * The setup is mapped to JSON by hand, not through Gson's record support, which relies on reflection that isn't
 * dependable on Android.
 */
public class CompanionStore {
    private static final String SETUP = "setup.json";
    private static final String STATE = "state.txt";
    private static final String STATES = "states";
    private static final String STEP_SUFFIX = ".txt";
    /** {@link #latestStep} of a game that has no saved state yet. */
    public static final int NO_STEP = -1;
    private static final String LOG = "log.txt";
    private static final String ENDED = "ended";
    private static final String DAMAGED_SUFFIX = ".damaged";

    /** A saved game. Its latest state is read when it starts, by {@link #loadState}. */
    public record Saved(String id, CompanionSetup setup) { }

    private final File dir;
    /** The last seq written to each game's log, read from the log at a game's first append. */
    private final Map<String, Long> lastSeq = new HashMap<>();
    private List<File> unreadable = new ArrayList<>();

    public CompanionStore(File dir) {
        this.dir = dir;
    }

    /** Starts a new game's folder, and returns its id. */
    public String create(CompanionSetup setup) {
        String id = UUID.randomUUID().toString();
        File gameDir = new File(dir, id);
        if (!gameDir.mkdirs() && !gameDir.isDirectory()) {
            throw new UncheckedIOException(new IOException("Can't create " + gameDir));
        }
        writeWhole(new File(gameDir, SETUP), toJson(setup));
        return id;
    }

    /** Replaces the game's setup, for the options the operator can change during the game. */
    public void saveSetup(String id, CompanionSetup setup) {
        writeWhole(new File(new File(dir, id), SETUP), toJson(setup));
    }

    /** Saves {@code state} as the next step, and returns its number: 0 for the game's first save. */
    public int saveState(String id, String state) {
        int step = latestStep(id) + 1;
        saveState(id, step, state);
        return step;
    }

    /**
     * Saves {@code state} as step {@code step}, replacing any step {@code step} there is, and drops every later step:
     * after an undo to {@code step - 1}, the next action's state is step {@code step} again.
     */
    public void saveState(String id, int step, String state) {
        if (step < 0) {
            throw new IllegalArgumentException("No step " + step);
        }
        migrate(id);
        // the later steps go first: a crash in between then leaves the steps up to step - 1, a state that existed
        discardAfter(id, step);
        writeWhole(stepFile(id, step), state);
    }

    /**
     * Deletes every step after {@code step}, so {@code step} is the current state again, for an undo. The highest step
     * goes first, so a crash part way leaves a state that existed.
     */
    public void discardAfter(String id, int step) {
        List<Integer> steps = steps(id);
        for (int i = steps.size() - 1; i >= 0 && steps.get(i) > step; i--) {
            delete(stepFile(id, steps.get(i)));
        }
        if (step < 0) {
            delete(new File(new File(dir, id), STATE)); // a slice 0 game's step 0
        }
    }

    /** The game's current step: the highest saved, 0 for a game saved before steps, or {@link #NO_STEP}. */
    public int latestStep(String id) {
        List<Integer> steps = steps(id);
        if (!steps.isEmpty()) {
            return steps.get(steps.size() - 1);
        }
        return new File(new File(dir, id), STATE).exists() ? 0 : NO_STEP;
    }

    /**
     * The game's latest saved state, or none if it never got to its first save: what a game resumes from. First the
     * log is made to match it ({@link #settleLog}).
     */
    public Optional<List<String>> loadState(String id) {
        int step = latestStep(id);
        settleLog(id, step);
        return step == NO_STEP ? Optional.empty() : loadStep(id, step);
    }

    /**
     * Marks undone the log entries of steps the game doesn't have, so the history says what the resumed board says.
     * An entry carries the step the next save writes, so one with a step after {@code latest} that isn't undone was
     * either never saved (a crash before its save) or its step was discarded by an undo whose log entry was never
     * written (a crash between {@link #discardAfter} and {@link #appendLog}). Either way it isn't on the board, and the
     * next action would reuse its step, so without this it would never show as undone. It's written as an ordinary
     * undo entry back to {@code latest}, so a second resume finds nothing to do.
     */
    private void settleLog(String id, int latest) {
        List<CompanionLogEntry> log = loadLog(id);
        Set<Long> undone = CompanionLogEntry.undone(log);
        SortedSet<Integer> lost = new TreeSet<>();
        CompanionLogEntry last = null;
        for (CompanionLogEntry entry : log) {
            if (entry.kind() != CompanionLogEntry.Kind.UNDO && entry.step() > latest
                    && !undone.contains(entry.seq())) {
                lost.add(entry.step());
            }
            last = entry;
        }
        if (!lost.isEmpty()) {
            appendLog(id, CompanionLogEntry.undo(latest, new ArrayList<>(lost), last.turn(), last.active(),
                    "Not kept when the game was interrupted: back to the last save"));
        }
    }

    /**
     * An ended game's last saved state, or none if it never got to its first save. Unlike {@link #loadState} it writes
     * nothing, so a past game stays as it was: its log isn't settled.
     */
    public Optional<List<String>> loadFinalState(String id) {
        int step = latestStep(id);
        return step == NO_STEP ? Optional.empty() : loadStep(id, step);
    }

    /** The state saved as step {@code step}, or none if that step isn't on disk (never saved, or discarded). */
    public Optional<List<String>> loadStep(String id, int step) {
        File file = stepFile(id, step);
        if (!file.exists() && step == 0) {
            file = new File(new File(dir, id), STATE);
        }
        return file.exists() ? Optional.of(Arrays.asList(read(file).split("\n"))) : Optional.empty();
    }

    private File statesDir(String id) {
        return new File(new File(dir, id), STATES);
    }

    private File stepFile(String id, int step) {
        return new File(statesDir(id), step + STEP_SUFFIX);
    }

    /** The steps on disk, lowest first. Side files from an interrupted write aren't steps. */
    private List<Integer> steps(String id) {
        String[] names = statesDir(id).list();
        List<Integer> steps = new ArrayList<>();
        if (names == null) {
            return steps;
        }
        for (String name : names) {
            if (name.endsWith(STEP_SUFFIX) && name.length() > STEP_SUFFIX.length()) {
                String number = name.substring(0, name.length() - STEP_SUFFIX.length());
                if (number.chars().allMatch(c -> c >= '0' && c <= '9') && number.length() < 10) {
                    steps.add(Integer.parseInt(number));
                }
            }
        }
        Collections.sort(steps);
        return steps;
    }

    /**
     * Creates the steps folder if needed, and moves a slice 0 game's {@code state.txt} in as step 0. A crash between the
     * two leaves {@code state.txt}, still read as step 0, and the next save moves it.
     */
    private void migrate(String id) {
        File states = statesDir(id);
        if (!states.mkdirs() && !states.isDirectory()) {
            throw new UncheckedIOException(new IOException("Can't create " + states));
        }
        File legacy = new File(new File(dir, id), STATE);
        if (legacy.exists()) {
            try {
                Files.move(legacy.toPath(), stepFile(id, 0).toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void delete(File file) {
        if (!file.delete() && file.exists()) {
            throw new UncheckedIOException(new IOException("Can't delete " + file));
        }
    }

    /**
     * A game that has ended, to browse read-only.
     *
     * @param id      its folder, for {@link #loadLog} and {@link #loadFinalState}
     * @param setup   its seats and options
     * @param endedAt when it ended, in milliseconds since the epoch
     */
    public record Ended(String id, CompanionSetup setup, long endedAt) {
        /** The seats' names, in seat order. */
        public List<String> seatNames() {
            List<String> names = new ArrayList<>();
            for (CompanionSetup.Seat seat : setup.seats()) {
                names.add(seat.name());
            }
            return names;
        }
    }

    /**
     * The games that have ended, newest first. A game whose setup doesn't read is left out: past games are only for
     * reading, so there's nothing to recover it for here.
     */
    public List<Ended> listEnded() {
        File[] games = dir.listFiles(f -> f.isDirectory() && !f.getName().endsWith(DAMAGED_SUFFIX)
                && new File(f, ENDED).exists());
        List<Ended> ended = new ArrayList<>();
        if (games == null) {
            return ended;
        }
        for (File game : games) {
            try {
                ended.add(new Ended(game.getName(), load(game).setup(), new File(game, ENDED).lastModified()));
            } catch (RuntimeException e) {
                System.err.println("Can't read ended companion game " + game + ": " + e);
            }
        }
        ended.sort(Comparator.comparingLong(Ended::endedAt).reversed().thenComparing(Ended::id));
        return ended;
    }

    /** Marks the game ended, so it's no longer the active game and Companion doesn't resume it. */
    public void end(String id) {
        writeWhole(new File(new File(dir, id), ENDED), "");
    }

    /** Moves a game that can't be loaded out of the way, so Companion doesn't keep trying to resume it. */
    public File setAside(String id) {
        File game = new File(dir, id);
        File aside = new File(dir, id + DAMAGED_SUFFIX);
        return game.renameTo(aside) ? aside : game;
    }

    /**
     * Adds {@code entry} to the game's log, numbered after the last entry, and returns it as written. A log that can't
     * be written doesn't stop the game: the entry is returned, unwritten. If a crash cut the last line short, the new
     * entry starts on a line of its own, so only the cut line is lost.
     */
    public synchronized CompanionLogEntry appendLog(String id, CompanionLogEntry entry) {
        File log = new File(new File(dir, id), LOG);
        CompanionLogEntry numbered = entry;
        try {
            Long last = lastSeq.get(id);
            numbered = entry.withSeq((last != null ? last : lastSeq(id)) + 1);
            String line = numbered.toLine() + "\n";
            if (endsCutShort(log)) {
                line = "\n" + line;
            }
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            lastSeq.put(id, numbered.seq());
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Can't write companion log " + log + ": " + e);
        }
        return numbered;
    }

    /**
     * The game's log, oldest first. Lines from before entries read as notices; a line that doesn't read, such as the
     * last one cut short by a crash, is skipped. Empty when there is no log yet.
     */
    public List<CompanionLogEntry> loadLog(String id) {
        File log = new File(new File(dir, id), LOG);
        List<CompanionLogEntry> entries = new ArrayList<>();
        if (!log.exists()) {
            return entries;
        }
        long seq = 0;
        for (String line : read(log).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                CompanionLogEntry entry = CompanionLogEntry.fromLine(line, seq + 1);
                entries.add(entry);
                seq = Math.max(seq, entry.seq());
            } catch (RuntimeException e) {
                System.err.println("Skipping a companion log line that doesn't read, in " + log + ": " + e);
            }
        }
        return entries;
    }

    private long lastSeq(String id) {
        List<CompanionLogEntry> log = loadLog(id);
        return log.stream().mapToLong(CompanionLogEntry::seq).max().orElse(0);
    }

    private static boolean endsCutShort(File log) throws IOException {
        if (log.length() == 0) {
            return false;
        }
        try (RandomAccessFile file = new RandomAccessFile(log, "r")) {
            file.seek(file.length() - 1);
            return file.read() != '\n';
        }
    }

    /** The files the last {@link #loadActive()} couldn't read, for telling the operator. */
    public List<File> unreadableFiles() {
        return unreadable;
    }

    /** The game that hasn't ended, most recently played first if a problem ever left more than one. */
    public Optional<Saved> loadActive() {
        unreadable = new ArrayList<>();
        File[] games = dir.listFiles(f -> f.isDirectory() && !f.getName().endsWith(DAMAGED_SUFFIX)
                && !new File(f, ENDED).exists());
        if (games == null) {
            return Optional.empty();
        }
        Arrays.sort(games, (a, b) -> Long.compare(lastPlayed(b), lastPlayed(a)));
        for (File game : games) {
            try {
                return Optional.of(load(game));
            } catch (JsonParseException | IllegalArgumentException | IllegalStateException
                     | UnsupportedOperationException | ClassCastException | NullPointerException e) {
                // A damaged game mustn't stop the mode from opening. Set it aside, for recovery by hand.
                System.err.println("Setting aside damaged companion game " + game + ": " + e);
                File aside = new File(dir, game.getName() + DAMAGED_SUFFIX);
                unreadable.add(game.renameTo(aside) ? aside : game);
            } catch (UncheckedIOException e) {
                // May be temporary, so the folder stays where it is.
                System.err.println("Can't read companion game " + game + ": " + e);
                unreadable.add(game);
            }
        }
        return Optional.empty();
    }

    private static long lastPlayed(File game) {
        // a step written, or discarded by an undo, changes the folder's time
        File states = new File(game, STATES);
        File state = new File(game, STATE);
        return states.isDirectory() ? states.lastModified() : state.exists() ? state.lastModified() : game.lastModified();
    }

    private static Saved load(File game) {
        return new Saved(game.getName(), fromJson(read(new File(game, SETUP))));
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code text} to a side file, then moves it over {@code file}, so a crash never leaves half of it. */
    private static void writeWhole(File file, String text) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync(); // the game has to survive the phone restarting, not just the app
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            temp.delete();
            throw new UncheckedIOException(e);
        }
    }

    static String toJson(CompanionSetup setup) {
        JsonObject json = new JsonObject();
        json.addProperty("version", 2);
        json.addProperty("life", setup.life());
        json.addProperty("hand", setup.handSize());
        json.addProperty("library", setup.librarySize());
        json.addProperty("combat", setup.engineCombat());
        JsonArray seats = new JsonArray();
        for (CompanionSetup.Seat seat : setup.seats()) {
            JsonObject s = new JsonObject();
            s.addProperty("name", seat.name());
            s.addProperty("me", seat.me());
            seats.add(s);
        }
        json.add("seats", seats);
        return json.toString();
    }

    static CompanionSetup fromJson(String text) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        List<CompanionSetup.Seat> seats = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("seats")) {
            JsonObject s = element.getAsJsonObject();
            seats.add(new CompanionSetup.Seat(s.get("name").getAsString(), s.get("me").getAsBoolean()));
        }
        return new CompanionSetup(seats, json.get("life").getAsInt(), json.get("hand").getAsInt(),
                json.get("library").getAsInt(),
                json.has("combat") && json.get("combat").getAsBoolean()); // games saved before the option had it off
    }
}
