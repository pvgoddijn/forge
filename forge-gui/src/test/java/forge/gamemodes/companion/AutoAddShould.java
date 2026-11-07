package forge.gamemodes.companion;

import org.testng.annotations.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Auto mode's decisions (slice 7): whether a photo's card is added without the dialog, and the undo prompt's reach. */
public class AutoAddShould {
    private static final List<String> SEATS = List.of("Alice", "Bob");
    private static final CardNameReader.Reading CLEAR = new CardNameReader.Reading("Lightning Bolt",
            List.of("Lightning Bolt", "Lightning Blow"), true, 1.2, 0.1);
    private static final Function<String, SeatOffer> EVERY_SEAT = card -> new SeatOffer(SEATS, "Alice", false);
    private static final Function<String, CommanderLegality.Verdict> LEGAL = card -> CommanderLegality.Verdict.LEGAL;

    private static AutoAdd.Plan plan(boolean on, CardNameReader.Reading reading, Function<String, SeatOffer> offer,
                                     Function<String, CommanderLegality.Verdict> verdict) {
        return AutoAdd.plan(on, reading, AutoAddPolicy.THRESHOLD, "Alice", offer, verdict);
    }

    @Test
    public void addAClearPhotoForTheActiveSeatWhenOn() {
        assertThat(plan(true, CLEAR, EVERY_SEAT, LEGAL)).isEqualTo(new AutoAdd.Plan(AutoAdd.Action.ADD,
                "Lightning Bolt"));
    }

    @Test
    public void showTheDialogWhenOff() {
        assertThat(plan(false, CLEAR, EVERY_SEAT, LEGAL).action()).isEqualTo(AutoAdd.Action.DIALOG);
    }

    @Test
    public void showTheDialogBelowThePolicy() {
        CardNameReader.Reading poor = new CardNameReader.Reading("Lightn", List.of("Lightning Bolt"), true, 0.2, 0.1);
        assertThat(plan(true, poor, EVERY_SEAT, LEGAL).action()).isEqualTo(AutoAdd.Action.DIALOG);
        assertThat(plan(true, CardNameReader.Reading.NOTHING, EVERY_SEAT, LEGAL).action())
                .isEqualTo(AutoAdd.Action.DIALOG);
    }

    @Test
    public void showTheDialogForAnInstantTheActiveSeatCouldNotHaveCast() {
        Function<String, SeatOffer> onlyBob = card -> new SeatOffer(List.of("Bob"), "Bob", true);
        assertThat(plan(true, CLEAR, onlyBob, LEGAL).action()).isEqualTo(AutoAdd.Action.DIALOG);
    }

    @Test
    public void addAnInstantNarrowedToSeatsThatKeepTheActiveOne() {
        Function<String, SeatOffer> aliceKept = card -> new SeatOffer(SEATS, "Alice", true);
        assertThat(plan(true, CLEAR, aliceKept, LEGAL).action()).isEqualTo(AutoAdd.Action.ADD);
    }

    @Test
    public void askTheCommanderChoiceForAnyVerdictButLegal() {
        for (CommanderLegality.Verdict verdict : CommanderLegality.Verdict.values()) {
            if (verdict == CommanderLegality.Verdict.LEGAL) {
                continue;
            }
            assertThat(plan(true, CLEAR, EVERY_SEAT, card -> verdict))
                    .isEqualTo(new AutoAdd.Plan(AutoAdd.Action.COMMANDER_CHOICE, "Lightning Bolt"));
        }
    }

    // ---- the undo prompt's reach ----

    private static CompanionLogEntry entry(long seq, CompanionLogEntry.Kind kind, int step) {
        return CompanionLogEntry.of(kind, step, 1, "Alice", List.of("Alice"), "line").withSeq(seq);
    }

    private static final CompanionLogEntry ADD = entry(5, CompanionLogEntry.Kind.ADD, 3)
            .withCard("Lightning Bolt", CompanionLogEntry.FromHand.PLACEHOLDER);

