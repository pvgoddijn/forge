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
     * The seats among {@code seats} whose commanders allow {@code card}, in the order given: for narrowing
     * {@link TableEditor#couldHaveCast(List, String)}'s candidates (slice 4's item 11).
     */
    public static List<String> legalSeats(PaperCard card, List<String> seats,
                                          Function<String, List<PaperCard>> commandersOf) {
        List<String> legal = new ArrayList<>();
        for (String seat : seats) {
            if (isLegal(card, commandersOf.apply(seat))) {
                legal.add(seat);
            }
        }
        return legal;
    }

    /**
     * The first seat after {@code activeSeat} in turn order ({@link CompanionSetup#seats()}, wrapping round) whose
     * commanders allow {@code card}, never the active seat itself; empty when there is none.
     *
     * @param commandersOf a seat name's commanders, empty for none
     */
    /** {@link #firstLegalSeatAfter(PaperCard, CompanionSetup, String, Function)} with the setup's own commanders. */
    public static Optional<SeatAhead> firstLegalSeatAfter(PaperCard card, CompanionSetup setup, String activeSeat) {
        return firstLegalSeatAfter(card, setup, activeSeat, seat -> commanders(setup.commandersOf(seat)));
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

    public static Optional<SeatAhead> firstLegalSeatAfter(PaperCard card, CompanionSetup setup, String activeSeat,
                                                          Function<String, List<PaperCard>> commandersOf) {
        List<CompanionSetup.Seat> seats = setup.seats();
        int active = -1;
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).name().equals(activeSeat)) {
                active = i;
                break;
            }
        }
        if (active < 0) {
            throw new IllegalArgumentException("No seat named " + activeSeat);
        }
        for (int ahead = 1; ahead < seats.size(); ahead++) {
            String seat = seats.get((active + ahead) % seats.size()).name();
            if (isLegal(card, commandersOf.apply(seat))) {
                return Optional.of(new SeatAhead(seat, ahead));
            }
        }
        return Optional.empty();
    }
}
