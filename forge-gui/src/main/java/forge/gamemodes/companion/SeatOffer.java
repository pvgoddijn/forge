package forge.gamemodes.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The seats the add dialog offers for a card, and the one it chooses (slice 4's item 11, slice 6's AC-45). Only a card
 * from hand that can be cast at instant speed is narrowed: to the seats whose mana could have paid for it, and with
 * Commander mode on, of those, to the ones whose commanders allow it. The colour identity never takes out the active
 * seat: whether an off-colour card for the active seat moves the turn ahead is
 * {@link CommanderLegality#checkAdd}'s to decide, so it must still be the target to get there. Pure: any thread.
 *
 * @param seats       the seats to offer, in the order given, the given Strings themselves
 * @param preselected the seat chosen, one of {@code seats}
 * @param narrowed    whether some seats were left out, so the dialog adds "show all" (AC-39)
 */
public record SeatOffer(List<String> seats, String preselected, boolean narrowed) {

    /**
     * @param allSeats       every seat, by setup name, in the order to offer them
     * @param activeSeat     the seat whose turn it is; may be null when not known
     * @param current        the seat chosen before, kept when it's still offered; may be null
     * @param fromHand       whether the card comes from hand: a card from elsewhere is never narrowed
     * @param instantSpeed   whether the card can be cast at instant speed (an instant, or with flash)
     * @param manaCandidates the seats whose mana could have paid for it; empty when nobody could have, which offers
     *                       every seat (AC-39)
     * @param identityAllows whether a seat's commanders allow the card; null with Commander mode off, or for a name
     *                       that isn't in the card database
     */
    public static SeatOffer forCard(List<String> allSeats, String activeSeat, String current, boolean fromHand,
                                    boolean instantSpeed, List<String> manaCandidates,
                                    Predicate<String> identityAllows) {
        List<String> offered = allSeats;
        if (fromHand && instantSpeed) {
            List<String> kept = new ArrayList<>();
            for (String seat : allSeats) {
                if (manaCandidates.contains(seat)
                        && (identityAllows == null || seat.equals(activeSeat) || identityAllows.test(seat))) {
                    kept.add(seat);
                }
            }
            if (!kept.isEmpty()) {
                offered = kept;
            }
        }
        // one of the given Strings, as a combo box compares its items by identity
        int at = offered.indexOf(current);
        if (at < 0) {
            at = Math.max(0, offered.indexOf(activeSeat));
        }
        String pick = offered.get(at);
        return new SeatOffer(List.copyOf(offered), pick, offered.size() < allSeats.size());
    }
}
