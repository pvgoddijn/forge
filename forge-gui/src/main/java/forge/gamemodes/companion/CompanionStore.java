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
import java.util.Objects;
import java.util.Optional;
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
 * A game followed from another phone (a shared game) has a folder under the host's game id ({@link #createWithId}),
 * and its log is copied in as the host wrote it ({@link #appendVerbatim}). A following store must never call
 * {@link #loadState} for that game: it would write a settle undo of its own, and the log would no longer be the host's
 * byte for byte. It reads with {@link #loadLog} and {@link #loadFinalState}, which write nothing.
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

    /** The text of the notice that stands in for an entry the host couldn't write ({@link #appendVerbatim}). */
    public static final String LOST_ON_HOST = "An entry was lost on the host";

    /** A saved game. Its latest state is read when it starts, by {@link #loadState}. */
    public record Saved(String id, CompanionSetup setup) { }

    /**
     * Told of what the store writes, in write order, on the thread that writes it: how a shared game's host feeds its
     * phones. Each call is made while the store holds its lock for the write, so calls never overlap or reorder.
     * With no journal ({@link #setJournal}) the store behaves exactly as before sharing (AC-114).
     */
    public interface Journal {
        /**
         * The entry to write in place of {@code entry}, before it's numbered: a shared game stamps it here with its
         * term, host and author ({@link CompanionLogEntry#withOrigin}). By default it's written as it is.
         */
        default CompanionLogEntry stamp(String id, CompanionLogEntry entry) {
            return entry;
        }

        /** {@code entry}, numbered, was written to the log as {@code line} (with no newline). */
        void appended(String id, CompanionLogEntry entry, String line);

        /** The entry numbered {@code seq} couldn't be written: the seq is used up, and nothing on disk has it. */
        void appendFailed(String id, long seq);

        /** The game's setup was written: at its creation, or changed during the game. */
        void setupSaved(String id, CompanionSetup setup);

        /** The game was marked ended. */
        void ended(String id);
    }

    /**
     * An entry as {@link #appendLog} wrote it, and whether it reached the log.
     *
     * @param entry   the entry, numbered (and stamped by the journal, if any)
     * @param written false when the log couldn't be written: the entry's seq is still used up
     */
    public record Appended(CompanionLogEntry entry, boolean written) { }

    /**
     * One item of a log copied from the host, for {@link #appendVerbatim}: either a log line, or a gap, a seq the host
     * used up without writing an entry (a failed write), which becomes a tombstone.
     *
     * @param seq  the seq of the entry, or of the gap
     * @param line the log line exactly as the host wrote it, without its newline; null for a gap
     */
    public record Verbatim(long seq, String line) {
        public Verbatim {
            if (line != null && (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0)) {
                throw new IllegalArgumentException("A log line has no line break in it: seq " + seq);
            }
        }

        /** The host's log line {@code line}, numbered {@code seq}. */
        public static Verbatim entry(long seq, String line) {
            return new Verbatim(seq, Objects.requireNonNull(line));
        }

        /** The seq the host used up without writing an entry. */
        public static Verbatim gap(long seq) {
            return new Verbatim(seq, null);
        }

        public boolean isGap() {
            return line == null;
        }
    }

    private final File dir;
    /** The last seq written to each game's log, read from the log at a game's first append. */
    private final Map<String, Long> lastSeq = new HashMap<>();
    private List<File> unreadable = new ArrayList<>();
    private volatile Journal journal;

    public CompanionStore(File dir) {
        this.dir = dir;
    }

    /** The folder holding every game's folder. */
    File dir() {
        return dir;
    }

    /** Tells {@code journal} of every write from now on, or nothing when it's null. */
    public void setJournal(Journal journal) {
        this.journal = journal;
    }

    /** Starts a new game's folder, and returns its id. */
    public String create(CompanionSetup setup) {
        String id = UUID.randomUUID().toString();
        createWithId(id, setup);
        return id;
    }

    /**
     * Makes the folder of a game followed from another phone, under the host's id, and writes its setup. A folder that
     * is already there (following the same game again) is kept, with its log; its setup is replaced.
     *
     * @throws IllegalArgumentException for an id that isn't a plain folder name (letters, digits, '-'), as one from the
     *                                  network might not be
     */
    public void createWithId(String id, CompanionSetup setup) {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Not a game id: " + id);
        }
        File gameDir = new File(dir, id);
        if (!gameDir.mkdirs() && !gameDir.isDirectory()) {
            throw new UncheckedIOException(new IOException("Can't create " + gameDir));
        }
        saveSetup(id, setup);
    }

    /** Replaces the game's setup, for the options the operator can change during the game. */
    public synchronized void saveSetup(String id, CompanionSetup setup) {
        writeWhole(new File(new File(dir, id), SETUP), toJson(setup));
        Journal j = journal;
        if (j != null) {
            j.setupSaved(id, setup);
        }
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
     * Marks undone the log entries of steps the game doesn't have, so the history says what the resumed board says
     * ({@link CompanionLogEntry#settle}). The next action would reuse their step, so without this they would never show
     * as undone. It's written as an ordinary undo entry back to {@code latest}, so a second resume finds nothing to do.
     */
    private void settleLog(String id, int latest) {
        CompanionLogEntry.settle(loadLog(id), latest).ifPresent(undo -> appendLog(id, undo));
    }

    /**
     * An ended game's log as its final board says it: {@link #loadLog} with the entry {@link CompanionLogEntry#settle}
     * finds, numbered after the last, added in memory only. Like {@link #loadFinalState} it writes nothing (AC-30).
     */
    public List<CompanionLogEntry> loadFinalLog(String id) {
        List<CompanionLogEntry> log = loadLog(id);
        long last = log.stream().mapToLong(CompanionLogEntry::seq).max().orElse(0);
        CompanionLogEntry.settle(log, latestStep(id)).ifPresent(undo -> log.add(undo.withSeq(last + 1)));
        return log;
    }

    /**
     * An ended game's last saved state, or none if it never got to its first save. Unlike {@link #loadState} it writes
     * nothing, so a past game stays as it was: its log is settled in memory only, by {@link #loadFinalLog}.
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
    public synchronized void end(String id) {
        writeWhole(new File(new File(dir, id), ENDED), "");
        Journal j = journal;
        if (j != null) {
            j.ended(id);
        }
    }

    /** Moves a game that can't be loaded out of the way, so Companion doesn't keep trying to resume it. */
    public File setAside(String id) {
        File game = new File(dir, id);
        File aside = new File(dir, id + DAMAGED_SUFFIX);
        return game.renameTo(aside) ? aside : game;
    }

    /**
     * Adds {@code entry} to the game's log, numbered after the last entry, and returns it as written. The journal, if
     * any, stamps it first and is told of it after. A log that can't be written doesn't stop the game: the entry is
     * returned, unwritten ({@link Appended#written} false), its seq is still taken, and the journal is told of the
     * failure. If a crash cut the last line short, the new entry starts on a line of its own, so only the cut line is
     * lost.
     */
    public synchronized Appended appendLog(String id, CompanionLogEntry entry) {
        File log = new File(new File(dir, id), LOG);
        Journal j = journal;
        CompanionLogEntry numbered = j != null ? j.stamp(id, entry) : entry;
        String line = null;
        try {
            Long last = lastSeq.get(id);
            numbered = numbered.withSeq((last != null ? last : lastSeq(id)) + 1);
            // taken even if the write fails: the table keeps the entry in memory, so the next one mustn't reuse it
            lastSeq.put(id, numbered.seq());
            line = numbered.toLine();
            String text = line + "\n";
            if (endsCutShort(log)) {
                text = "\n" + text;
            }
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Can't write companion log " + log + ": " + e);
            line = null;
        }
        if (j != null) {
            if (line != null) {
                j.appended(id, numbered, line);
            } else {
                j.appendFailed(id, numbered.seq());
            }
        }
        return new Appended(numbered, line != null);
    }

    /**
     * Copies the host's log entries into this (following) store's log, each with the host's seq and bytes, so the
     * two logs stay the same byte for byte. The journal isn't told: these entries are the host's, not this store's.
     * <ul>
     * <li>{@code items} are in seq order. Each new item must be the next seq after the last one held: a gap no
     * {@link Verbatim#gap} fills is refused.</li>
     * <li>A {@link Verbatim#gap} is written as a tombstone, a {@link CompanionLogEntry.Kind#NOTICE} line
     * "{@value #LOST_ON_HOST}" with that seq.</li>
     * <li>An item whose seq is already held is skipped if the line held is the same, byte for byte (a tombstone for
     * a tombstone), and refused if it isn't.</li>
     * <li>The last seq is updated, so a later {@link #appendLog} (this phone taking over as host) numbers after
     * it.</li>
     * </ul>
     * The whole call is checked before anything is written, so a refused call writes nothing. Everything new is written
     * at once and synced to disk once.
     *
     * @return how many lines were written
     * @throws IllegalArgumentException for a gap no tombstone fills, a different line for a seq already held, or a
     *                                  line that doesn't read with the seq it was given
     * @throws UncheckedIOException     when the log can't be read or written
     */
    public synchronized int appendVerbatim(String id, List<Verbatim> items) {
        File log = new File(new File(dir, id), LOG);
        Long cached = lastSeq.get(id);
        long last = cached != null ? cached : lastSeq(id);
        Map<Long, String> held = null;
        StringBuilder text = new StringBuilder();
        long next = last + 1;
        for (Verbatim item : items) {
            String line = item.isGap() ? tombstone(item.seq()) : item.line();
            if (!item.isGap() && readSeq(line, item.seq()) != item.seq()) {
                throw new IllegalArgumentException("Log line isn't seq " + item.seq() + ": " + line);
            }
            if (item.seq() < next) {
                if (item.seq() > last) {
                    throw new IllegalArgumentException("Seq " + item.seq() + " given twice");
                }
                if (held == null) {
                    held = heldLines(log);
                }
                if (!line.equals(held.get(item.seq()))) {
                    throw new IllegalArgumentException("A different entry " + item.seq() + " is already in " + log);
                }
                continue;
            }
            if (item.seq() > next) {
                throw new IllegalArgumentException("Entries " + next + " to " + (item.seq() - 1) + " are missing");
            }
            text.append(line).append('\n');
            next++;
        }
        if (next == last + 1) {
            return 0;
        }
        try {
            if (endsCutShort(log)) {
                text.insert(0, '\n');
            }
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write(text.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        lastSeq.put(id, next - 1);
        return (int) (next - 1 - last);
    }

    /** The line written for a seq the host used up without writing an entry. */
    private static String tombstone(long seq) {
        return new CompanionLogEntry(seq, NO_STEP, 0, null, null, CompanionLogEntry.Kind.NOTICE, LOST_ON_HOST, null,
                null, null).toLine();
    }

    private static long readSeq(String line, long seqIfPlain) {
        try {
            return CompanionLogEntry.fromLine(line, seqIfPlain).seq();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Log line doesn't read: " + line, e);
        }
    }

    /** The lines in {@code log} that read, by seq, as {@link #loadLog} numbers them. */
    private static Map<Long, String> heldLines(File log) {
        Map<Long, String> lines = new HashMap<>();
        if (!log.exists()) {
            return lines;
        }
        long seq = 0;
        for (String line : read(log).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                long read = CompanionLogEntry.fromLine(line, seq + 1).seq();
                lines.put(read, line);
                seq = Math.max(seq, read);
            } catch (RuntimeException e) {
                // skipped, as loadLog does
            }
        }
        return lines;
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
        json.addProperty("version", 3);
        json.addProperty("life", setup.life());
        json.addProperty("hand", setup.handSize());
        json.addProperty("library", setup.librarySize());
        json.addProperty("combat", setup.engineCombat());
        json.addProperty("commanderMode", setup.commanderMode());
        JsonArray seats = new JsonArray();
        for (CompanionSetup.Seat seat : setup.seats()) {
            JsonObject s = new JsonObject();
            s.addProperty("name", seat.name());
            s.addProperty("me", seat.me());
            JsonArray commanders = new JsonArray();
            seat.commanders().forEach(commanders::add);
            s.add("commanders", commanders);
            s.addProperty("deckList", seat.deckList());
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
            // a seat saved before version 3 has no commanders and no deck
            List<String> commanders = new ArrayList<>();
            if (s.has("commanders")) {
                for (JsonElement commander : s.getAsJsonArray("commanders")) {
                    commanders.add(commander.getAsString());
                }
            }
            String deckList = s.has("deckList") ? s.get("deckList").getAsString() : "";
            seats.add(new CompanionSetup.Seat(s.get("name").getAsString(), s.get("me").getAsBoolean(), commanders,
                    deckList));
        }
        return new CompanionSetup(seats, json.get("life").getAsInt(), json.get("hand").getAsInt(),
                json.get("library").getAsInt(),
                json.has("combat") && json.get("combat").getAsBoolean(), // games saved before the option had it off
                // and before version 3 there were no commanders, so nothing to check a card against
                json.has("commanderMode") && json.get("commanderMode").getAsBoolean());
    }
}
