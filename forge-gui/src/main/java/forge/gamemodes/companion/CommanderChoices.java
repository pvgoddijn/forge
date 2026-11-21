package forge.gamemodes.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The commanders chosen in the setup screen's commander step (AC-44), per seat row. They are kept when the step is
 * cancelled part way, so the next Start doesn't drop the seats already done: each of those is only asked to confirm
 * its choice, as a seat whose deck names its commanders is, and the step carries on from the seat that was cancelled.
 * A choice moves with its seat when rows are swapped and is forgotten when the seat's deck changes. Not thread-safe:
 * the setup screen's UI thread only.
 */
public final class CommanderChoices {
    private static final String CONFIRM = "Confirm";
    private static final String CHANGE = "Change";
    private static final String NO_COMMANDER = "No commander";

    /**
     * What to ask a seat that already has commanders: {@code options} are Confirm, Change and, when there are
     * commanders, No commander, in that order.
     *
     * @param commanders the commanders Confirm keeps, empty for none
     */
    public record Confirmation(String message, List<String> options, List<String> commanders) {
        public Confirmation {
            options = List.copyOf(options);
            commanders = List.copyOf(commanders);
        }
    }

    /** Each row's chosen commanders; null where nothing was chosen yet. */
    private final List<List<String>> chosen;

    public CommanderChoices(int rows) {
        chosen = new ArrayList<>(Collections.nCopies(rows, null));
    }

    /** Records the commanders chosen for the seat in {@code row}, empty for "No commander". */
    public void choose(int row, List<String> commanders) {
        chosen.set(row, List.copyOf(commanders));
    }

    /** Drops the choice for the seat in {@code row}, as when its deck changes. */
    public void forget(int row) {
        chosen.set(row, null);
    }

    /** Swaps the choices of two rows, as their seats swap. */
    public void swap(int row, int other) {
        Collections.swap(chosen, row, other);
    }

    /**
     * What the commander step asks {@code seat}, in {@code row}: its choice from an earlier, cancelled step to
     * confirm, else its deck's commanders to confirm, else empty, for the picker.
     */
    public Optional<Confirmation> confirmation(int row, CompanionSetup.Seat seat) {
        List<String> choice = chosen.get(row);
        if (choice != null && choice.isEmpty()) {
            return Optional.of(new Confirmation(seat.name() + " has no commander.", List.of(CONFIRM, CHANGE), choice));
        }
        if (choice != null) {
            return Optional.of(new Confirmation(seat.name() + "'s commander" + (choice.size() > 1 ? "s" : "") + ": "
                    + String.join(" and ", choice) + ".", List.of(CONFIRM, CHANGE, NO_COMMANDER), choice));
        }
        if (!seat.commanders().isEmpty()) {
            return Optional.of(new Confirmation(seat.name() + "'s deck has " + String.join(" and ", seat.commanders())
                    + " as its commander" + (seat.commanders().size() > 1 ? "s." : "."),
                    List.of(CONFIRM, CHANGE, NO_COMMANDER), seat.commanders()));
        }
        return Optional.empty();
    }
}
