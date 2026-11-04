package forge.gamemodes.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps each tracked game in its own folder, {@code <id>/}:
 * <ul>
 * <li>{@code setup.json}: the seats, written once when the game starts;</li>
 * <li>{@code state.txt}: the latest settled state, in Forge's game-state format, replaced whole on each save so a
 * crash leaves either the old state or the new one;</li>
 * <li>{@code log.txt}: what the operator did, one line each.</li>
 * </ul>
 * A folder with no {@code ended} file is the active game.
 * <p>
 * The setup is mapped to JSON by hand, not through Gson's record support, which relies on reflection that isn't
 * dependable on Android.
 */
public class CompanionStore {
    private static final String SETUP = "setup.json";
    private static final String STATE = "state.txt";
    private static final String LOG = "log.txt";
    private static final String ENDED = "ended";
    private static final String DAMAGED_SUFFIX = ".damaged";

    /** A saved game. Its latest state is read when it starts, by {@link #loadState}. */
    public record Saved(String id, CompanionSetup setup) { }

    private final File dir;
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

    public void saveState(String id, String state) {
        writeWhole(new File(new File(dir, id), STATE), state);
    }

    /** The game's latest saved state, or none if it never got to its first save. */
    public Optional<List<String>> loadState(String id) {
        File state = new File(new File(dir, id), STATE);
        return state.exists() ? Optional.of(Arrays.asList(read(state).split("\n"))) : Optional.empty();
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

    /** Adds a line to the game's log. A log that can't be written doesn't stop the game. */
    public void appendLog(String id, String line) {
        File log = new File(new File(dir, id), LOG);
        try (FileOutputStream out = new FileOutputStream(log, true)) {
            out.write((line.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Can't write companion log " + log + ": " + e);
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
        File state = new File(game, STATE);
        return state.exists() ? state.lastModified() : game.lastModified();
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
