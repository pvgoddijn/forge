package forge.gamemodes.companion;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    private List<String> names(List<CardNameMatcher.Suggestion> suggestions) {
        return suggestions.stream().map(CardNameMatcher.Suggestion::card).collect(Collectors.toList());
    }

    // The rule (AC-12): an exact match first; then the deck's matches, best first; then every other match, best first.
    // Only names passing the matcher's own cut-off are matches, deck or not.

    @Test
    public void rankDeckCardsAboveCloserSpelledOtherCards() {
        assertThat(names(matcher.match("sol", 5))).startsWith("Sol Ring", "Sol Talisman");

        assertThat(names(matcher.match("sol", 5, Set.of("Solemn Simulacrum", "Sol Talisman"))))
                .containsExactly("Sol Talisman", "Solemn Simulacrum", "Sol Ring", "Soldier Token");
    }

    @Test
    public void stillOfferCardsOutsideTheDeck() {
        assertThat(names(matcher.match("sol", 5, Set.of("Solemn Simulacrum")))).contains("Sol Ring", "Sol Talisman");
    }

    @Test
    public void notOfferADeckCardTheTextDoesNotMatch() {
        assertThat(names(matcher.match("sol", 10, Set.of("Lightning Bolt")))).doesNotContain("Lightning Bolt");
        assertThat(matcher.match("zzzzzzzz", 5, Set.of("Sol Ring"))).isEmpty();
    }

    @Test
    public void keepTheLimitWithADeck() {
        assertThat(names(matcher.match("sol", 2, Set.of("Solemn Simulacrum"))))
                .containsExactly("Solemn Simulacrum", "Sol Ring");
    }

    @Test
    public void rankAnExactMatchAboveTheDeck() {
        CardNameMatcher shocks = new CardNameMatcher(Arrays.asList("Shockwave", "Shock"));

        assertThat(names(shocks.match("shock", 5, Set.of("Shockwave")))).containsExactly("Shock", "Shockwave");
    }

    @Test
    public void rankAsBeforeWithoutADeck() {
        assertThat(matcher.match("sol", 5, Set.of())).isEqualTo(matcher.match("sol", 5));
    }

    @Test
    public void preferEachFaceOfASplitCardInTheDeck() {
        CardNameMatcher ice = new CardNameMatcher(Arrays.asList("Ice Floe", "Thin Ice"));

        assertThat(names(ice.match("ice", 5, Set.of("Fire // Thin Ice")))).containsExactly("Thin Ice", "Ice Floe");
    }

    @Test
    public void mergeAReadingWithDeckCardsFirst() {
        List<String> reader = List.of("Lightning Bolt", "Lightning Helix", "Lightning Strike", "Lightning Greaves");
        List<CardNameMatcher.Suggestion> matches = List.of(new CardNameMatcher.Suggestion("Lightning Bolt", 0.95),
                new CardNameMatcher.Suggestion("Lightning Axe", 0.94),
                new CardNameMatcher.Suggestion("Lightning Dragon", 0.93));

        assertThat(CardNameMatcher.mergeReading(reader, matches, Set.of("Lightning Greaves", "Lightning Axe")))
                .containsExactly("Lightning Greaves", "Lightning Axe", "Lightning Bolt", "Lightning Helix",
                        "Lightning Strike", "Lightning Dragon");
    }

    @Test
    public void mergeAReadingAsReadWithoutADeck() {
        List<String> reader = List.of("Lightning Bolt", "Lightning Helix");
        List<CardNameMatcher.Suggestion> matches = List.of(new CardNameMatcher.Suggestion("Lightning Axe", 0.9),
                new CardNameMatcher.Suggestion("Lightning Bolt", 0.8));

        assertThat(CardNameMatcher.mergeReading(reader, matches, Set.of()))
                .containsExactly("Lightning Bolt", "Lightning Helix", "Lightning Axe");
    }

    @Test
    public void mergeAnExactMatchForTheTextReadFirst() {
        List<CardNameMatcher.Suggestion> matches = List.of(new CardNameMatcher.Suggestion("Shock", 1),
                new CardNameMatcher.Suggestion("Shockwave", 0.9));

        assertThat(CardNameMatcher.mergeReading(List.of("Shockwave"), matches, Set.of("Shockwave")))
                .containsExactly("Shock", "Shockwave");
    }
}
