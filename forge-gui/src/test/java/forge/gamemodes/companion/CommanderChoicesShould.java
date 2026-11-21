package forge.gamemodes.companion;

import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The commander step's choices (AC-44), kept per seat when the step is cancelled part way. */
public class CommanderChoicesShould {
    private static final CompanionSetup.Seat ALICE = new CompanionSetup.Seat("Alice", true);
    private static final CompanionSetup.Seat BOB = new CompanionSetup.Seat("Bob", false);

    @Test
    public void pickASeatWithNoChoiceAndNoDeckCommanders() {
        CommanderChoices choices = new CommanderChoices(CompanionSetup.MAX_PLAYERS);

        assertThat(choices.confirmation(0, ALICE)).isEmpty();
    }

    @Test
    public void keepTheChoicesMadeBeforeACancelSoTheNextStartOnlyConfirmsThem() {
        CommanderChoices choices = new CommanderChoices(CompanionSetup.MAX_PLAYERS);
        // Alice chose Krenko, then the operator cancelled while choosing for Bob
        choices.choose(0, List.of("Krenko, Mob Boss"));

        CommanderChoices.Confirmation alice = choices.confirmation(0, ALICE).orElseThrow();
        assertThat(alice.commanders()).containsExactly("Krenko, Mob Boss");
        assertThat(alice.message()).isEqualTo("Alice's commander: Krenko, Mob Boss.");
        assertThat(alice.options()).containsExactly("Confirm", "Change", "No commander");
        // Bob, where the step was cancelled, is asked from the start again
        assertThat(choices.confirmation(1, BOB)).isEmpty();
    }

    @Test
    public void keepNoCommanderAsAChoice() {
        CommanderChoices choices = new CommanderChoices(CompanionSetup.MAX_PLAYERS);
        choices.choose(1, List.of());

        CommanderChoices.Confirmation bob = choices.confirmation(1, BOB).orElseThrow();
        assertThat(bob.commanders()).isEmpty();
        assertThat(bob.message()).isEqualTo("Bob has no commander.");
        assertThat(bob.options()).containsExactly("Confirm", "Change");
    }

    @Test
    public void confirmADecksCommandersUntilAChoiceReplacesThem() {
        CommanderChoices choices = new CommanderChoices(CompanionSetup.MAX_PLAYERS);
        CompanionSetup.Seat withDeck = new CompanionSetup.Seat("Alice", true,
                List.of("Tymna the Weaver", "Thrasios, Triton Hero"), "");

        CommanderChoices.Confirmation deck = choices.confirmation(0, withDeck).orElseThrow();
        assertThat(deck.commanders()).containsExactly("Tymna the Weaver", "Thrasios, Triton Hero");
        assertThat(deck.message())
                .isEqualTo("Alice's deck has Tymna the Weaver and Thrasios, Triton Hero as its commanders.");
        assertThat(deck.options()).containsExactly("Confirm", "Change", "No commander");

        choices.choose(0, List.of("Krenko, Mob Boss"));
        assertThat(choices.confirmation(0, withDeck).orElseThrow().commanders()).containsExactly("Krenko, Mob Boss");
    }

    @Test
    public void moveChoicesWithSwappedSeatsAndForgetThemWhenTheDeckChanges() {
        CommanderChoices choices = new CommanderChoices(CompanionSetup.MAX_PLAYERS);
        choices.choose(0, List.of("Krenko, Mob Boss"));

        choices.swap(0, 1);
        assertThat(choices.confirmation(0, ALICE)).isEmpty();
        assertThat(choices.confirmation(1, BOB).orElseThrow().commanders()).containsExactly("Krenko, Mob Boss");

        choices.forget(1);
        assertThat(choices.confirmation(1, BOB)).isEmpty();
    }
}
