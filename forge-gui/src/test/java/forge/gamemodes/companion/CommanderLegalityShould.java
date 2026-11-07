package forge.gamemodes.companion;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.item.PaperCard;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real card scripts from {@code res/cardsfolder}, read one by one so no card database has to load. */
public class CommanderLegalityShould {
    private static final PaperCard KRENKO = card("Krenko, Mob Boss");
    private static final PaperCard MARWYN = card("Marwyn, the Nurturer");
    private static final PaperCard TALRAND = card("Talrand, Sky Summoner");
    private static final PaperCard THRASIOS = card("Thrasios, Triton Hero");
    private static final PaperCard TYMNA = card("Tymna the Weaver");
    private static final PaperCard GRIZZLEGOM = card("Grizzlegom, Hurloon Hero");
    private static final PaperCard LLANOWAR_ELVES = card("Llanowar Elves");
    private static final PaperCard SOL_RING = card("Sol Ring");
    private static final PaperCard KITCHEN_FINKS = card("Kitchen Finks");
    private static final PaperCard GRUUL_TURF = card("Gruul Turf");
    private static final PaperCard AZORIUS_CHANCERY = card("Azorius Chancery");
    private static final PaperCard SWORDS = card("Swords to Plowshares");

    static PaperCard card(String name) {
        String file = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replace(' ', '_') + ".txt";
        Path path = Paths.get("res", "cardsfolder", file.substring(0, 1), file);
        try {
            CardRules rules = CardRules.fromScript(Files.readAllLines(path, StandardCharsets.UTF_8));
            return new PaperCard(rules, "", CardRarity.Common);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void allowAMonoColouredCardOnlyInItsColour() {
        assertThat(CommanderLegality.isLegal(LLANOWAR_ELVES, List.of(MARWYN))).isTrue();
        assertThat(CommanderLegality.isLegal(LLANOWAR_ELVES, List.of(KRENKO))).isFalse();
    }

    @Test
    public void allowAColourlessCardForEveryone() {
        assertThat(CommanderLegality.isLegal(SOL_RING, List.of(KRENKO))).isTrue();
        assertThat(CommanderLegality.isLegal(SOL_RING, List.of(MARWYN))).isTrue();
        assertThat(CommanderLegality.isLegal(SOL_RING, List.of(THRASIOS, TYMNA))).isTrue();
    }

    /** Forge counts both halves of a hybrid symbol in the colour identity (rule 903.4). */
    @Test
    public void countBothColoursOfAHybridCard() {
        assertThat(CommanderLegality.isLegal(KITCHEN_FINKS, List.of(MARWYN))).isFalse();
        assertThat(CommanderLegality.isLegal(KITCHEN_FINKS, List.of(THRASIOS, TYMNA))).isTrue();
    }

    @Test
    public void needEveryColourOfAMultiColouredCard() {
        assertThat(CommanderLegality.isLegal(GRUUL_TURF, List.of(KRENKO))).isFalse();
        assertThat(CommanderLegality.isLegal(GRUUL_TURF, List.of(MARWYN))).isFalse();
        assertThat(CommanderLegality.isLegal(GRUUL_TURF, List.of(GRIZZLEGOM))).isTrue();
    }

    @Test
    public void giveALandItsColourIdentity() {
        assertThat(CommanderLegality.isLegal(AZORIUS_CHANCERY, List.of(TALRAND))).isFalse();
        assertThat(CommanderLegality.isLegal(AZORIUS_CHANCERY, List.of(THRASIOS, TYMNA))).isTrue();
    }

    @Test
    public void followACommandersOwnRule() {
        // Grizzlegom (red-green): "A deck with this commander can have any land cards."
        assertThat(CommanderLegality.isLegal(AZORIUS_CHANCERY, List.of(GRIZZLEGOM))).isTrue();
        assertThat(CommanderLegality.isLegal(SWORDS, List.of(GRIZZLEGOM))).isFalse();
    }

    @Test
    public void combineTwoPartners() {
        List<PaperCard> partners = List.of(THRASIOS, TYMNA);
        assertThat(CommanderLegality.isLegal(LLANOWAR_ELVES, partners)).isTrue();
        assertThat(CommanderLegality.isLegal(SWORDS, partners)).isTrue();
        assertThat(CommanderLegality.isLegal(KRENKO, partners)).isFalse();
    }

    /** Forge's deck-editor predicate also lets in a card that could partner with a lone partner commander. */
    @Test
    public void notAcceptAPossiblePartnerOutsideTheIdentity() {
        assertThat(CommanderLegality.isLegal(TYMNA, List.of(THRASIOS))).isFalse();
    }

    @Test
    public void allowEverythingForASeatWithNoCommander() {
        assertThat(CommanderLegality.isLegal(SWORDS, List.of())).isTrue();
        assertThat(CommanderLegality.isLegal(KITCHEN_FINKS, List.of())).isTrue();
    }

    // Seat order Alice, Bob, Carol, Dan: Krenko, Talrand, Marwyn, no commander.
    private final CompanionSetup table = CompanionSetup.withDefaults(List.of(
            new CompanionSetup.Seat("Alice", true), new CompanionSetup.Seat("Bob", false),
            new CompanionSetup.Seat("Carol", false), new CompanionSetup.Seat("Dan", false)));
    private final Map<String, List<PaperCard>> commanders = Map.of(
            "Alice", List.of(KRENKO), "Bob", List.of(TALRAND), "Carol", List.of(MARWYN), "Dan", List.of());
    private final CompanionSetup threeSeats = CompanionSetup.withDefaults(List.of(
            new CompanionSetup.Seat("Alice", true), new CompanionSetup.Seat("Bob", false),
            new CompanionSetup.Seat("Carol", false)));

    private Optional<CommanderLegality.SeatAhead> after(PaperCard card, CompanionSetup setup, String active) {
        return CommanderLegality.firstLegalSeatAfter(card, setup, active, allOf(setup), commanders::get);
    }

    private static List<String> allOf(CompanionSetup setup) {
        return setup.seats().stream().map(CompanionSetup.Seat::name).toList();
    }

    /** A seat that has left the game takes no turns, so it's neither offered nor counted. */
    @Test
    public void passOverASeatThatLeftTheGame() {
        // Alice, Bob, Carol, Dan; Carol (Marwyn) lost: the elves fit Dan, two turns on, not Carol
        assertThat(CommanderLegality.firstLegalSeatAfter(LLANOWAR_ELVES, table, "Alice", List.of("Alice", "Bob", "Dan"),
                commanders::get)).contains(new CommanderLegality.SeatAhead("Dan", 2));
        // Swords fits only Dan, three turns on with everyone in; two with Bob gone
        assertThat(CommanderLegality.firstLegalSeatAfter(SWORDS, table, "Alice", List.of("Alice", "Carol", "Dan"),
                commanders::get)).contains(new CommanderLegality.SeatAhead("Dan", 2));
    }

    @Test
    public void refuseWhenOnlyASeatThatLeftCouldPlayIt() {
        CommanderLegality.AddCheck check = CommanderLegality.checkAdd(LLANOWAR_ELVES, "Llanowar Elves", true,
                threeSeats, "Alice", "Alice", List.of("Alice", "Bob"), commanders::get);
        assertThat(check.verdict()).isEqualTo(CommanderLegality.Verdict.NO_SEAT);
    }

    @Test
    public void findTheNextLegalSeatInTurnOrder() {
        assertThat(after(LLANOWAR_ELVES, threeSeats, "Alice")).contains(new CommanderLegality.SeatAhead("Carol", 2));
        assertThat(after(LLANOWAR_ELVES, table, "Bob")).contains(new CommanderLegality.SeatAhead("Carol", 1));
    }

    @Test
    public void wrapRoundPastTheLastSeat() {
        assertThat(after(KRENKO, threeSeats, "Bob")).contains(new CommanderLegality.SeatAhead("Alice", 2));
    }

    @Test
    public void stopAtASeatWithNoCommander() {
        assertThat(after(SWORDS, table, "Alice")).contains(new CommanderLegality.SeatAhead("Dan", 3));
    }

    @Test
    public void neverReturnTheActiveSeat() {
        assertThat(after(KRENKO, threeSeats, "Alice")).isEmpty();
        assertThat(after(SOL_RING, threeSeats, "Alice")).contains(new CommanderLegality.SeatAhead("Bob", 1));
    }

    @Test
    public void returnNoneWhenNobodyCanPlayIt() {
        assertThat(after(SWORDS, threeSeats, "Alice")).isEmpty();
        assertThat(after(KITCHEN_FINKS, threeSeats, "Carol")).isEmpty();
    }

    @Test
    public void refuseAnUnknownActiveSeat() {
        assertThatThrownBy(() -> after(SOL_RING, threeSeats, "Eve")).isInstanceOf(IllegalArgumentException.class);
    }

    private CommanderLegality.AddCheck check(PaperCard card, boolean fromHand, CompanionSetup setup, String target,
                                             String active) {
        String name = card == null ? "Goblin" : card.getName();
        return CommanderLegality.checkAdd(card, name, fromHand, setup, target, active, allOf(setup), commanders::get);
    }

    @Test
    public void addALegalCardAsAsked() {
        assertThat(check(LLANOWAR_ELVES, true, threeSeats, "Carol", "Alice").verdict())
                .isEqualTo(CommanderLegality.Verdict.LEGAL);
        assertThat(check(SOL_RING, true, threeSeats, "Alice", "Alice").verdict())
                .isEqualTo(CommanderLegality.Verdict.LEGAL);
    }

    @Test
    public void notCheckWithCommanderModeOffACardFromElsewhereOrATokenName() {
        assertThat(check(LLANOWAR_ELVES, true, threeSeats.withCommanderMode(false), "Alice", "Alice").verdict())
                .isEqualTo(CommanderLegality.Verdict.LEGAL);
        assertThat(check(LLANOWAR_ELVES, false, threeSeats, "Alice", "Alice").verdict())
                .isEqualTo(CommanderLegality.Verdict.LEGAL);
        assertThat(check(null, true, threeSeats, "Alice", "Alice").verdict())
                .isEqualTo(CommanderLegality.Verdict.LEGAL);
    }

    @Test
    public void offerToMoveAheadForTheActiveSeat() {
        CommanderLegality.AddCheck check = check(LLANOWAR_ELVES, true, threeSeats, "Alice", "Alice");
        assertThat(check.verdict()).isEqualTo(CommanderLegality.Verdict.SKIP);
        assertThat(check.verdict().refused()).isFalse();
        assertThat(check.skip()).isEqualTo(new CommanderLegality.SeatAhead("Carol", 2));
        assertThat(check.moveLabel()).isEqualTo("Move to Carol's turn (2 turns ahead)");
        assertThat(check.message()).contains("Llanowar Elves", "Alice", "Carol", "Commander mode off");
    }

    @Test
    public void sayOneTurnAheadInTheSingular() {
        CommanderLegality.AddCheck check = check(LLANOWAR_ELVES, true, threeSeats, "Bob", "Bob");
        assertThat(check.moveLabel()).isEqualTo("Move to Carol's turn (1 turn ahead)");
    }

    @Test
    public void refuseACardNobodyCanPlay() {
        CommanderLegality.AddCheck check = check(SWORDS, true, threeSeats, "Alice", "Alice");
        assertThat(check.verdict()).isEqualTo(CommanderLegality.Verdict.NO_SEAT);
        assertThat(check.verdict().refused()).isTrue();
        assertThat(check.skip()).isNull();
        assertThat(check.message()).contains("no other player", "Commander mode off");
    }

    /** AC-46: a target the operator picked who isn't the active seat is never moved ahead to another seat. */
    @Test
    public void refuseAnIllegalCardForATargetThatIsNotActive() {
        CommanderLegality.AddCheck check = check(LLANOWAR_ELVES, true, threeSeats, "Bob", "Alice");
        assertThat(check.verdict()).isEqualTo(CommanderLegality.Verdict.NOT_FOR_TARGET);
        assertThat(check.verdict().refused()).isTrue();
        assertThat(check.message()).contains("Bob", "Commander mode off");
    }

    @Test
    public void refuseRatherThanSkipWhenTheActiveSeatIsUnknown() {
        assertThat(check(LLANOWAR_ELVES, true, threeSeats, "Alice", null).verdict())
                .isEqualTo(CommanderLegality.Verdict.NOT_FOR_TARGET);
    }
}
