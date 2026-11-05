package forge.gamemodes.companion;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import forge.gamemodes.companion.CompanionSnapshotSummary.Seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A past game's final boards, read from a saved state's text. The fixture is a real save: {@link CompanionGameState}'s
 * capture of a three-seat game with Bo as Me, after adding cards in TableEditorTest's way (a tapped Forest, a token, a
 * commander, a known card in hand, a card gone from a hidden hand).
 */
public class CompanionSnapshotSummaryShould {
    /** Seat order Ann, Bo, Cy; Bo is Me, so the save holds p0 = Bo, p1 = Cy, p2 = Ann. */
    private static final CompanionSetup SETUP = CompanionSetup.withDefaults(List.of(
            new CompanionSetup.Seat("Ann", false), new CompanionSetup.Seat("Bo", true),
            new CompanionSetup.Seat("Cy", false)));

    private static List<String> fixture() {
        try (InputStream in = CompanionSnapshotSummaryShould.class.getResourceAsStream("snapshot-three-seats.txt")) {
            return Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void readEverySeatsBoardFromARealSaveInSeatOrder() {
        CompanionSnapshotSummary summary = CompanionSnapshotSummary.of(fixture(), SETUP);

        assertThat(summary.turn()).isEqualTo(1);
        assertThat(summary.active()).isEqualTo("Ann");
        assertThat(summary.seats()).containsExactly(
                new Seat("Ann", -2, 6, List.of(), List.of(), List.of(), List.of("Serra Angel"), List.of()),
                new Seat("Bo", 31, 7, List.of("Swords to Plowshares"),
                        List.of("Forest", "Llanowar Elves", "Soldier Token"), List.of("Lightning Bolt"), List.of(),
                        List.of()),
                new Seat("Cy", 40, 7, List.of(), List.of(), List.of(), List.of(),
                        List.of("Atraxa, Praetors' Voice")));
    }

    @Test
    public void readTheStartingStateOfAGameThatNeverSaved() {
        CompanionSnapshotSummary summary = CompanionSnapshotSummary.of(SETUP.initialState(), SETUP);

        assertThat(summary.active()).isEqualTo("Ann");
        assertThat(summary.seats()).extracting(Seat::life).containsExactly(40, 40, 40);
        assertThat(summary.seats()).extracting(Seat::hiddenInHand).containsExactly(7, 7, 7);
        assertThat(summary.seats()).allSatisfy(seat -> assertThat(seat.battlefield()).isEmpty());
    }

    @Test
    public void nameTokensFoilsAndTokenScriptsByTheirCardName() {
        assertThat(CompanionSnapshotSummary.cardName(
                "t:Treasure Token,P:0,T:0,Cost:no cost,Color:C,Types:Artifact-Treasure,Keywords:,Image:c_a_treasure_sac%7CTMH2%7C1|Tapped"))
                .isEqualTo("Treasure Token");
        assertThat(CompanionSnapshotSummary.cardName("Sol Ring+|Set:C21|Art:1")).isEqualTo("Sol Ring");
        assertThat(CompanionSnapshotSummary.cardName("T:w_1_1_soldier|Set:10E")).isEqualTo("w_1_1_soldier");
    }

    @Test
    public void keepTheStartingLifeOfASeatTheSaveDoesNotMentionAndSkipUnknownPlayers() {
        CompanionSnapshotSummary summary = CompanionSnapshotSummary.of(
                List.of("turn=3", "activeplayer=p9", "p0life=12", "p9life=1", "p1battlefield="), SETUP);

        assertThat(summary.active()).isNull();
        assertThat(summary.seats()).extracting(Seat::life).containsExactly(40, 12, 40);
    }

    @Test
    public void refuseALifeThatIsNotANumber() {
        assertThatThrownBy(() -> CompanionSnapshotSummary.of(List.of("p0life=lots"), SETUP))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
