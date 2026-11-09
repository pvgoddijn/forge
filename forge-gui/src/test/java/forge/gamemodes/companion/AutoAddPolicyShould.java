package forge.gamemodes.companion;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class AutoAddPolicyShould {
    private static final double THRESHOLD = 0.5;

    private static CardNameReader.Reading reading(boolean confident, double best, double second, String... cards) {
        return new CardNameReader.Reading("text", List.of(cards), confident, best, second);
    }

    @Test
    public void notAddBelowTheThreshold() {
        assertThat(AutoAddPolicy.cardToAutoAdd(reading(true, 0.49, 0.0, "Sol Ring", "Sol Talisman"), THRESHOLD)).isEmpty();
    }

    @Test
    public void notAddWhenNotConfident() {
        assertThat(AutoAddPolicy.cardToAutoAdd(reading(false, 1.5, 0.0, "Sol Ring", "Sol Talisman"), THRESHOLD)).isEmpty();
    }

    @Test
    public void notAddWithNoCards() {
        assertThat(AutoAddPolicy.cardToAutoAdd(reading(true, 1.5, Double.NaN), THRESHOLD)).isEmpty();
        assertThat(AutoAddPolicy.cardToAutoAdd(CardNameReader.Reading.NOTHING, 0)).isEmpty();
    }

    @Test
    public void addAtOrAboveTheThreshold() {
        assertThat(AutoAddPolicy.cardToAutoAdd(reading(true, 0.5, 0.1, "Sol Ring", "Sol Talisman"), THRESHOLD))
                .contains("Sol Ring");
        assertThat(AutoAddPolicy.cardToAutoAdd(reading(true, 1.2, 0.1, "Sol Ring", "Sol Talisman"), THRESHOLD))
                .contains("Sol Ring");
    }

    @Test
    public void addWithNoSecondScore() {
        assertThat(AutoAddPolicy.cardToAutoAdd(reading(true, 0.8, Double.NaN, "Sol Ring"), THRESHOLD)).contains("Sol Ring");
    }

    @Test
    public void notAddWhenTheSecondIsTooClose() {
        double justShort = 1.0 - AutoAddPolicy.MIN_LEAD + 0.01;
        assertThat(AutoAddPolicy.cardToAutoAdd(reading(true, 1.0, justShort, "Sol Ring", "Sol Talisman"), THRESHOLD)).isEmpty();
        assertThat(AutoAddPolicy.cardToAutoAdd(reading(true, 0.19, 0.185, "Sand Silos", "Vandalblast"), 0.1)).isEmpty();
    }

    @Test
    public void addTheReadersOwnTopCardNotTheMergedListsFirst() {
        CardNameReader.Reading read = reading(true, 1.0, 0.2, "Sol Talisman", "Sol Ring");
        // the text read matches Sol Ring exactly, and it's in the deck: the merged list puts it first
        List<String> merged = CardNameMatcher.mergeReading(read.cards(),
                new CardNameMatcher(List.of("Sol Ring", "Sol Talisman")).match("Sol Ring", 5), Set.of("Sol Ring"));
        assertThat(merged.get(0)).isEqualTo("Sol Ring");
        assertThat(AutoAddPolicy.cardToAutoAdd(read, THRESHOLD)).contains("Sol Talisman");
    }
}