    @Test
    public void reachTheAddWhenItIsLatest() {
        List<CompanionLogEntry> log = List.of(entry(4, CompanionLogEntry.Kind.ADD, 2), ADD);
        assertThat(AutoAdd.undoReaches(log, 5, 2)).isTrue();
    }

    @Test
    public void reachTheAddPastWhatTheEngineDidForIt() {
        List<CompanionLogEntry> log = List.of(ADD, entry(6, CompanionLogEntry.Kind.ENGINE, 3),
                entry(7, CompanionLogEntry.Kind.ENGINE, 3));
        assertThat(AutoAdd.undoReaches(log, 5, 2)).isTrue();
    }

    @Test
    public void notReachTheAddPastAnotherAction() {
        for (CompanionLogEntry.Kind kind : List.of(CompanionLogEntry.Kind.ADD, CompanionLogEntry.Kind.ACTION,
                CompanionLogEntry.Kind.MOVE, CompanionLogEntry.Kind.CORRECTION, CompanionLogEntry.Kind.NOTICE)) {
            assertThat(AutoAdd.undoReaches(List.of(ADD, entry(6, kind, 3)), 5, 2)).as(kind.name()).isFalse();
        }
    }

    @Test
    public void notReachTheAddAfterAnUndo() {
        CompanionLogEntry undo = CompanionLogEntry.undo(2, List.of(3), 1, "Alice", "Undone").withSeq(6);
        // the undo went back to step 2; another undo would go back to step 1
        assertThat(AutoAdd.undoReaches(List.of(ADD, undo), 5, 1)).isFalse();
        assertThat(AutoAdd.undoReaches(List.of(ADD, undo), 5, 2)).isFalse();
    }

    @Test
    public void notReachTheAddWhenTheUndoGoesBackToAnotherStep() {
        // the game was saved after the add and moved on (a combat, a new turn): the undo goes back to step 3 only
        List<CompanionLogEntry> log = List.of(ADD, entry(6, CompanionLogEntry.Kind.ENGINE, 4));
        assertThat(AutoAdd.undoReaches(log, 5, 3)).isFalse();
        assertThat(AutoAdd.undoReaches(List.of(ADD), 5, 3)).isFalse();
    }

    @Test
    public void notReachAnAddThatIsNotInTheLog() {
        assertThat(AutoAdd.undoReaches(List.of(ADD), 9, 2)).isFalse();
        assertThat(AutoAdd.undoReaches(List.of(entry(5, CompanionLogEntry.Kind.NOTICE, 3)), 5, 2)).isFalse();
    }

    @Test
    public void notReachTheAddPastAnActionBeforeItInItsStep() {
        // a life change, or a turn skip's action, ran since the save; the undo would take it back too
        for (CompanionLogEntry.Kind kind : List.of(CompanionLogEntry.Kind.ADD, CompanionLogEntry.Kind.ACTION,
                CompanionLogEntry.Kind.MOVE, CompanionLogEntry.Kind.CORRECTION)) {
            assertThat(AutoAdd.undoReaches(List.of(entry(4, kind, 3), ADD), 5, 2)).as(kind.name()).isFalse();
        }
    }

    @Test
    public void notReachTheAddPastACombatBeforeItInItsStep() {
        List<CompanionLogEntry> log = List.of(entry(3, CompanionLogEntry.Kind.ENGINE, 3),
                entry(4, CompanionLogEntry.Kind.ENGINE, 3), ADD);
        assertThat(AutoAdd.undoReaches(log, 5, 2)).isFalse();
    }

    @Test
    public void reachTheAddPastANoticeOrAnUndoneEntryBeforeIt() {
        CompanionLogEntry undone = entry(2, CompanionLogEntry.Kind.ACTION, 3);
        CompanionLogEntry undo = CompanionLogEntry.undo(2, List.of(3), 1, "Alice", "Undone").withSeq(3);
        List<CompanionLogEntry> log = List.of(undone, undo, entry(4, CompanionLogEntry.Kind.NOTICE, 3), ADD);
        assertThat(AutoAdd.undoReaches(log, 5, 2)).isTrue();
    }
}
