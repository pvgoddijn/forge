package forge.gamemodes.companion;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import forge.gamemodes.companion.CompanionLogEntry.Added;
import forge.gamemodes.companion.CompanionLogEntry.FromHand;
import forge.gamemodes.companion.CompanionLogEntry.Kind;

import static org.assertj.core.api.Assertions.assertThat;

/** Which cards the "wrong player" correction offers: this turn's adds, read from the log. */
public class CompanionLogEntryShould {
    private final List<CompanionLogEntry> log = new ArrayList<>();

    @BeforeMethod
    public void startAnEmptyLog() {
        log.clear(); // TestNG runs every test on one instance
    }

    private void add(int step, int turn, String seat, String card, FromHand fromHand) {
        log(new CompanionLogEntry(0, step, turn, seat, List.of(seat), Kind.ADD, card + " added", card, fromHand,
                null));
    }

    private void correct(int step, int turn, String from, String to, String card, FromHand toSide) {
        log(new CompanionLogEntry(0, step, turn, from, List.of(from, to), Kind.CORRECTION, "fixed", card, toSide,
                null));
    }

    private void log(CompanionLogEntry entry) {
        log.add(entry.withSeq(log.size() + 1));
    }

    @Test
    public void offerOnlyTheAddsOfTheTurn() {
        add(1, 1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER);
        add(2, 2, "Bo", "Shock", FromHand.KNOWN);
        log(CompanionLogEntry.of(Kind.ACTION, 2, 2, "Bo", List.of("Ann"), "life"));
        log(CompanionLogEntry.of(Kind.NOTICE, 2, 2, "Bo", List.of("Bo"), "Forest wasn't added").withCard("Forest",
                null));
        add(2, 2, "Cy", "Island", FromHand.NONE);

        assertThat(CompanionLogEntry.addedInTurn(log, 2)).containsExactly(
                new Added(2, "Bo", "Shock", FromHand.KNOWN), new Added(5, "Cy", "Island", FromHand.NONE));
        assertThat(CompanionLogEntry.addedInTurn(log, 3)).isEmpty();
    }

    @Test
    public void leaveOutUndoneAdds() {
        add(1, 1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER);
        add(2, 1, "Ann", "Shock", FromHand.PLACEHOLDER);
        log(CompanionLogEntry.undo(1, List.of(2), 1, "Ann", "Undone"));
        add(2, 1, "Bo", "Island", FromHand.NONE);

        assertThat(CompanionLogEntry.addedInTurn(log, 1)).extracting(Added::card)
                .containsExactly("Grizzly Bears", "Island");
    }

    @Test
    public void followACorrectionToItsNewSeatWithThatSeatsSideOfTheHand() {
        add(1, 1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER);
        add(2, 1, "Ann", "Grizzly Bears", FromHand.KNOWN);
        correct(3, 1, "Ann", "Bo", "Grizzly Bears", FromHand.NONE);

        assertThat(CompanionLogEntry.addedInTurn(log, 1)).containsExactly(
                new Added(1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER),
                new Added(2, "Bo", "Grizzly Bears", FromHand.NONE));
        assertThat(CompanionLogEntry.addedInTurn(log, 1).get(1).reassignTo("Cy"))
                .isEqualTo(new CompanionTable.Reassign("Bo", "Grizzly Bears", "Cy", FromHand.NONE));
    }

    @Test
    public void ignoreAnUndoneCorrection() {
        add(1, 1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER);
        correct(2, 1, "Ann", "Bo", "Grizzly Bears", FromHand.KNOWN);
        log(CompanionLogEntry.undo(1, List.of(2), 1, "Ann", "Undone"));

        assertThat(CompanionLogEntry.addedInTurn(log, 1)).containsExactly(
                new Added(1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER));
    }

    @Test
    public void ignoreACorrectionOfAnEarlierTurnsCard() {
        add(1, 1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER);
        add(2, 2, "Bo", "Grizzly Bears", FromHand.PLACEHOLDER);
        correct(3, 2, "Ann", "Cy", "Grizzly Bears", FromHand.NONE);

        assertThat(CompanionLogEntry.addedInTurn(log, 2)).containsExactly(
                new Added(2, "Bo", "Grizzly Bears", FromHand.PLACEHOLDER));
    }

