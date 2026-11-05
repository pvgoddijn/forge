package forge.gamemodes.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A game's history (AC-26): its log entries as rows to read, oldest first. Undo entries aren't rows: each marks the rows
 * of the steps it took back as undone ({@link CompanionLogEntry#undone}), and they stay in the history (AC-27). A line
 * from a log written before entries is a notice, with no turn or seat.
 *
 * @param rows the rows, oldest first
 */
public record CompanionHistory(List<Row> rows) {

    /**
     * One thing that happened.
     *
     * @param seq      the log entry's seq
     * @param turn     the game's turn, from 1; 0 when not known
     * @param active   the active seat, or null when not known
     * @param affected the seats it's about, maybe none
     * @param kind     what kind of entry it came from; never {@link CompanionLogEntry.Kind#UNDO}
     * @param text     the readable line
     * @param undone   whether a later undo took it back
     */
    public record Row(long seq, int turn, String active, List<String> affected, CompanionLogEntry.Kind kind,
                      String text, boolean undone) {
        public Row {
            affected = List.copyOf(affected);
        }
    }

    public CompanionHistory {
        rows = List.copyOf(rows);
    }

    /** The history of {@code log}, as {@link CompanionStore#loadLog} reads it. */
    public static CompanionHistory of(List<CompanionLogEntry> log) {
        Set<Long> undone = CompanionLogEntry.undone(log);
        List<Row> rows = new ArrayList<>();
        for (CompanionLogEntry entry : log) {
            if (entry.kind() != CompanionLogEntry.Kind.UNDO) {
                rows.add(new Row(entry.seq(), entry.turn(), entry.active(), entry.affected(), entry.kind(),
                        entry.text(), undone.contains(entry.seq())));
            }
        }
        return new CompanionHistory(rows);
    }

    /**
     * The rows affecting {@code seat} (AC-28): those that list it among their affected seats. Not the rows of that
     * seat's turns, as the player affected is its own field.
     */
    public CompanionHistory filter(String seat) {
        List<Row> kept = new ArrayList<>();
        for (Row row : rows) {
            if (row.affected().contains(seat)) {
                kept.add(row);
            }
        }
        return new CompanionHistory(kept);
    }
}
