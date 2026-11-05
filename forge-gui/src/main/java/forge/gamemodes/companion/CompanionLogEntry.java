package forge.gamemodes.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One line of a game's log ({@link CompanionStore#appendLog}), stored as one JSON object per line.
 * <p>
 * Seats are named by their setup name, which setup keeps unique. Cards are named by card name, never by id: ids don't
 * survive a load (the game's next id only grows).
 * <p>
 * Undo is append-only: an {@link Kind#UNDO} entry lists in {@link #undid} the steps it took back, and nothing earlier
 * is rewritten. An entry is undone when a later undo entry names its step ({@link #undone}). After an undo to
 * {@code n - 1}, the next action writes step {@code n} again; its entries come after that undo entry, so they aren't
 * undone by it.
 *
 * @param seq      the entry's place in the log, from 1, assigned by the store when it's written
 * @param step     the snapshot the entry leads to: the step the next save writes, so everything between two saves
 *                 shares a step and one undo takes it all back. For an undo, the step it went back to.
 *                 {@link CompanionStore#NO_STEP} when not known (a line from before steps)
 * @param turn     the game's turn when it was written, from 1; 0 when not known
 * @param active   the active seat, or null when not known
 * @param affected the seats the entry is about, maybe none
 * @param kind     what kind of entry
 * @param text     the readable line
 * @param card     the card it's about, by name, or null
 * @param fromHand for an {@link Kind#ADD}, what left the hand (AC-22a), or null when not known
 * @param undid    for an {@link Kind#UNDO}, the steps it took back; empty otherwise
 */
public record CompanionLogEntry(long seq, int step, int turn, String active, List<String> affected, Kind kind,
                                String text, String card, FromHand fromHand, List<Integer> undid) {

    /** What kind of entry. A kind this version doesn't know, from a later one, reads as {@link #NOTICE}. */
    public enum Kind {
        /** The operator added a card. */
        ADD,
        /** Any other operator action that changed the game: a life total, readying creatures. */
        ACTION,
        /** An undo, naming the steps it took back. */
        UNDO,
        /** A card given to the right player (AC-22a). */
        CORRECTION,
        /**
         * A card the operator moved to a zone of a player (AC-22); {@link #affected} has the seat it was with first,
         * then the seat it went to when that's someone else.
         */
        MOVE,
        /** Something the game decided or couldn't do, for the operator to read; and lines from before entries. */
        NOTICE,
        /** What the engine did, copied from its game log (slice 5). */
        ENGINE;

        /** Whether an entry of this kind changed the game, so it's on the board only once its step is saved. */
        public boolean changesState() {
            return this == ADD || this == ACTION || this == CORRECTION || this == MOVE || this == ENGINE;
        }
    }

    /** What left the hand when a card was added, for giving it back (AC-22a). */
    public enum FromHand {
        /** One of the unknown cards. */
        PLACEHOLDER,
        /** The player's known copy. */
        KNOWN,
        /** Nothing, as it was from the hand but the hand count was already 0 (AC-19/AC-20 clamped). */
        EMPTY,
        /**
         * Nothing, as it wasn't from the hand: from elsewhere, a token, an emblem. A log written before {@link #EMPTY}
         * has this for an empty hand as well.
         */
        NONE;

        /** Whether the card was added from the hand, so a correction applies AC-19/AC-20 to the right player. */
        public boolean isFromHand() {
            return this != NONE;
        }
    }

    public CompanionLogEntry {
        affected = affected == null ? List.of() : List.copyOf(affected);
        undid = undid == null ? List.of() : List.copyOf(undid);
        kind = kind == null ? Kind.NOTICE : kind;
        text = text == null ? "" : text;
    }

    /** An entry for {@link CompanionStore#appendLog}, which numbers it. */
    public static CompanionLogEntry of(Kind kind, int step, int turn, String active, List<String> affected,
                                       String text) {
        return new CompanionLogEntry(0, step, turn, active, affected, kind, text, null, null, null);
    }

    /** An undo entry: it went back to {@code toStep}, taking back {@code undid}. */
    public static CompanionLogEntry undo(int toStep, List<Integer> undid, int turn, String active, String text) {
        return new CompanionLogEntry(0, toStep, turn, active, null, Kind.UNDO, text, null, null, undid);
    }

    public CompanionLogEntry withSeq(long seq0) {
        return new CompanionLogEntry(seq0, step, turn, active, affected, kind, text, card, fromHand, undid);
    }

    public CompanionLogEntry withCard(String card0, FromHand fromHand0) {
        return new CompanionLogEntry(seq, step, turn, active, affected, kind, text, card0, fromHand0, undid);
    }

    /**
     * The seqs of the entries that are undone: a later undo entry names their step. Undo entries themselves are never
     * undone (there's no redo).
     */
    public static Set<Long> undone(List<CompanionLogEntry> log) {
        Set<Long> undone = new HashSet<>();
        Set<Integer> undoneSteps = new HashSet<>();
        // newest first, so each undo marks only what came before it
        for (int i = log.size() - 1; i >= 0; i--) {
            CompanionLogEntry entry = log.get(i);
            if (entry.kind == Kind.UNDO) {
                undoneSteps.addAll(entry.undid);
            } else if (undoneSteps.contains(entry.step)) {
                undone.add(entry.seq);
            }
        }
        return Collections.unmodifiableSet(undone);
    }

    /**
     * The undo entry that makes {@code log} say what the board at step {@code latest} says, or none if it already
     * does. An entry carries the step the next save writes, so one that changed the game ({@link Kind#changesState}) with a
     * step after {@code latest} that isn't undone was either never saved (a crash or the game's end before its save)
     * or its step was discarded by an undo whose log entry was never written (a crash between the undo's files and its
     * entry). Either way it isn't on the board. A notice changes nothing, so one after the last save is still true: a
     * save with nothing changed writes nothing, and a notice logged after it keeps the next step. The entry is
     * unnumbered, for {@link CompanionStore#appendLog}.
     */
    public static Optional<CompanionLogEntry> settle(List<CompanionLogEntry> log, int latest) {
        Set<Long> undone = undone(log);
        SortedSet<Integer> lost = new TreeSet<>();
        for (CompanionLogEntry entry : log) {
            if (entry.kind.changesState() && entry.step > latest && !undone.contains(entry.seq)) {
                lost.add(entry.step);
            }
        }
        if (lost.isEmpty()) {
            return Optional.empty();
        }
        CompanionLogEntry last = log.get(log.size() - 1);
        return Optional.of(undo(latest, new ArrayList<>(lost), last.turn, last.active,
                "Not kept when the game was interrupted: back to the last save"));
    }

    /**
     * A card added in a turn, as the "wrong player" correction offers it: whose it is now, and what left that
     * player's hand for it, ready for {@link CompanionTable.Reassign}.
     *
     * @param seq      the add entry's seq
     * @param player   the seat it's logged to now: the add's, or the latest correction's
     * @param card     its name
     * @param fromHand what left {@code player}'s hand for it, or null when not known
     */
    public record Added(long seq, String player, String card, FromHand fromHand) {
        /** The correction that gives this card to {@code to}. */
        public CompanionTable.Reassign reassignTo(String to) {
            return new CompanionTable.Reassign(player, card, to, fromHand);
        }
    }

    /**
     * The cards added in {@code turn} that aren't undone, oldest first, for the "wrong player" correction (AC-22a).
     * A correction that isn't undone moves its card to the seat it gave it to, with that seat's side of the hand, so
     * the card can be corrected again from there; it applies to the latest add before it of that card and seat. Adds
     * with no card or seat (a line from before entries) are left out. Whether each card can still be found is the
     * game's business, not the log's.
     */
    public static List<Added> addedInTurn(List<CompanionLogEntry> log, int turn) {
        Set<Long> undone = undone(log);
        List<Added> added = new ArrayList<>();
        for (CompanionLogEntry entry : log) {
            if (undone.contains(entry.seq) || entry.card == null) {
                continue;
            }
            if (entry.kind == Kind.ADD && entry.turn == turn && !entry.affected.isEmpty()) {
                added.add(new Added(entry.seq, entry.affected.get(0), entry.card, entry.fromHand));
            } else if (entry.kind == Kind.CORRECTION && entry.affected.size() == 2) {
                String from = entry.affected.get(0);
                for (int i = added.size() - 1; i >= 0; i--) {
                    Added a = added.get(i);
                    if (a.player.equals(from) && a.card.equals(entry.card)) {
                        added.set(i, new Added(a.seq, entry.affected.get(1), a.card, entry.fromHand));
                        break;
                    }
                }
            }
        }
        return Collections.unmodifiableList(added);
    }

    /** This entry as one line of JSON, with no newline in it. Unknown fields are left out. */
    String toLine() {
        JsonObject json = new JsonObject();
        json.addProperty("seq", seq);
        json.addProperty("step", step);
        json.addProperty("turn", turn);
        if (active != null) {
            json.addProperty("active", active);
        }
        json.add("affected", strings(affected));
        json.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
        json.addProperty("text", text);
        if (card != null) {
            json.addProperty("card", card);
        }
        if (fromHand != null) {
            json.addProperty("fromHand", fromHand.name().toLowerCase(Locale.ROOT));
        }
        if (!undid.isEmpty()) {
            JsonArray steps = new JsonArray();
            undid.forEach(steps::add);
            json.add("undid", steps);
        }
        return json.toString();
    }

    /**
     * Reads one log line. A line that isn't JSON is from a log written before entries: it reads as a notice numbered
     * {@code seqIfPlain}, with no step, turn or seat. Throws {@link JsonParseException} (or another runtime exception)
     * for a line that starts as JSON but doesn't read, such as one cut short by a crash.
     */
    static CompanionLogEntry fromLine(String line, long seqIfPlain) {
        if (!line.startsWith("{")) {
            return new CompanionLogEntry(seqIfPlain, CompanionStore.NO_STEP, 0, null, null, Kind.NOTICE, line, null,
                    null, null);
        }
        JsonObject json = JsonParser.parseString(line).getAsJsonObject();
        List<Integer> undid = new ArrayList<>();
        if (json.has("undid")) {
            for (JsonElement step : json.getAsJsonArray("undid")) {
                undid.add(step.getAsInt());
            }
        }
        List<String> affected = new ArrayList<>();
        if (json.has("affected")) {
            for (JsonElement seat : json.getAsJsonArray("affected")) {
                affected.add(seat.getAsString());
            }
        }
        return new CompanionLogEntry(json.get("seq").getAsLong(), json.get("step").getAsInt(),
                json.has("turn") ? json.get("turn").getAsInt() : 0, string(json, "active"), affected,
                kindOf(string(json, "kind")), string(json, "text"), string(json, "card"),
                fromHandOf(string(json, "fromHand")), undid);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static Kind kindOf(String name) {
        if (name != null) {
            for (Kind kind : Kind.values()) {
                if (kind.name().equalsIgnoreCase(name)) {
                    return kind;
                }
            }
        }
        return Kind.NOTICE;
    }

    private static FromHand fromHandOf(String name) {
        if (name != null) {
            for (FromHand value : FromHand.values()) {
                if (value.name().equalsIgnoreCase(name)) {
                    return value;
                }
            }
        }
        return null;
    }
}
