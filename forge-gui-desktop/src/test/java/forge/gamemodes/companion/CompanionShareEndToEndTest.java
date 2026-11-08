package forge.gamemodes.companion;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gamemodes.companion.share.CompanionClient;
import forge.gamemodes.companion.share.CompanionShareHost;
import forge.gamemodes.companion.share.JoinCode;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.interfaces.IDeviceAdapter;
import forge.interfaces.IGameController;
import forge.interfaces.ILobbyListener;
import forge.net.HeadlessNetworkGuiGame;

import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * A Companion game shared over a real localhost socket (multi-phone slice 0): a {@link CompanionClient} joins by its
 * {@link JoinCode} after the game started, follows the host's board, gets the whole board again after a host undo,
 * and nothing it sends changes the host's game. The test thread plays the host's game loop: it runs the table's
 * system ops and ops, and flushes the views, where the loop's priority prompt would.
 */
public class CompanionShareEndToEndTest extends AITest {
    private static final long WAIT_MS = 15_000;

    private static CompanionTable newTable() {
        try {
            File dir = Files.createTempDirectory("companion-share").toFile();
            assertTrue(new File(dir, "t").mkdir());
            CompanionSetup setup = CompanionSetup.withDefaults(List.of(new CompanionSetup.Seat("Ann", true),
                    new CompanionSetup.Seat("Bo", false), new CompanionSetup.Seat("Cy", false)));
            return new CompanionTable("t", setup, new CompanionStore(dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A device whose local-network binding calls land in {@code binds}. */
    private static IDeviceAdapter recordingDevice(List<Boolean> binds) {
        return (IDeviceAdapter) Proxy.newProxyInstance(IDeviceAdapter.class.getClassLoader(),
                new Class<?>[] { IDeviceAdapter.class }, (proxy, method, args) -> {
                    if ("bindToLocalNetwork".equals(method.getName())) {
                        binds.add((Boolean) args[0]);
                    }
                    return null;
                });
    }

    /** Runs {@code op} and then the save the next prompt would make, as the game loop does. */
    private static void act(CompanionTable table, Game game, CompanionTable.Op op) {
        table.submit(op);
        assertTrue(table.runPending(game));
        table.save(game);
    }

    /** What the loop does when an input changes: every view observing the seats' inputs flushes. */
    private static void flushViews(CompanionTable table) {
        table.seats().get(0).getInputQueue().updateObservers();
    }

    /** Waits, bounded, for {@code condition}; the host's loop work is done meanwhile by {@code loop}. */
    private static void await(BooleanSupplier condition, Runnable loop, String why) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting: " + why);
            }
            loop.run();
            Thread.sleep(20);
        }
    }

    /** Every player's life, and the card ids in each of their zones: what a joined board must match. */
    private static Map<String, String> board(GameView view) {
        Map<String, String> board = new TreeMap<>();
        if (view == null || view.getPlayers() == null) {
            return board;
        }
        for (PlayerView player : view.getPlayers()) {
            board.put(player.getName() + " life", String.valueOf(player.getLife()));
            for (ZoneType zone : ZoneType.values()) {
                Set<Integer> ids = new TreeSet<>();
                if (player.getCards(zone) != null) {
                    player.getCards(zone).forEach(c -> ids.add(c.getId()));
                }
                board.put(player.getName() + " " + zone, ids.toString());
            }
        }
        return board;
    }

    private static boolean onBattlefield(GameView view, String name) {
        if (view == null || view.getPlayers() == null) {
            return false;
        }
        for (PlayerView player : view.getPlayers()) {
            if (player.getCards(ZoneType.Battlefield) != null) {
                for (CardView card : player.getCards(ZoneType.Battlefield)) {
                    if (name.equals(card.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A second client, with {@code code}'s token + 1: refused, so its lobby listener closes and no view comes. */
    private static void refusesAWrongToken(CompanionTable table, CompanionShareHost host, JoinCode code)
            throws InterruptedException {
        assertEquals(host.refusedAtGate(), 0, "the right preamble went first, before the login");
        JoinCode wrong = new JoinCode(code.address(), code.port(), code.token() + 1);
        HeadlessNetworkGuiGame wrongView = new HeadlessNetworkGuiGame();
        List<Boolean> binds = new CopyOnWriteArrayList<>();
        CompanionClient intruder = new CompanionClient("Intruder", wrongView, wrong, recordingDevice(binds));
        AtomicBoolean closed = new AtomicBoolean();
        intruder.addLobbyListener(new ILobbyListener() {
            @Override
            public void message(String source, String message, ChatMessage.MessageType type) { }

            @Override
            public void update(GameLobby.GameLobbyData state, int slot) { }

            @Override
            public void close() {
                closed.set(true);
            }

            @Override
            public ClientGameLobby getLobby() {
                return null;
            }
        });
        try {
            intruder.connect();
            await(closed::get, table::runSystemOps, "the wrong token's connection closing");
            table.runSystemOps(); // an attach, had one been queued, would run here
            assertNull(intruder.welcomedTo(), "a wrong token is never welcomed");
            assertEquals(intruder.epoch(), 0);
            assertFalse(wrongView.isOpenViewCalled(), "no view for a wrong token");
            // refused before it was seated, so no reconnect follows: the join is over without a close()
            await(() -> !intruder.isBoundToLocalNetwork(), () -> { }, "the refused client unbinding");
            assertEquals(binds, List.of(true, false), "bound to connect, unbound once refused");
        } finally {
            intruder.close();
        }
    }

    @Test(timeOut = 120_000)
    public void aClientJoinedMidGameFollowsTheHostAndChangesNothing() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = newTable();
        for (Player p : game.getPlayers()) {
            new CompanionPlayerController(game, p, new forge.ai.LobbyPlayerAi(p.getName(), null), table);
        }
        table.startFromLatest(game);
        table.atPrompt(game); // the start, saved as the first step
        Player ann = game.getPhaseHandler().getPlayerTurn();
        act(table, game, new CompanionTable.AddCard(ann.getName(), "Grizzly Bears", false,
                TableEditor.Mode.PUT_ONTO_BATTLEFIELD));

        CompanionShareHost host = new CompanionShareHost(table);
        CompanionClient client = null;
        try {
            int port = host.start();
            JoinCode code = host.codeAt((Inet4Address) InetAddress.getByName("127.0.0.1"));
            assertEquals(code.port(), port);
            JoinCode typed = JoinCode.parse(code.text().toLowerCase()).orElseThrow();

            // the game has started: a client joins by the code, and is attached at the loop's next prompt
            HeadlessNetworkGuiGame view = new HeadlessNetworkGuiGame();
            List<Boolean> binds = new CopyOnWriteArrayList<>();
            client = new CompanionClient("Joiner", view, typed, recordingDevice(binds));
            client.connect();
            assertEquals(binds, List.of(true), "bound to the local network before connecting");
            CompanionClient joined = client;
            await(() -> joined.epoch() >= 1 && view.isOpenViewCalled(), table::runSystemOps, "the attach");
            assertEquals(joined.welcomedTo(), "t", "welcomed to the shared game");
            assertEquals(joined.epoch(), 1, "one full view on the attach");
            assertEquals(host.peerCount(), 1);
            await(() -> board(view.getGameView()).equals(board(game.getView())), () -> { },
                    "the joined board is the host's");
            assertTrue(onBattlefield(view.getGameView(), "Grizzly Bears"), "the card added before the join");
            for (PlayerView seat : view.getGameView().getPlayers()) {
                assertNotNull(view.getGameController(seat), "every seat is the client's: " + seat.getName());
            }

            // a client whose code has the wrong token is never welcomed, sees the connection close, and isn't counted
            refusesAWrongToken(table, host, code);
            assertEquals(host.peerCount(), 1, "the refused client was never counted");
            assertEquals(host.refusedAtGate(), 1, "refused at the gate, before anything it sent was deserialized");

            // the host adds a card: it reaches the client as a delta
            act(table, game, new CompanionTable.AddCard(ann.getName(), "Llanowar Elves", false,
                    TableEditor.Mode.PUT_ONTO_BATTLEFIELD));
            flushViews(table);
            await(() -> onBattlefield(view.getGameView(), "Llanowar Elves"), () -> { }, "the elves on the client");
            assertEquals(joined.epoch(), 1, "an ordinary action is a delta, not a full view");
            await(() -> board(view.getGameView()).equals(board(game.getView())), () -> { },
                    "the joined board is the host's after the action");

            // the host undoes, which renumbers every card: the client gets the whole board again, and the epoch
            Map<String, String> beforeUndo = board(game.getView());
            table.undo();
            assertTrue(table.runPending(game));
            flushViews(table);
            assertFalse(board(game.getView()).equals(beforeUndo), "the undo renumbered the cards");
            assertFalse(onBattlefield(game.getView(), "Llanowar Elves"), "the undo took the elves back");
            await(() -> joined.epoch() == 2, () -> { }, "the epoch of the undo's full view");
            await(() -> board(view.getGameView()).equals(board(game.getView())), () -> { },
                    "the joined board is the host's after the undo");
            assertFalse(onBattlefield(view.getGameView(), "Llanowar Elves"));

            // and the deltas after it resolve against the new ids
            act(table, game, new CompanionTable.AddCard(ann.getName(), "Llanowar Elves", false,
                    TableEditor.Mode.PUT_ONTO_BATTLEFIELD));
            flushViews(table);
            await(() -> board(view.getGameView()).equals(board(game.getView())), () -> { },
                    "the joined board is the host's after an action after the undo");
            assertTrue(onBattlefield(view.getGameView(), "Llanowar Elves"));

            // the client concedes and undoes: the host's game doesn't change (AC-108)
            Map<String, String> beforeCalls = board(game.getView());
            PlayerView clientAnn = view.getGameView().getPlayers().get(0);
            IGameController controller = view.getGameController(clientAnn);
            controller.concede();
            controller.undoLastAction();
            CardView anyCard = view.getGameView().getPlayers().get(0).getCards(ZoneType.Battlefield).iterator().next();
            // a question, answered once the host has had the calls before it; the gate answers null
            assertNull(controller.getActivateDescription(anyCard));
            Thread.sleep(300); // the host runs calls off its IO thread: give a stray one the time to land
            table.runSystemOps();
            assertFalse(table.hasPending(), "no op from the client");
            assertFalse(table.runPending(game));
            assertFalse(game.isGameOver(), "the concede was dropped");
            for (Player p : game.getPlayers()) {
                assertFalse(p.hasLost(), p.getName() + " is still in the game");
            }
            assertEquals(board(game.getView()), beforeCalls, "the host's board is as it was");
            assertEquals(joined.epoch(), 2, "nothing was resent");

            // leaving unbinds, once
            assertEquals(binds, List.of(true), "still bound while joined");
            client.close();
            client.close();
            assertEquals(binds, List.of(true, false), "unbound on leaving");
        } finally {
            if (client != null) {
                client.close();
            }
            host.stop();
        }
    }
}
