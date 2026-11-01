package forge.gamemodes.companion;

import org.testng.annotations.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class CardNameMatcherShould {
    private final CardNameMatcher matcher = new CardNameMatcher(Arrays.asList(
            "Sol Ring", "Solemn Simulacrum", "Sol Talisman", "Lightning Bolt", "Sword of Fire and Ice",
            "Delver of Secrets", "Insectile Aberration", "Lim-Dûl's Vault", "Soldier Token"));

    private String best(String text) {
        return matcher.match(text, 5).get(0).card();
    }

    @Test
    public void rankAnExactNameFirst() {
        assertThat(best("Sol Ring")).isEqualTo("Sol Ring");
        assertThat(matcher.match("sol ring", 5).get(0).confidence()).isEqualTo(1);
    }

    @Test
    public void tolerateTypos() {
        assertThat(best("Sol Rng")).isEqualTo("Sol Ring");
        assertThat(best("Lightnng")).isEqualTo("Lightning Bolt");
    }

    @Test
    public void tolerateATypoInALaterWord() {
        assertThat(best("Bolr")).isEqualTo("Lightning Bolt");
    }

    @Test
    public void rankADroppedLetterAboveALookalike() {
        CardNameMatcher lookalikes = new CardNameMatcher(Arrays.asList("Fighting Drake", "Lightning Bolt"));

        assertThat(lookalikes.match("Lightnng", 5).get(0).card()).isEqualTo("Lightning Bolt");
    }

    @Test
    public void matchEitherFaceOfADoubleFacedCard() {
        assertThat(best("Delver of Secrets")).isEqualTo("Delver of Secrets");
        assertThat(best("Insectile Aberration")).isEqualTo("Insectile Aberration");
    }

    @Test
    public void findTokens() {
        assertThat(best("Soldier")).isEqualTo("Soldier Token");
    }

    @Test
    public void ignoreAccentsAndPunctuation() {
        assertThat(best("lim duls vault")).isEqualTo("Lim-Dûl's Vault");
    }

    @Test
    public void giveNothingUnderTwoCharacters() {
        assertThat(matcher.match("s", 5)).isEmpty();
        assertThat(matcher.match(" . ", 5)).isEmpty();
    }

    @Test
    public void matchTwoCharacters() {
        assertThat(matcher.match("so", 5)).isNotEmpty();
    }

    @Test
    public void notInventMatchesForUnrelatedText() {
        assertThat(matcher.match("zzzzzzzz", 5)).isEmpty();
    }

    @Test
    public void respectTheLimit() {
        assertThat(matcher.match("sol", 2)).hasSize(2);
    }
}
