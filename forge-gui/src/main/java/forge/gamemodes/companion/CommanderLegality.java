package forge.gamemodes.companion;

import forge.StaticData;
import forge.card.CardRules;
import forge.card.CardRulesPredicates;
import forge.deck.DeckFormat;
import forge.deck.DeckRule;
import forge.deck.DeckRuleColorIdentity;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Whether a card can be in a player's deck, going by their commanders' colour identity as Forge's deck rules decide
 * it (spec AC-43): partners and backgrounds combined, plus cards a commander's own rules let in. The banned list and
 * deck size aren't checked. Pure: no game access, so the UI can ask on its own thread.
 */
public final class CommanderLegality {

    /** A later seat that can play a card, and how many turns after the active seat's its turn comes. */
    public record SeatAhead(String seat, int turnsAhead) { }

    private CommanderLegality() { }

    /**
     * True when {@code card} fits {@code commanders}. A seat with no commanders allows everything.
     * <p>
     * Built like {@link DeckFormat#isLegalCardForCommanderPredicate}, but without its extra case for a lone partner
     * commander (it also accepts any card that could partner with it): right in the deck editor, wrong here.
     */
    public static boolean isLegal(PaperCard card, List<PaperCard> commanders) {
        if (commanders.isEmpty()) {
            return true;
        }
        byte identity = 0;
        List<DeckRuleColorIdentity> rules = new ArrayList<>();
        for (PaperCard commander : commanders) {
            identity |= commander.getRules().getColorIdentity().getColor();
            for (DeckRule rule : DeckRule.parseAll(commander)) {
                if (rule instanceof DeckRuleColorIdentity && rule.isActiveFor(DeckSection.Commander)) {
                    rules.add((DeckRuleColorIdentity) rule);
                }
            }
        }
        CardRules candidate = card.getRules();
        return CardRulesPredicates.hasColorIdentity(identity).test(candidate)
                || DeckFormat.allowsOffColorIdentity(rules, candidate)
                || DeckFormat.approvesAdditionalColor(rules, candidate, identity);
    }

    /** Whether {@code card} can be a player's first commander, as Forge's Commander format decides it (AC-44). */
    public static boolean canBeCommander(PaperCard card) {
        return DeckFormat.Commander.isLegalCommander(card.getRules());
    }

    /**
     * Whether {@code first} can be joined by a second commander (AC-44): it has partner, partner with, a partner
     * type such as friends forever, "choose a Background", or is a Doctor or has doctor's companion; or it is a
     * Background itself.
     */
    public static boolean canHaveSecondCommander(PaperCard first) {
        return first.getRules().canBePartnerCommander();
    }

    /** The cards that can be the second commander beside {@code first}: the other half of its pairing. */
    public static Predicate<PaperCard> secondCommanderFor(PaperCard first) {
        CardRules firstRules = first.getRules();
        return card -> !card.getName().equals(first.getName()) && canBeCommander(card)
                && card.getRules().canBePartnerCommanders(firstRules);
    }

    /**
     * {@link #firstLegalSeatAfter(PaperCard, CompanionSetup, String, Collection, Function)} with the setup's own
     * commanders.
     */
    public static Optional<SeatAhead> firstLegalSeatAfter(PaperCard card, CompanionSetup setup, String activeSeat,
                                                          Collection<String> liveSeats) {
        return firstLegalSeatAfter(card, setup, activeSeat, liveSeats, seat -> commanders(setup.commandersOf(seat)));
    }

    /**
     * The cards named {@code names}, from Forge's card database. A name it doesn't have is left out, so it never
     * narrows what a seat can play.
     */
    public static List<PaperCard> commanders(List<String> names) {
        List<PaperCard> cards = new ArrayList<>();
        for (String name : names) {
            PaperCard card = StaticData.instance().getCommonCards().getUniqueByName(name);
            if (card != null) {
                cards.add(card);
            }
        }
        return cards;
    }

    /**
     * The first seat still in the game after {@code activeSeat} in turn order ({@link CompanionSetup#seats()},
     * wrapping round) whose commanders allow {@code card}, never the active seat itself; empty when there is none. A
     * seat that has left the game is passed over and not counted in the turns ahead, as it takes no turns.
     *
     * @param liveSeats    the seats still in the game, by setup name, such as the game view's players
     * @param commandersOf a seat name's commanders, empty for none
     */
    public static Optional<SeatAhead> firstLegalSeatAfter(PaperCard card, CompanionSetup setup, String activeSeat,
                                                          Collection<String> liveSeats,
                                                          Function<String, List<PaperCard>> commandersOf) {
        List<String> seats = new ArrayList<>();
        for (CompanionSetup.Seat seat : setup.seats()) {
            if (seat.name().equals(activeSeat) || liveSeats.contains(seat.name())) {
                seats.add(seat.name());
            }
        }
        int active = seats.indexOf(activeSeat);
        if (active < 0) {
            throw new IllegalArgumentException("No seat named " + activeSeat);
        }
        for (int ahead = 1; ahead < seats.size(); ahead++) {
            String seat = seats.get((active + ahead) % seats.size());
            if (isLegal(card, commandersOf.apply(seat))) {
                return Optional.of(new SeatAhead(seat, ahead));
            }
        }
        return Optional.empty();
    }

