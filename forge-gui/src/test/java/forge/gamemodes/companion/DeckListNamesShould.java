package forge.gamemodes.companion;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class DeckListNamesShould {
    @Test
    public void readACountAndAName() {
        assertThat(DeckListNames.cardNames("1 Sol Ring\n4 Lightning Bolt")).containsExactly("Sol Ring", "Lightning Bolt");
    }

    @Test
    public void readANameWithoutACount() {
        assertThat(DeckListNames.cardNames("Sol Ring")).containsExactly("Sol Ring");
    }

    @Test
    public void dropTheSetAndCollectorNumber() {
        assertThat(DeckListNames.cardNames("1x Sol Ring (CMR) 123\n1 Arcane Signet [ELD]\n1 Mox Opal (SOM) 179 *F*"))
                .containsExactly("Sol Ring", "Arcane Signet", "Mox Opal");
    }

    @Test
    public void readForgesOwnFormat() {
        assertThat(DeckListNames.cardNames("[metadata]\nName=Goblins\n[Commander]\n1 Krenko, Mob Boss|M13|1\n"
                + "[Main]\n1 Sol Ring|CMR\n[Sideboard]\n1 Shock|M19"))
                .containsExactly("Krenko, Mob Boss", "Sol Ring");
    }

    @Test
    public void readSectionHeaders() {
        assertThat(DeckListNames.cardNames("Commander\n1 Krenko, Mob Boss\n\nDeck\n1 Sol Ring\n\nSideboard\n1 Shock"))
                .containsExactly("Krenko, Mob Boss", "Sol Ring");
        assertThat(DeckListNames.cardNames("Commander (1):\n1 Krenko, Mob Boss\nMainboard:\n1 Sol Ring"))
                .containsExactly("Krenko, Mob Boss", "Sol Ring");
    }

    @Test
    public void readSideboardAndCommanderPrefixes() {
        assertThat(DeckListNames.cardNames("CM: Krenko, Mob Boss\n1 Sol Ring\nSB: 1 Shock"))
                .containsExactly("Krenko, Mob Boss", "Sol Ring");
    }

    @Test
    public void skipBlankLinesAndComments() {
        assertThat(DeckListNames.cardNames("# my goblins\n\n   \n// ramp\n1 Sol Ring\n"))
                .containsExactly("Sol Ring");
    }

    @Test
    public void keepSplitCardNames() {
        assertThat(DeckListNames.cardNames("1 Fire // Ice\n1 Wear / Tear"))
                .containsExactly("Fire // Ice", "Wear // Tear");
    }

    @Test
    public void listARepeatedCardOnce() {
        assertThat(DeckListNames.cardNames("1 Mountain\n10 Mountain")).containsExactly("Mountain");
    }

    @Test
    public void giveNothingForAnEmptyList() {
        assertThat(DeckListNames.cardNames("")).isEmpty();
        assertThat(DeckListNames.cardNames(null)).isEmpty();
    }
}
