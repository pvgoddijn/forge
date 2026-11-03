package forge.gamemodes.companion;

import forge.game.zone.ZoneType;

import org.testng.annotations.Test;

import java.util.List;

import forge.gamemodes.companion.PlaceholderText.Spot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Taking the placeholders out of a game state's text, before Forge's loader reads it. */
public class PlaceholderTextShould {
    private static final String UNKNOWN = CompanionCards.PLACEHOLDER_NAME + "|Set:???";

    @Test
    public void keepThePlacesOfUnknownCardsBetweenKnownOnes() {
        PlaceholderText text = PlaceholderText.extract(List.of(
                "turn=3",
                "p1library=" + UNKNOWN + ";Forest|Set:LEA;" + UNKNOWN + ";" + UNKNOWN + ";Sol Ring|Set:LEA;" + UNKNOWN));

        assertThat(text.lines()).containsExactly("turn=3", "p1library=Forest|Set:LEA;Sol Ring|Set:LEA");
        assertThat(text.spots()).extracting(Spot::position).containsExactly(0, 2, 3, 5);
        assertThat(text.spots()).allSatisfy(spot -> {
            assertThat(spot.player()).isEqualTo(1);
            assertThat(spot.zone()).isEqualTo(ZoneType.Library);
        });
    }

    @Test
    public void readThePlayerAndZoneOfEachKey() {
        PlaceholderText text = PlaceholderText.extract(List.of(
                "humanhand=" + UNKNOWN,
                "aigraveyard=" + UNKNOWN,
                "p3exile=" + UNKNOWN,
                "p2battlefield=" + UNKNOWN,
                "p4play=" + UNKNOWN,
                "p5command=" + UNKNOWN));

        assertThat(text.spots()).extracting(Spot::player, Spot::zone).containsExactly(
                tuple(0, ZoneType.Hand),
                tuple(1, ZoneType.Graveyard),
                tuple(3, ZoneType.Exile),
                tuple(2, ZoneType.Battlefield),
                tuple(4, ZoneType.Battlefield),
                tuple(5, ZoneType.Command));
        assertThat(text.lines()).containsExactly(
                "humanhand=", "aigraveyard=", "p3exile=", "p2battlefield=", "p4play=", "p5command=");
    }

    @Test
    public void leaveOtherLinesAndZonesWithoutPlaceholdersAlone() {
        List<String> lines = List.of(
                "# a comment=with an equals sign",
                "p0life=40",
                "p0landsplayed=1",
                "p0commandercast=2|Krenko, Mob Boss",
                "p0hand=Forest|Set:LEA;Sol Ring|Set:LEA|Id:4",
                "p0graveyard=");

        PlaceholderText text = PlaceholderText.extract(lines);

        assertThat(text.lines()).isEqualTo(lines);
        assertThat(text.spots()).isEmpty();
    }

    @Test
    public void keepWhatAnUnknownCardWasSavedWith() {
        PlaceholderText text = PlaceholderText.extract(List.of(
                "p0battlefield=" + UNKNOWN + "|Tapped|FaceDown:Manifested|Counters:P1P1=2"));

        assertThat(text.spots()).singleElement().extracting(Spot::modifiers)
                .isEqualTo(List.of("Set:???", "Tapped", "FaceDown:Manifested", "Counters:P1P1=2"));
    }

    @Test
    public void takeAnUnknownCardOutOfTheReferencesToIt() {
        PlaceholderText text = PlaceholderText.extract(List.of(
                "p0exile=" + UNKNOWN + "|Id:7;" + UNKNOWN + "|Id:9|ExiledWith:3",
                "p1battlefield=Bone Collector|Set:M10|Id:3|RememberedCards:7,5|Imprinting:9;Arcane Signet|Set:CMR"
                        + "|ChosenCards:7,9|Tapped",
                "p2battlefield=Pacifism|Set:M10|AttachedTo:7;Mirror|Set:M10|ExiledWith:9|Attacking:7"));

        assertThat(text.lines()).containsExactly(
                "p0exile=",
                "p1battlefield=Bone Collector|Set:M10|Id:3|RememberedCards:5;Arcane Signet|Set:CMR|Tapped",
                "p2battlefield=Pacifism|Set:M10;Mirror|Set:M10");
    }
}