    /** What the add dialog does with a card (slice 6, chunk 8). */
    public enum Verdict {
        /** Add it as asked: legal for the target, or not checked. */
        LEGAL,
        /** Illegal for the active seat, legal for a later one: offer to move the turn ahead to it (AC-45). */
        SKIP,
        /** Illegal for the active seat and for every other seat: refuse it (AC-46). */
        NO_SEAT,
        /** Illegal for a target that isn't the active seat: refuse it (AC-46). */
        NOT_FOR_TARGET;

        /** Whether the card is refused, with Retake and Cancel. */
        public boolean refused() {
            return this == NO_SEAT || this == NOT_FOR_TARGET;
        }
    }

    /**
     * The add dialog's answer for a card, and what it says.
     *
     * @param card   the card's name
     * @param target the seat the operator picked
     * @param skip   the seat to move ahead to, for {@link Verdict#SKIP} only; otherwise null
     */
    public record AddCheck(Verdict verdict, String card, String target, SeatAhead skip) {
        private static final String MODE_OFF = " To add it anyway, turn Commander mode off in the game menu.";

        /**
         * The "move ahead" button, such as "Move to Carol": short, so the button keeps its normal text size on a
         * phone; the turns ahead are in the {@link #message}. For {@link Verdict#SKIP} only.
         */
        public String moveLabel() {
            return "Move to " + skip.seat();
        }

        /**
         * Why the card can't go to the target, and for a skip, who it fits and the question the buttons answer. Empty
         * for {@link Verdict#LEGAL}.
         */
        public String message() {
            String outside = card + " isn't legal for " + target + ": it's outside their commander's colour identity.";
            switch (verdict) {
                case SKIP:
                    return outside + " " + skip.seat() + " can play it, " + turns(skip.turnsAhead()) + " ahead.\n\n"
                            + "Move the game to " + skip.seat() + "'s turn and add it there? The turns in between are"
                            + " played, draws and upkeeps included." + MODE_OFF;
                case NO_SEAT:
                    return outside + " It fits no other player either, so it wasn't added." + MODE_OFF;
                case NOT_FOR_TARGET:
                    return outside + " It wasn't added." + MODE_OFF;
                default:
                    return "";
            }
        }

        private static String turns(int n) {
            return n == 1 ? "1 turn" : n + " turns";
        }
    }

    /**
     * {@link #checkAdd(PaperCard, String, boolean, CompanionSetup, String, String, Collection, Function)} with the
     * setup's own commanders, looked up in Forge's card database.
     */
    public static AddCheck checkAdd(PaperCard card, String name, boolean fromHand, CompanionSetup setup,
                                    String target, String activeSeat, Collection<String> liveSeats) {
        return checkAdd(card, name, fromHand, setup, target, activeSeat, liveSeats,
                seat -> commanders(setup.commandersOf(seat)));
    }

    /**
     * What the add dialog does with a card for {@code target} (AC-43, AC-45, AC-46). Only a card from hand that is in
     * the card database is checked, and only with Commander mode on: tokens, emblems and cards from elsewhere are
     * always {@link Verdict#LEGAL}. An illegal card for the active seat is a {@link Verdict#SKIP} to the first later
     * seat still in the game it fits, in the setup's seat order, or {@link Verdict#NO_SEAT}; for any other target,
     * or when the active seat isn't known, it is {@link Verdict#NOT_FOR_TARGET}. Pure: the active seat and the seats
     * still in the game come from the caller.
     *
     * @param card       the card, or null when the name isn't in the card database
     * @param name       the name as picked, for the messages
     * @param activeSeat the seat whose turn it is, by setup name; null when not known
     * @param liveSeats  the seats still in the game, by setup name: one that lost is never moved to
     */
    public static AddCheck checkAdd(PaperCard card, String name, boolean fromHand, CompanionSetup setup,
                                    String target, String activeSeat, Collection<String> liveSeats,
                                    Function<String, List<PaperCard>> commandersOf) {
        if (card == null || !fromHand || !setup.commanderMode() || isLegal(card, commandersOf.apply(target))) {
            return new AddCheck(Verdict.LEGAL, name, target, null);
        }
        boolean activeKnown = setup.seats().stream().anyMatch(seat -> seat.name().equals(activeSeat));
        if (!activeKnown || !target.equals(activeSeat)) {
            return new AddCheck(Verdict.NOT_FOR_TARGET, name, target, null);
        }
        return firstLegalSeatAfter(card, setup, activeSeat, liveSeats, commandersOf)
                .map(ahead -> new AddCheck(Verdict.SKIP, name, target, ahead))
                .orElseGet(() -> new AddCheck(Verdict.NO_SEAT, name, target, null));
    }
}
