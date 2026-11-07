package forge.gamemodes.companion;

import forge.item.PaperCard;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** The add dialog's seats for a card (slice 4's item 11) and how they meet the Commander-mode turn skip (AC-45). */
public class SeatOfferShould {
    private static final List<String> SEATS = List.of("Alice", "Bob", "Carol");
    // Alice (Krenko, red) and Bob (Talrand, blue) can't play a green card; Carol (Marwyn, green) can
    private static final Predicate<String> GREEN_ONLY_CAROL = "Carol"::equals;

    @Test
    public void keepTheActiveSeatForAnOffColourCardThatIsNotAnInstantSoTheTurnSkipIsOffered() {
        SeatOffer offer = SeatOffer.forCard(SEATS, "Alice", "Alice", true, false, SEATS, GREEN_ONLY_CAROL);

        assertThat(offer.seats()).containsExactlyElementsOf(SEATS);
        assertThat(offer.preselected()).isEqualTo("Alice");
        assertThat(offer.narrowed()).isFalse();

        // what the dialog then does with it: Alice can't play the elves, so it's a skip to Carol, not an add for her
        PaperCard elves = CommanderLegalityShould.card("Llanowar Elves");
        Map<String, List<PaperCard>> commanders = Map.of("Alice", List.of(CommanderLegalityShould.card(
                "Krenko, Mob Boss")), "Bob", List.of(CommanderLegalityShould.card("Talrand, Sky Summoner")),
                "Carol", List.of(CommanderLegalityShould.card("Marwyn, the Nurturer")));
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(new CompanionSetup.Seat("Alice", true),
                new CompanionSetup.Seat("Bob", false), new CompanionSetup.Seat("Carol", false)));
        CommanderLegality.AddCheck check = CommanderLegality.checkAdd(elves, elves.getName(), true, setup,
                offer.preselected(), "Alice", SEATS, commanders::get);
        assertThat(check.verdict()).isEqualTo(CommanderLegality.Verdict.SKIP);
        assertThat(check.skip()).isEqualTo(new CommanderLegality.SeatAhead("Carol", 2));
    }

    @Test
    public void narrowAnInstantByManaThenIdentityButNeverDropTheActiveSeat() {
        SeatOffer offer = SeatOffer.forCard(SEATS, "Alice", "Alice", true, true, List.of("Alice", "Bob", "Carol"),
                GREEN_ONLY_CAROL);

        assertThat(offer.seats()).containsExactly("Alice", "Carol");
        assertThat(offer.preselected()).isEqualTo("Alice");
        assertThat(offer.narrowed()).isTrue();
    }

    @Test
    public void narrowAnInstantToTheOneOtherSeatThatCouldHaveCastIt() {
        SeatOffer offer = SeatOffer.forCard(SEATS, "Alice", "Alice", true, true, List.of("Bob", "Carol"),
                GREEN_ONLY_CAROL);

        assertThat(offer.seats()).containsExactly("Carol");
        assertThat(offer.preselected()).isEqualTo("Carol");
    }

    @Test
    public void offerEverySeatWhenTheIdentityLeavesNobody() {
        SeatOffer offer = SeatOffer.forCard(SEATS, "Alice", "Alice", true, true, List.of("Bob"), GREEN_ONLY_CAROL);

        assertThat(offer.seats()).containsExactlyElementsOf(SEATS);
        assertThat(offer.preselected()).isEqualTo("Alice");
        assertThat(offer.narrowed()).isFalse();
    }

    @Test
    public void notNarrowACardFromElsewhere() {
        SeatOffer offer = SeatOffer.forCard(SEATS, "Alice", "Alice", false, true, List.of("Carol"), GREEN_ONLY_CAROL);

        assertThat(offer.seats()).containsExactlyElementsOf(SEATS);
        assertThat(offer.preselected()).isEqualTo("Alice");
        assertThat(offer.narrowed()).isFalse();
    }

    @Test
    public void narrowAnInstantByManaAloneWithCommanderModeOff() {
        SeatOffer offer = SeatOffer.forCard(SEATS, "Alice", null, true, true, List.of("Bob", "Carol"), null);

        assertThat(offer.seats()).containsExactly("Bob", "Carol");
        assertThat(offer.preselected()).isEqualTo("Bob");
    }

    @Test
    public void keepTheChoiceAlreadyMadeWhenStillOffered() {
        SeatOffer offer = SeatOffer.forCard(SEATS, "Alice", "Bob", true, false, SEATS, GREEN_ONLY_CAROL);

        assertThat(offer.preselected()).isEqualTo("Bob");
    }

    @Test
    public void offerEverySeatWhenNobodyCouldHaveCastIt() {
        SeatOffer offer = SeatOffer.forCard(SEATS, "Alice", "Alice", true, true, List.of(), null);

        assertThat(offer.seats()).containsExactlyElementsOf(SEATS);
        assertThat(offer.narrowed()).isFalse();
    }
}
