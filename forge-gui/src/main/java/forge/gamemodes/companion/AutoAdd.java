package forge.gamemodes.companion;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Auto mode's decisions (slice 7, AC-9): whether a photo's card goes straight into the game, and whether the undo
 * prompt shown after it can still take it back. Pure: any thread.
 */
public final class AutoAdd {
    /** What the add dialog does with a photo's reading. */
    public enum Action {
        /** Show the dialog with the reading, as in confirm mode. */
        DIALOG,
        /** Add the card for the active seat, From hand, Cast it, without the dialog. */
        ADD,
        /**
         * Commander mode refuses the card for the active seat: show slice 6's choice (move ahead, Retake, Cancel), as
         * tapping Add would, never an add without a tap.
         */
        COMMANDER_CHOICE
    }

    /**
     * @param action what to do
     * @param card   the card to add or to ask about; null for {@link Action#DIALOG}
     */
    public record Plan(Action action, String card) {
        static final Plan DIALOG = new Plan(Action.DIALOG, null);
    }

    private AutoAdd() {
    }

    /**
     * What to do with {@code reading} in the add dialog, which adds for {@code activeSeat}, From hand, Cast it. Auto
     * mode adds only when it's on, {@link AutoAddPolicy#cardToAutoAdd} gives a card, the dialog would choose the
     * active seat for it ({@code offerFor}: an instant or a flash card from hand is offered only to the seats that
     * could have cast it, and the dialog then chooses another seat when the active one couldn't, which the operator
     * should see), and Commander mode allows it for that seat. Anything else shows the dialog.
     *
     * @param on         whether auto mode is on
     * @param threshold  the lowest score to add at
     * @param activeSeat the seat the dialog chooses first, by setup name
     * @param offerFor   the seats the dialog would offer a card from hand, the active seat chosen before
     * @param verdictFor slice 6's check of a card from hand for the active seat
     */
    public static Plan plan(boolean on, CardNameReader.Reading reading, double threshold, String activeSeat,
                            Function<String, SeatOffer> offerFor,
                            Function<String, CommanderLegality.Verdict> verdictFor) {
        if (!on) {
            return Plan.DIALOG;
        }
        Optional<String> card = AutoAddPolicy.cardToAutoAdd(reading, threshold);
        if (card.isEmpty()) {
            return Plan.DIALOG;
        }
        String name = card.get();
        if (!activeSeat.equals(offerFor.apply(name).preselected())) {
            return Plan.DIALOG;
        }
        if (verdictFor.apply(name) != CommanderLegality.Verdict.LEGAL) {
            return new Plan(Action.COMMANDER_CHOICE, name);
        }
        return new Plan(Action.ADD, name);
    }

    /**
     * Whether an undo that goes back to step {@code backTo} takes back the add that wrote log entry {@code addSeq},
     * and only that: the add's entry is in the log under step {@code backTo + 1}, it's the first entry of that step
     * that changed the game ({@link CompanionLogEntry.Kind#changesState}; notices before it are fine, and so are
     * entries an undo took back), and nothing but what the engine did (the cast resolving, its effects) was logged
     * after it. Nothing is saved outside the main phases or while an op is parked, so one step can hold a combat, a
     * life change or a turn skip before the add, which the undo would take back too. Another action, a notice, or an
     * undo since means the undo would reach something else, or not this add.
     *
     * @param log    the game's log, oldest first
     * @param addSeq the add's entry, by {@link CompanionLogEntry#seq}
     * @param backTo the step the undo would put back
     */
    public static boolean undoReaches(List<CompanionLogEntry> log, long addSeq, int backTo) {
        int at = -1;
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).seq() == addSeq) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return false;
        }
        CompanionLogEntry add = log.get(at);
        if (add.kind() != CompanionLogEntry.Kind.ADD || add.step() != backTo + 1) {
            return false;
        }
        Set<Long> undone = CompanionLogEntry.undone(log);
        for (CompanionLogEntry earlier : log.subList(0, at)) {
            if (earlier.step() == add.step() && earlier.kind().changesState() && !undone.contains(earlier.seq())) {
                return false;
            }
        }
        for (CompanionLogEntry later : log.subList(at + 1, log.size())) {
            if (later.kind() != CompanionLogEntry.Kind.ENGINE || later.step() != add.step()) {
                return false;
            }
        }
        return true;
    }
}
