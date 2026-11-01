package forge.gamemodes.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps each game in its own file, {@code <gameId>.jsonl}, one action per line. Each action is on disk before it
 * counts. A crash can cut the last line short; that line is dropped on load, as its action never counted.
 * <p>
 * Actions are mapped to JSON by hand, not through Gson's record support: that relies on reflection over record
 * classes, which isn't dependable on Android, where classes are desugared and renamed.
 */
public class CompanionStore {
    private static final String EXTENSION = ".jsonl";

    private final File dir;

    public CompanionStore(File dir) {
        this.dir = dir;
    }

    public CompanionGame createGame(List<CompanionAction.PlayerSetup> players) {
        String id = UUID.randomUUID().toString();
        return CompanionGame.start(id, players, action -> append(id, action));
    }

    /** The game that hasn't ended, most recently played first if a problem ever left more than one. */
    public Optional<CompanionGame> loadActive() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(EXTENSION));
        if (files == null) {
            return Optional.empty();
        }
        File newest = null;
        CompanionGame active = null;
        for (File file : files) {
            if (newest != null && file.lastModified() <= newest.lastModified()) {
                continue;
            }
            String name = file.getName();
            String id = name.substring(0, name.length() - EXTENSION.length());
            try {
                CompanionGame game = load(id);
                if (game != null && !game.state().ended()) {
                    newest = file;
                    active = game;
                }
            } catch (IllegalArgumentException | JsonParseException | IllegalStateException e) {
                // A damaged game mustn't stop the mode from opening. Its file stays, for recovery by hand.
                System.err.println("Skipping unreadable companion game " + file + ": " + e);
            }
        }
        return Optional.ofNullable(active);
    }

    /** Loads a game, or returns null if its file holds no complete action. */
    CompanionGame load(String id) {
        File file = fileFor(id);
        List<CompanionAction> log = readLog(file);
        return log.isEmpty() ? null : new CompanionGame(id, log, action -> append(id, action));
    }

    private File fileFor(String id) {
        return new File(dir, id + EXTENSION);
    }

    private void append(String id, CompanionAction action) {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new UncheckedIOException(new IOException("Can't create " + dir));
        }
        byte[] line = (toJson(action) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(fileFor(id), true)) {
            out.write(line);
            out.flush();
            out.getFD().sync(); // the game has to survive the phone restarting, not just the app
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads every complete action, and cuts a torn last line off the file so the next append starts cleanly. */
    private static List<CompanionAction> readLog(File file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<CompanionAction> log = new ArrayList<>();
        int start = 0;
        while (start < bytes.length) {
            int end = start;
            while (end < bytes.length && bytes[end] != '\n') {
                end++;
            }
            boolean lastLine = end >= bytes.length - 1;
            if (end == bytes.length) {
                break; // no line ending: the write never finished
            }
            try {
                log.add(fromJson(new String(bytes, start, end - start, StandardCharsets.UTF_8)));
            } catch (JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException e) {
                if (lastLine) {
                    break;
                }
                throw new IllegalArgumentException("Damaged line at byte " + start + " of " + file, e);
            }
            start = end + 1;
        }

        if (start < bytes.length) {
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                out.setLength(start);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return log;
    }

    static String toJson(CompanionAction action) {
        JsonObject json = new JsonObject();
        json.addProperty("seq", action.seq());
        json.addProperty("time", action.timestamp());
        if (action instanceof CompanionAction.GameStarted started) {
            json.addProperty("type", "GameStarted");
            JsonArray players = new JsonArray();
            for (CompanionAction.PlayerSetup setup : started.players()) {
                JsonObject player = new JsonObject();
                player.addProperty("name", setup.name());
                player.addProperty("me", setup.me());
                players.add(player);
            }
            json.add("players", players);
        } else if (action instanceof CompanionAction.CardAdded added) {
            json.addProperty("type", "CardAdded");
            json.addProperty("player", added.player());
            json.addProperty("card", added.cardName());
        } else if (action instanceof CompanionAction.GameEnded) {
            json.addProperty("type", "GameEnded");
        } else {
            throw new IllegalArgumentException("Unknown action " + action);
        }
        return json.toString();
    }

    static CompanionAction fromJson(String line) {
        JsonObject json = JsonParser.parseString(line).getAsJsonObject();
        int seq = json.get("seq").getAsInt();
        long time = json.get("time").getAsLong();
        String type = json.get("type").getAsString();
        switch (type) {
            case "GameStarted":
                List<CompanionAction.PlayerSetup> players = new ArrayList<>();
                for (JsonElement element : json.getAsJsonArray("players")) {
                    JsonObject player = element.getAsJsonObject();
                    players.add(new CompanionAction.PlayerSetup(
                            player.get("name").getAsString(), player.get("me").getAsBoolean()));
                }
                return new CompanionAction.GameStarted(seq, time, players);
            case "CardAdded":
                return new CompanionAction.CardAdded(seq, time,
                        json.get("player").getAsInt(), json.get("card").getAsString());
            case "GameEnded":
                return new CompanionAction.GameEnded(seq, time);
            default:
                throw new JsonParseException("Unknown action type " + type);
        }
    }
}
