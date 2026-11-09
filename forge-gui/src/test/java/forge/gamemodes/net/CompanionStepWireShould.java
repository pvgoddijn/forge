package forge.gamemodes.net;

import forge.gamemodes.companion.CompanionSetup;
import forge.gamemodes.companion.share.ShareMessages;
import io.netty.handler.codec.serialization.ClassResolvers;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Companion's step messages through the real wire streams, so the class filter is the one a peer meets. */
public class CompanionStepWireShould {
    private static Object roundTrip(Object message) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new CObjectOutputStream(bytes, false, null, -1, false)) {
            out.writeObject(message);
        }
        try (CObjectInputStream in = new CObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()),
                ClassResolvers.cacheDisabled(null), null)) {
            return in.readObject();
        }
    }

    @Test
    public void passTheClassFilter() throws Exception {
        CompanionSetup setup = CompanionSetup.withDefaults(List.of(new CompanionSetup.Seat("Ann", true),
                new CompanionSetup.Seat("Bo", false)));

        ShareMessages.Welcome welcome = (ShareMessages.Welcome) roundTrip(
                new ShareMessages.Welcome("g", setup, 3, 1, Map.of(0, "a", 1, "b")));
        ShareMessages.Want want = (ShareMessages.Want) roundTrip(new ShareMessages.Want(1));
        ShareMessages.Step step = (ShareMessages.Step) roundTrip(new ShareMessages.Step(1, "turn=1"));

        assertThat(welcome.stepIndex()).containsExactly(Map.entry(0, "a"), Map.entry(1, "b"));
        assertThat(want.steps()).containsExactly(1);
        assertThat(step.intact()).isTrue();
    }
}
