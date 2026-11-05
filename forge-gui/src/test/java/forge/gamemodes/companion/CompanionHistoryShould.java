package forge.gamemodes.companion;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import forge.gamemodes.companion.CompanionHistory.Row;
import forge.gamemodes.companion.CompanionLogEntry.Kind;

import static org.assertj.core.api.Assertions.assertThat;

/** The history read from a game's log entries: its rows, what's undone, and the filter by player. */
public class CompanionHistoryShould {
    private final List<CompanionLogEntry> log = new ArrayList<>();

    @BeforeMethod
    public void startAnEmptyLog() {
        log.clear(); // TestNG runs every test on one instance
    }

    private long log(CompanionLogEntry entry) {
        log.add(entry.withSeq(log.size() + 1));
        return log.size();
    }

    @Test
    public void carryTurnActiveSeatAffectedSeatsAndText() {
        log(CompanionLogEntry.of(Kind.ADD, 1, 2, "Ann", List.of("Bo"), "Bo casts Shock"));
        log(CompanionLogEntry.of(Kind.ENGINE, 1, 2, "Ann", List.of("Ann", "Cy"), "Shock deals 2 damage to Cy"));

        assertThat(CompanionHistory.of(log).rows()).containsExactly(
                new Row(1, 2, "Ann", List.of("Bo"), Kind.ADD, "Bo casts Shock", false),
                new Row(2, 2, "Ann", List.of("Ann", "Cy"), Kind.ENGINE, "Shock deals 2 damage to Cy", false));
    }

    @Test
    public void markTheRowsOfTheStepsAnUndoTookBackAndNotShowTheUndo() {
        long kept = log(CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "a"));
        long add = log(CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "b"));
        long drain = log(CompanionLogEntry.of(Kind.ENGINE, 2, 1, "Ann", List.of("Bo"), "Bo loses 2 life"));
        log(CompanionLogEntry.undo(1, List.of(2), 1, "Ann", "Undone"));
        long redone = log(CompanionLogEntry.of(Kind.ADD, 2, 1, "Ann", List.of("Ann"), "c"));

        List<Row> rows = CompanionHistory.of(log).rows();

        assertThat(rows).extracting(Row::seq).containsExactly(kept, add, drain, redone);
        assertThat(rows).filteredOn(Row::undone).extracting(Row::seq).containsExactly(add, drain);
    }

    @Test
    public void keepOnlyTheRowsAffectingTheSeat() {
        log(CompanionLogEntry.of(Kind.ADD, 1, 1, "Ann", List.of("Ann"), "Ann casts Opt"));
        log(CompanionLogEntry.of(Kind.ACTION, 1, 1, "Ann", List.of("Bo"), "Bo to 38"));
        log(CompanionLogEntry.of(Kind.CORRECTION, 1, 1, "Ann", List.of("Ann", "Bo"), "Opt is Bo's"));
        log(CompanionLogEntry.of(Kind.NOTICE, 1, 1, "Ann", List.of(), "Saved"));

        assertThat(CompanionHistory.of(log).filter("Bo").rows()).extracting(Row::text)
                .containsExactly("Bo to 38", "Opt is Bo's");
        // Ann's turn, but not about Ann
        assertThat(CompanionHistory.of(log).filter("Ann").rows()).extracting(Row::text)
                .containsExactly("Ann casts Opt", "Opt is Bo's");
        assertThat(CompanionHistory.of(log).filter("Cy").rows()).isEmpty();
    }

    @Test
    public void readAPlainLineFromAnOlderLogAsANotice() {
        log.add(CompanionLogEntry.fromLine("Ann makes Goblin", 1));

        assertThat(CompanionHistory.of(log).rows()).containsExactly(
                new Row(1, 0, null, List.of(), Kind.NOTICE, "Ann makes Goblin", false));
    }

    @Test
    public void showAMoveAsARowForBothSeatsAndMarkItUndone() {
        long move = log(new CompanionLogEntry(0, 2, 1, "Ann", List.of("Ann", "Bo"), Kind.MOVE,
                "Grizzly Bears was moved from Ann's Battlefield to Bo's Battlefield", "Grizzly Bears", null, null));
        log(CompanionLogEntry.undo(1, List.of(2), 1, "Ann", "Undone"));

        assertThat(CompanionHistory.of(log).rows()).containsExactly(new Row(move, 1, "Ann", List.of("Ann", "Bo"),
                Kind.MOVE, "Grizzly Bears was moved from Ann's Battlefield to Bo's Battlefield", true));
        assertThat(CompanionHistory.of(log).filter("Bo").rows()).extracting(Row::seq).containsExactly(move);
    }
}