    @Test
    public void skipAPlainLineFromBeforeEntries() {
        log.add(CompanionLogEntry.fromLine("Grizzly Bears added for Ann", 1));

        assertThat(CompanionLogEntry.addedInTurn(log, 0)).isEmpty();
    }

    @Test
    public void keepAnEmptyHandApartFromNotFromTheHandThroughJson() {
        add(1, 1, "Ann", "Grizzly Bears", FromHand.EMPTY);
        add(1, 1, "Ann", "Treasure", FromHand.NONE);

        assertThat(CompanionLogEntry.fromLine(log.get(0).toLine(), 0).fromHand()).isEqualTo(FromHand.EMPTY);
        assertThat(CompanionLogEntry.fromLine(log.get(1).toLine(), 0).fromHand()).isEqualTo(FromHand.NONE);
        assertThat(FromHand.EMPTY.isFromHand()).isTrue();
        assertThat(FromHand.NONE.isFromHand()).isFalse();
    }

    @Test
    public void readAnOlderLogsNoneAsNotFromTheHand() {
        String line = "{\"seq\":1,\"step\":1,\"turn\":1,\"affected\":[\"Ann\"],\"kind\":\"add\","
                + "\"text\":\"Treasure\",\"card\":\"Treasure\",\"fromHand\":\"none\"}";

        assertThat(CompanionLogEntry.fromLine(line, 0).fromHand()).isEqualTo(FromHand.NONE);
    }

    @Test
    public void readAMoveBackThroughJsonAndNotOfferItAsAnAdd() {
        log(new CompanionLogEntry(0, 2, 1, "Ann", List.of("Ann", "Bo"), Kind.MOVE,
                "Grizzly Bears was moved from Ann's Battlefield to Bo's Battlefield", "Grizzly Bears", null, null));

        CompanionLogEntry read = CompanionLogEntry.fromLine(log.get(0).toLine(), 0);
        assertThat(read).isEqualTo(log.get(0));
        assertThat(log.get(0).toLine()).contains("\"kind\":\"move\"");
        assertThat(CompanionLogEntry.addedInTurn(log, 1)).isEmpty();
    }

    private void move(int step, int turn, String from, String to, String card, FromHand newOwner) {
        log(new CompanionLogEntry(0, step, turn, from, List.of(from, to), Kind.MOVE, "moved", card, newOwner,
                null));
    }

    @Test
    public void followAMoveThatGaveTheCardToAnotherSeat() {
        add(1, 1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER);
        move(2, 1, "Ann", "Bo", "Grizzly Bears", FromHand.NONE);

        assertThat(CompanionLogEntry.addedInTurn(log, 1)).containsExactly(
                new Added(1, "Bo", "Grizzly Bears", FromHand.NONE));
        assertThat(CompanionLogEntry.addedInTurn(log, 1).get(0).reassignTo("Cy"))
                .isEqualTo(new CompanionTable.Reassign("Bo", "Grizzly Bears", "Cy", FromHand.NONE));
    }

    @Test
    public void leaveACardWhereItWasLoggedAfterAControlChangeOrAnUndoneMove() {
        add(1, 1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER);
        move(2, 1, "Ann", "Bo", "Grizzly Bears", null); // to Bo's battlefield: still Ann's
        add(3, 1, "Ann", "Shock", FromHand.KNOWN);
        move(4, 1, "Ann", "Bo", "Shock", FromHand.NONE);
        log(CompanionLogEntry.undo(3, List.of(4), 1, "Ann", "Undone"));

        assertThat(CompanionLogEntry.addedInTurn(log, 1)).containsExactly(
                new Added(1, "Ann", "Grizzly Bears", FromHand.PLACEHOLDER),
                new Added(3, "Ann", "Shock", FromHand.KNOWN));
    }

    @Test
    public void countAMoveAsChangingTheGameSoAResumeSettlesIt() {
        assertThat(Kind.MOVE.changesState()).isTrue();
        log(new CompanionLogEntry(0, 2, 1, "Ann", List.of("Ann", "Bo"), Kind.MOVE, "moved", "Grizzly Bears", null,
                null));

        assertThat(CompanionLogEntry.settle(log, 1)).isPresent();
        assertThat(CompanionLogEntry.settle(log, 2)).isEmpty();
    }
}
