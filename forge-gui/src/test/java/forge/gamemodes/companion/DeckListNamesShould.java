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

    @Test
    public void readTheCommanderSection() {
        String list = "Commander\n1 Krenko, Mob Boss\n\nDeck\n1 Sol Ring\n1 Goblin Lackey";
        assertThat(DeckListNames.commanderNames(list)).containsExactly("Krenko, Mob Boss");
    }

    @Test
    public void readTwoPartners() {
        String list = "Commanders (2):\n1 Tymna the Weaver (C16) 48\n1 Thrasios, Triton Hero (C16) 46\n"
                + "Mainboard:\n1 Sol Ring";
        assertThat(DeckListNames.commanderNames(list)).containsExactly("Tymna the Weaver", "Thrasios, Triton Hero");
        assertThat(DeckListNames.commanderNames("[Commander]\n1 Tymna the Weaver|C16\n1 Thrasios, Triton Hero|C16\n"
                + "[Main]\n1 Sol Ring|CMR")).containsExactly("Tymna the Weaver", "Thrasios, Triton Hero");
    }

    @Test
    public void findNoCommanderWithoutASection() {
        assertThat(DeckListNames.commanderNames("1 Krenko, Mob Boss\n1 Sol Ring")).isEmpty();
        assertThat(DeckListNames.commanderNames("Deck\n1 Sol Ring\nSideboard\n1 Shock")).isEmpty();
        assertThat(DeckListNames.commanderNames("")).isEmpty();
        assertThat(DeckListNames.commanderNames(null)).isEmpty();
    }

    @Test
    public void readMoxfieldStyleHeaders() {
        // Moxfield's MTG Arena export: a "Commander" header, then "Deck", blank lines between
        String list = "Commander\n1 Krenko, Mob Boss (M13) 141\n\nDeck\n1 Sol Ring (CMR) 472\n";
        assertThat(DeckListNames.commanderNames(list)).containsExactly("Krenko, Mob Boss");
        assertThat(DeckListNames.commanderNames("COMMANDER:\n1 Krenko, Mob Boss\nMAINBOARD:\n1 Sol Ring"))
                .containsExactly("Krenko, Mob Boss");
        assertThat(DeckListNames.commanderNames("// Commander\n1 Krenko, Mob Boss\n// Main\n1 Sol Ring"))
                .containsExactly("Krenko, Mob Boss");
    }

    @Test
    public void readArchidektStyleCategories() {
        String list = "1x Krenko, Mob Boss (m13) 141 [Commander{top}]\n1x Sol Ring (cmr) 472 [Ramp]\n"
                + "1x Goblin Lackey [Creature,Haste]\n1x Shock (m19) 156 [Maybeboard{noDeck}{noPrice}]";
        assertThat(DeckListNames.commanderNames(list)).containsExactly("Krenko, Mob Boss");
        assertThat(DeckListNames.cardNames(list)).containsExactly("Krenko, Mob Boss", "Sol Ring", "Goblin Lackey");
    }

    @Test
    public void readACommanderPrefix() {
        assertThat(DeckListNames.commanderNames("CM: Krenko, Mob Boss\n1 Sol Ring")).containsExactly("Krenko, Mob Boss");
    }

    @Test
    public void readForgesTextExport() {
        // Deck.generateTextExport, as the deck editor copies a deck
        String list = "Deck: Goblins\n\nMain: \n1 Sol Ring\n30 Mountain\n\nSideboard: \n1 Shock\n\n"
                + "Commander: \n1 Krenko, Mob Boss\n\n";
        assertThat(DeckListNames.commanderNames(list)).containsExactly("Krenko, Mob Boss");
        assertThat(DeckListNames.cardNames(list)).containsExactly("Sol Ring", "Mountain", "Krenko, Mob Boss");
    }

    @Test
    public void countThePlayedCards() {
        assertThat(DeckListNames.cardCount("Commander\n1 Krenko, Mob Boss\nDeck\n1 Sol Ring\n30 Mountain\n"
                + "Sideboard\n4 Shock")).isEqualTo(32);
        assertThat(DeckListNames.cardCount("Sol Ring\n2x Island")).isEqualTo(3);
        assertThat(DeckListNames.cardCount(null)).isZero();
    }
}
