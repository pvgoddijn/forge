package forge.gamemodes.companion.share;

import forge.gamemodes.companion.CompanionStore;
import io.netty.channel.embedded.EmbeddedChannel;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A joined phone's copy of the host's steps, without a network: the client's data handler on an embedded channel (the
 * copy's thread is the client's own), and a real store in a temporary folder.
 */
public class CompanionClientShould {
    private static final String GAME = "g";

    private CompanionStore store;

    @BeforeMethod
    public void createStore() throws IOException {
        File dir = Files.createTempDirectory("companion-client").toFile();
        dir.deleteOnExit();
        store = new CompanionStore(dir);
        store.createDir(GAME);
    }

    private static String stepText(int n) {
        return "turn=" + n + "\nlife=20\n";
    }

    /** {@code step} as it would arrive with {@code from} changed to {@code to} in its text on the way. */
    private static ShareMessages.Step damaged(ShareMessages.Step step, String from, String to) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(step);
        }
        byte[] wire = bytes.toByteArray();
        byte[] find = from.getBytes(StandardCharsets.UTF_8);
        byte[] put = to.getBytes(StandardCharsets.UTF_8);
        assertThat(put).hasSameSizeAs(find);
        int at = indexOf(wire, find);
        assertThat(at).as("the text in the serialized step").isNotNegative();
        System.arraycopy(put, 0, wire, at, put.length);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(wire))) {
            return (ShareMessages.Step) in.readObject();
        }
    }

    private static int indexOf(byte[] in, byte[] find) {
        for (int i = 0; i + find.length <= in.length; i++) {
            boolean match = true;
            for (int j = 0; j < find.length && match; j++) {
                match = in[i + j] == find[j];
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static JoinCode code() throws IOException {
        return new JoinCode((Inet4Address) InetAddress.getByName("127.0.0.1"), 36744, 1234);
    }

    @Test
    public void tellAStepDamagedOnTheWay() throws Exception {
        ShareMessages.Step step = damaged(new ShareMessages.Step(1, stepText(1)), "turn=1", "turn=9");

        assertThat(step.text()).isEqualTo(stepText(9));
        assertThat(step.intact()).isFalse();
    }

    // Fails if the client writes a step without checking its hash (CompanionClient.writeStep)
    @Test(timeOut = 30_000)
    public void notWriteAStepDamagedOnTheWay() throws Exception {
        List<RuntimeException> failed = new CopyOnWriteArrayList<>();
        List<Integer> written = new CopyOnWriteArrayList<>();
        CountDownLatch heard = new CountDownLatch(2);
        CompanionClient client = new CompanionClient("Joiner", null, code(), store);
        client.addCopyListener(new CompanionClient.CopyListener() {
            @Override
            public void stepWritten(String game, int n) {
                written.add(n);
                heard.countDown();
            }

            @Override
            public void failed(String game, RuntimeException e) {
                failed.add(e);
                heard.countDown();
            }
        });
        client.followAgain(GAME);
        EmbeddedChannel channel = new EmbeddedChannel(client.newExtraInboundHandler());
        try {
            channel.writeInbound(damaged(new ShareMessages.Step(1, stepText(1)), "turn=1", "turn=9"));
            channel.writeInbound(new ShareMessages.Step(2, stepText(2))); // an intact one after it is written

            assertThat(heard.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0)).isInstanceOf(IllegalStateException.class).hasMessageContaining("damaged");
            assertThat(written).containsExactly(2);
            assertThat(store.stepText(GAME, 1)).isEmpty();
            assertThat(store.stepText(GAME, 2)).contains(stepText(2));
        } finally {
            channel.finishAndReleaseAll();
            client.close();
        }
    }

    // Fails without the welcome's discardAfter: a phone that left holding steps 0-4, the host undoing to 3 meanwhile
    // with no action after, would keep a step 4 the host no longer has
    @Test
    public void dropTheStepsPastTheHostsLatestBeforeSayingWhatItWants() {
        Map<Integer, String> hostIndex = new TreeMap<>();
        for (int n = 0; n <= 4; n++) {
            store.writeStep(GAME, n, stepText(n));
            if (n <= 3) {
                hostIndex.put(n, CompanionStore.sha256(stepText(n)));
            }
        }

        int[] wanted = CompanionClient.wantedSteps(store, GAME, 3, hostIndex);

        assertThat(wanted).isEmpty();
        assertThat(store.latestStep(GAME)).isEqualTo(3);
        assertThat(store.stepHashes(GAME)).isEqualTo(hostIndex);
    }

    @Test
    public void wantTheStepsItLacksOrHoldsWithAnotherHash() {
        Map<Integer, String> hostIndex = new TreeMap<>();
        for (int n = 0; n <= 3; n++) {
            hostIndex.put(n, CompanionStore.sha256(stepText(n)));
        }
        store.writeStep(GAME, 0, stepText(0));
        store.writeStep(GAME, 1, stepText(1) + "tampered\n");

        assertThat(CompanionClient.wantedSteps(store, GAME, 3, hostIndex)).containsExactly(1, 2, 3);
    }

    @Test
    public void neverGiveUpOnTheHostWaitingHalfASecondDoublingToFive() {
        assertThat(CompanionClient.backoffMillis(0)).isEqualTo(500);
        assertThat(CompanionClient.backoffMillis(1)).isEqualTo(1_000);
        assertThat(CompanionClient.backoffMillis(2)).isEqualTo(2_000);
        assertThat(CompanionClient.backoffMillis(3)).isEqualTo(4_000);
        assertThat(CompanionClient.backoffMillis(4)).isEqualTo(5_000);
        for (int attempt = 5; attempt < 10_000; attempt++) {
            assertThat(CompanionClient.backoffMillis(attempt)).isEqualTo(5_000);
        }
    }
}
