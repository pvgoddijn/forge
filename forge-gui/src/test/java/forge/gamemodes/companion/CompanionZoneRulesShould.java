package forge.gamemodes.companion;

import forge.card.CardType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

import static forge.gamemodes.companion.CompanionZone.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The hand and zone rules, which come only from replaying the log. */
public class CompanionZoneRulesShould {
    private File dir;
    private CompanionStore store;
    private CompanionGame game;

    @BeforeMethod
    public void startGame() throws IOException {
        dir = Files.createTempDirectory("companion-zones").toFile();
        store = new CompanionStore(dir);
        game = store.createGame(Arrays.asList(
                new CompanionAction.PlayerSetup("Ann", true),
                new CompanionAction.PlayerSetup("Bo", false)));
    }

    @AfterMethod
    public void deleteDir() throws IOException {
        try (Stream<Path> paths = Files.walk(dir.toPath())) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    private CompanionState.PlayerState ann() {
        return game.state().players().get(0);
    }

    private CompanionState.PlayerState bo() {
        return game.state().players().get(1);
    }

    private int lastCardId() {
        return game.log().size() - 1;
    }

    @Test
    public void startEveryPlayerWithSevenHiddenCards() {
        assertThat(ann().hiddenHand()).isEqualTo(7);
        assertThat(bo().hiddenHand()).isEqualTo(7);
    }

    @Test
    public void defaultPermanentsToBattlefieldAndOthersToGraveyard() {
        assertThat(addDefaults(CardType.parse("Artifact", false))).isEqualTo(new AddDefaults(HAND, BATTLEFIELD));
        assertThat(addDefaults(CardType.parse("Legendary Creature Elf", false))).isEqualTo(new AddDefaults(HAND, BATTLEFIELD));
        assertThat(addDefaults(CardType.parse("Instant", false))).isEqualTo(new AddDefaults(HAND, GRAVEYARD));
        assertThat(addDefaults(CardType.parse("Sorcery", false))).isEqualTo(new AddDefaults(HAND, GRAVEYARD));
    }

    @Test
    public void notTakeATokenOrEmblemFromTheHand() {
        assertThat(addDefaults((CardType) null)).isEqualTo(new AddDefaults(null, BATTLEFIELD));

        game.addCard(0, "Soldier Token", null, BATTLEFIELD);

        assertThat(ann().hiddenHand()).isEqualTo(7);
        assertThat(ann().battlefield()).hasSize(1);
    }

    @Test
    public void reduceTheHiddenCountWhenAnUnknownCardLeavesHand() {
        game.addCard(0, "Sol Ring", HAND, BATTLEFIELD);

        assertThat(ann().hiddenHand()).isEqualTo(6);
        assertThat(ann().battlefield()).extracting(CompanionState.GameCard::name).containsExactly("Sol Ring");
    }

    @Test
    public void neverReduceTheHiddenCountBelowZero() {
        for (int i = 0; i < 9; i++) {
            game.changeHandCount(0, -1);
        }
        assertThat(ann().hiddenHand()).isZero();

        game.addCard(0, "Sol Ring", HAND, BATTLEFIELD);

        assertThat(ann().hiddenHand()).isZero();
        assertThat(ann().battlefield()).hasSize(1);
    }

    @Test
    public void recordAMoveToHandAsAKnownCardAndLeaveTheCountAlone() {
        game.addCard(0, "Sol Ring", HAND, BATTLEFIELD);
        int ring = lastCardId();

        game.moveCard(ring, 0, HAND);

        assertThat(ann().hiddenHand()).isEqualTo(6);
        assertThat(ann().knownHand()).extracting(CompanionState.GameCard::instanceId).containsExactly(ring);
        assertThat(ann().battlefield()).isEmpty();
    }

    @Test
    public void leaveTheCountAloneWhenAKnownCardIsMovedOutOfHand() {
        game.addCard(0, "Sol Ring", HAND, BATTLEFIELD);
        int ring = lastCardId();
        game.moveCard(ring, 0, HAND);

        game.moveCard(ring, 0, EXILE);

        assertThat(ann().hiddenHand()).isEqualTo(6);
        assertThat(ann().knownHand()).isEmpty();
        assertThat(ann().zone(EXILE)).extracting(CompanionState.GameCard::instanceId).containsExactly(ring);
    }

    @Test
    public void useTheKnownCardWhenTheSameNameIsAddedFromHand() {
        game.addCard(0, "Sol Ring", HAND, BATTLEFIELD);
        int ring = lastCardId();
        game.moveCard(ring, 0, HAND);

        game.addCard(0, "Sol Ring", HAND, BATTLEFIELD);

        assertThat(ann().hiddenHand()).isEqualTo(6);
        assertThat(ann().knownHand()).isEmpty();
        assertThat(ann().battlefield()).extracting(CompanionState.GameCard::instanceId).containsExactly(ring);
    }

    @Test
    public void moveACardBetweenPlayers() {
        game.addCard(0, "Sol Ring", HAND, BATTLEFIELD);
        int ring = lastCardId();

        game.moveCard(ring, 1, BATTLEFIELD);

        assertThat(ann().battlefield()).isEmpty();
        assertThat(bo().battlefield()).extracting(CompanionState.GameCard::instanceId).containsExactly(ring);
        assertThat(bo().hiddenHand()).isEqualTo(7);
    }

    @Test
    public void makeAKnownCardInTheTargetPlayersHandOnAMoveBetweenPlayers() {
        game.addCard(0, "Sol Ring", HAND, BATTLEFIELD);

        game.moveCard(lastCardId(), 1, HAND);

        assertThat(bo().knownHand()).hasSize(1);
        assertThat(bo().hiddenHand()).isEqualTo(7);
    }

    @Test
    public void refuseToMoveACardThatIsNotTracked() {
        assertThatThrownBy(() -> game.moveCard(99, 0, EXILE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(game.log()).hasSize(1);
    }

    @Test
    public void comeBackTheSameAfterAReload() {
        game.addCard(0, "Sol Ring", HAND, BATTLEFIELD);
        int ring = lastCardId();
        game.addCard(0, "Lightning Bolt", HAND, GRAVEYARD);
        game.addCard(1, "Soldier Token", null, BATTLEFIELD);
        game.moveCard(ring, 1, HAND);
        game.changeHandCount(1, 1);

        CompanionGame reloaded = new CompanionStore(dir).loadActive().orElseThrow();

        assertThat(reloaded.log()).isEqualTo(game.log());
        assertThat(reloaded.state()).isEqualTo(game.state());
    }
}
