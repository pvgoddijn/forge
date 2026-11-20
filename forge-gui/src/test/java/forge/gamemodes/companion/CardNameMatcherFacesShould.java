package forge.gamemodes.companion;

import forge.CardStorageReader;
import forge.StaticData;
import forge.item.PaperCard;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-13, on a real card database: a small one, from a few scripts in {@code res/cardsfolder} and the real editions, so
 * the faces come from Forge's own card rules and not from a list made up for the test.
 */
public class CardNameMatcherFacesShould {
    private static final String[] SCRIPTS = {
        "d/delver_of_secrets_insectile_aberration.txt", // transforming double-faced
        "e/esika_god_of_the_tree_the_prismatic_bridge.txt", // modal double-faced
        "f/fire_ice.txt", // split
        "b/bonecrusher_giant_stomp.txt", // adventure
        "s/sol_ring.txt",
    };

    private CardNameMatcher matcher;

    @BeforeClass
    public void loadCards() {
        try {
            Path cards = Files.createTempDirectory("matcher-cards");
            for (String script : SCRIPTS) {
                Path to = cards.resolve(script);
                Files.createDirectories(to.getParent());
                Files.copy(Paths.get("res", "cardsfolder", script), to);
            }
            Path tokens = Files.createTempDirectory("matcher-tokens");
            Path customEditions = Files.createTempDirectory("matcher-editions");
            Lang.createInstance("en-US");
            Localizer.getInstance().initialize("en-US", "res/languages/");
            new StaticData(new CardStorageReader(cards + "/", null, false),
                    new CardStorageReader(tokens + "/", null, false), null, null, "res/editions/",
                    customEditions.toString(), "res/blockdata/", "", "Latest Art All Editions", true, false, false,
                    false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        matcher = CardNameMatcher.fromStaticData();
    }

    private List<String> names(String text) {
        return matcher.match(text, 10).stream().map(CardNameMatcher.Suggestion::card).collect(Collectors.toList());
    }

    @Test
    public void offerTheCardForTheBackFaceOfADoubleFacedCard() {
        assertThat(names("Insectile Aberration")).containsExactly("Insectile Aberration", "Delver of Secrets");
        assertThat(names("Insectile")).contains("Delver of Secrets");
    }

    @Test
    public void offerTheCardForTheBackFaceOfAModalDoubleFacedCard() {
        assertThat(names("The Prismatic Bridge")).containsExactly("The Prismatic Bridge", "Esika, God of the Tree");
    }

    @Test
    public void keepTheBackFaceItselfFirstForAnExactRead() {
        assertThat(matcher.match("Insectile Aberration", 10).get(0).confidence()).isEqualTo(1);
        assertThat(matcher.match("Insectile Aberration", 10).get(1).confidence()).isLessThan(1);
    }

    @Test
    public void offerTheCardThroughAFilter() {
        List<String> offered = matcher.matchWhere("Insectile Aberration", 10, card -> true).stream()
                .map(CardNameMatcher.Suggestion::card).collect(Collectors.toList());

        assertThat(offered).contains("Delver of Secrets");
        PaperCard card = StaticData.instance().getCommonCards().getUniqueByName("Delver of Secrets");
        assertThat(card).isNotNull();
        assertThat(card.getRules().getOtherPart().getName()).isEqualTo("Insectile Aberration");
    }

    @Test
    public void notOfferTheBackFaceForTheFrontFace() {
        assertThat(names("Delver of Secrets")).containsExactly("Delver of Secrets");
    }

    @Test
    public void keepSplitAndAdventureHalvesAsTheyAre() {
        assertThat(names("Ice")).containsExactly("Ice");
        assertThat(names("Stomp")).containsExactly("Stomp");
        assertThat(names("Bonecrusher Giant")).containsExactly("Bonecrusher Giant");
    }
}
