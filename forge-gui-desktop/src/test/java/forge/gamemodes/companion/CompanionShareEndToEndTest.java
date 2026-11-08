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
import forge.gamemodes.companion.share.ShareState;
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
import java.util.Arrays;
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
        return newTable("t");
    }

    /** A table under the id {@code id}, which may be one the store's rule refuses (a host sending a bad id). */
    private static CompanionTable newTable(String id) {
        try {
            File dir = Files.createTempDirectory("companion-share").toFile();
            CompanionSetup setup = CompanionSetup.withDefaults(List.of(new CompanionSetup.Seat("Ann", true),
                    new CompanionSetup.Seat("Bo", false), new CompanionSetup.Seat("Cy", false)));
            CompanionStore store = new CompanionStore(dir);
            // as the setup screen's create does, under a fixed id; by hand, so the id isn't checked
            assertTrue(store.gameDir(id).mkdirs());
            store.saveSetup(id, setup);
            return new CompanionTable(id, setup, store);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A phone's own Companion store, empty. */
    private static CompanionStore tempStore() {
        try {
            return new CompanionStore(Files.createTempDirectory("companion-phone").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] logBytes(CompanionStore store, String game) {
        File log = new File(store.gameDir(game), "log.txt");
        try {
            return log.exists() ? Files.readAllBytes(log.toPath()) : new byte[0];
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean sameLog(CompanionStore host, CompanionStore phone, String game) {
        return Arrays.equals(logBytes(host, game), logBytes(phone, game));
    }

    /** A table with a running three-player game, its start saved, at its first prompt. */
    private CompanionTable startedTable(Game game) {
        return startedTable(game, "t");
    }

    private CompanionTable startedTable(Game game, String id) {
        CompanionTable table = newTable(id);
        for (Player p : game.getPlayers()) {
            new CompanionPlayerController(game, p, new forge.ai.LobbyPlayerAi(p.getName(), null), table);
        }
        table.startFromLatest(game);
        table.atPrompt(game);
        return table;
    }

    private static void addCard(CompanionTable table, Game game, String card) {
        act(table, game, new CompanionTable.AddCard(game.getPhaseHandler().getPlayerTurn().getName(), card, false,
                TableEditor.Mode.PUT_ONTO_BATTLEFIELD));
    }

    private static void undo(CompanionTable table, Game game) {
        table.undo();
        assertTrue(table.runPending(game));
        flushViews(table);
    }

    /** A device whose {@code holdLocalNetwork} calls land in {@code binds}. */
    private static IDeviceAdapter recordingDevice(List<Boolean> binds) {
        return (IDeviceAdapter) Proxy.newProxyInstance(IDeviceAdapter.class.getClassLoader(),
                new Class<?>[] { IDeviceAdapter.class }, (proxy, method, args) -> {
                    if ("holdLocalNetwork".equals(method.getName())) {
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
        CompanionClient intruder = new CompanionClient("Intruder", wrongView, wrong, recordingDevice(binds),
                tempStore());
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
            await(() -> !intruder.isHoldingLocalNetwork(), () -> { }, "the refused client letting go of the local network");
            assertEquals(binds, List.of(true, false), "held to connect, let go once refused");
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
            client = new CompanionClient("Joiner", view, typed, recordingDevice(binds), tempStore());
            client.connect();
            assertEquals(binds, List.of(true), "held the local network before connecting");
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

            // leaving lets go, once
            assertEquals(binds, List.of(true), "still held while joined");
            client.close();
            client.close();
            assertEquals(binds, List.of(true, false), "let go on leaving");
        } finally {
            if (client != null) {
                client.close();
            }
            host.stop();
        }
    }

    /** AC-105, the log: a phone joining after undos gets the host's log byte for byte, each entry live, the end. */
    @Test(timeOut = 120_000)
    public void aPhoneJoiningAfterUndosGetsAnIdenticalLog() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionStore hostStore = table.store();
        addCard(table, game, "Grizzly Bears");
        addCard(table, game, "Llanowar Elves");
        undo(table, game);
        addCard(table, game, "Forest");
        addCard(table, game, "Mountain");
        assertTrue(hostStore.loadLog("t").stream().anyMatch(e -> e.kind() == CompanionLogEntry.Kind.UNDO),
                "the host's log has an undo in it");

        CompanionShareHost host = new CompanionShareHost(table);
        CompanionStore phone = tempStore();
        List<String> ended = new CopyOnWriteArrayList<>();
        CompanionClient client = null;
        try {
            host.start();
            JoinCode code = host.codeAt((Inet4Address) InetAddress.getByName("127.0.0.1"));
            HeadlessNetworkGuiGame view = new HeadlessNetworkGuiGame();
            client = new CompanionClient("Joiner", view, code, phone);
            client.addCopyListener(new CompanionClient.CopyListener() {
                @Override
                public void ended(String game) {
                    ended.add(game);
                }
            });
            client.connect();
            CompanionClient joined = client;
            await(() -> joined.epoch() >= 1 && view.isOpenViewCalled(), table::runSystemOps, "the attach");
            await(() -> sameLog(hostStore, phone, "t"), () -> { }, "the phone's log is the host's");
            assertTrue(logBytes(phone, "t").length > 0, "the phone has the history");
            assertEquals(phone.gameDir("t").getParentFile().list(), new String[] { "t" },
                    "the phone's only folder has the host's game id");
            ShareState state = ShareState.read(phone, "t").orElseThrow();
            assertEquals(state.role(), ShareState.Role.JOINED);
            assertEquals(state.code(), code, "the host's address, port and token, to join again");
            assertEquals(phone.loadSetup("t"), table.setup(), "the host's setup");

            // an entry after the join comes live
            addCard(table, game, "Island");
            flushViews(table);
            long latest = hostStore.loadLogLines("t").size();
            await(() -> joined.heldSeq() == latest, () -> { }, "the entry after the join");
            assertTrue(sameLog(hostStore, phone, "t"), "the logs are the same after a live entry");

            // the host ends the game: the phone's copy is ended too
            hostStore.end("t");
            await(() -> phone.isEnded("t"), () -> { }, "the end on the phone");
            assertEquals(ended, List.of("t"), "the listener heard of the end once it was written");
            assertTrue(sameLog(hostStore, phone, "t"), "the logs are the same at the end");
            assertFalse(phone.loadActive().isPresent(), "an ended copy isn't the phone's active game");
        } finally {
            if (client != null) {
                client.close();
            }
            host.stop();
        }
    }

    /**
     * AC-113: the host ends the game and stops sharing at once, as leaving the match does. What the journal still had
     * for the phone (the last entries and the end) is sent before the connection closes.
     */
    @Test(timeOut = 120_000)
    public void aPhoneSeesTheEndWhenTheHostStopsSharingRightAfter() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionStore hostStore = table.store();
        addCard(table, game, "Grizzly Bears");

        CompanionShareHost host = new CompanionShareHost(table);
        CompanionStore phone = tempStore();
        List<String> ended = new CopyOnWriteArrayList<>();
        CompanionClient client = null;
        try {
            host.start();
            JoinCode code = host.codeAt((Inet4Address) InetAddress.getByName("127.0.0.1"));
            HeadlessNetworkGuiGame view = new HeadlessNetworkGuiGame();
            client = new CompanionClient("Joiner", view, code, phone);
            client.addCopyListener(new CompanionClient.CopyListener() {
                @Override
                public void ended(String game) {
                    ended.add(game);
                }
            });
            client.connect();
            CompanionClient joined = client;
            await(() -> joined.epoch() >= 1 && view.isOpenViewCalled(), table::runSystemOps, "the attach");
            await(() -> sameLog(hostStore, phone, "t"), () -> { }, "the catch-up");

            // entries the phone hasn't had yet, the end, and the share stopped, without waiting in between
            addCard(table, game, "Forest");
            addCard(table, game, "Island");
            addCard(table, game, "Mountain");
            hostStore.end("t");
            host.stop();

            await(() -> phone.isEnded("t"), () -> { }, "the end on the phone, after the share stopped");
            assertEquals(ended, List.of("t"));
            assertTrue(sameLog(hostStore, phone, "t"), "the last entries came before the end");
        } finally {
            if (client != null) {
                client.close();
            }
            host.stop();
        }
    }

    /** A phone joining again with part of the log gets only the entries it lacks (AC-105). */
    @Test(timeOut = 120_000)
    public void aPhoneJoiningAgainGetsOnlyTheMissingEntries() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionStore hostStore = table.store();
        addCard(table, game, "Grizzly Bears");
        undo(table, game);
        addCard(table, game, "Forest");

        CompanionShareHost host = new CompanionShareHost(table);
        CompanionStore phone = tempStore();
        CompanionClient first = null;
        CompanionClient again = null;
        try {
            host.start();
            JoinCode code = host.codeAt((Inet4Address) InetAddress.getByName("127.0.0.1"));
            HeadlessNetworkGuiGame view = new HeadlessNetworkGuiGame();
            first = new CompanionClient("Joiner", view, code, phone);
            first.connect();
            CompanionClient joined = first;
            await(() -> joined.epoch() >= 1, table::runSystemOps, "the first attach");
            await(() -> sameLog(hostStore, phone, "t"), () -> { }, "the first copy");
            long had = joined.heldSeq();
            assertTrue(had > 0);
            first.close();
            await(() -> host.peerCount() == 0, table::runSystemOps, "the host seeing the phone go");

            // the host goes on without the phone
            addCard(table, game, "Island");
            addCard(table, game, "Mountain");
            long latest = hostStore.loadLogLines("t").size();
            assertTrue(latest > had + 1);

            // "Join again": the hello says what the copy holds, and only the rest comes
            List<long[]> sent = new CopyOnWriteArrayList<>();
            HeadlessNetworkGuiGame view2 = new HeadlessNetworkGuiGame();
            again = new CompanionClient("Joiner", view2, code, phone);
            again.addCopyListener(new CompanionClient.CopyListener() {
                @Override
                public void written(String game, long fromSeq, long toSeq) {
                    sent.add(new long[] { fromSeq, toSeq });
                }
            });
            again.followAgain("t");
            assertEquals(again.heldSeq(), had, "the copy's last seq, read from the store");
            again.connect();
            CompanionClient rejoined = again;
            await(() -> rejoined.epoch() >= 1, table::runSystemOps, "the second attach");
            await(() -> rejoined.heldSeq() == latest, () -> { }, "the missing entries");
            assertEquals(sent.size(), 1, "one catch-up message");
            assertEquals(sent.get(0)[0], had + 1, "starting after what the copy held");
            assertEquals(sent.get(0)[1], latest);
            assertTrue(sameLog(hostStore, phone, "t"), "the logs are the same after joining again");
        } finally {
            if (first != null) {
                first.close();
            }
            if (again != null) {
                again.close();
            }
            host.stop();
        }
    }

    /**
     * The host's hello check (M5): what a phone holds counts only for the host's game. A phone whose copy follows
     * another game, with entries, gets the host's whole log from seq 1.
     */
    @Test(timeOut = 120_000)
    public void aPhoneHoldingAnotherGameGetsTheWholeLog() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionStore hostStore = table.store();
        addCard(table, game, "Grizzly Bears");
        addCard(table, game, "Forest");
        addCard(table, game, "Island");
        long latest = hostStore.loadLogLines("t").size();

        CompanionStore phone = tempStore();
        phone.createWithId("other", table.setup());
        for (int i = 0; i < latest - 1; i++) {
            phone.appendLog("other", CompanionLogEntry.of(CompanionLogEntry.Kind.NOTICE, 0, 1, null, null, "x" + i));
        }
        CompanionShareHost host = new CompanionShareHost(table);
        CompanionClient client = null;
        try {
            host.start();
            JoinCode code = host.codeAt((Inet4Address) InetAddress.getByName("127.0.0.1"));
            client = new CompanionClient("Joiner", new HeadlessNetworkGuiGame(), code, phone);
            List<long[]> sent = new CopyOnWriteArrayList<>();
            client.addCopyListener(new CompanionClient.CopyListener() {
                @Override
                public void written(String game, long fromSeq, long toSeq) {
                    sent.add(new long[] { fromSeq, toSeq });
                }
            });
            client.followAgain("other");
            assertEquals(client.heldSeq(), latest - 1, "the hello says the phone holds entries");
            client.connect();
            CompanionClient joined = client;
            await(() -> joined.heldSeq() == latest, table::runSystemOps, "the host's log");
            assertEquals(sent.get(0)[0], 1L, "the host's log from its first entry");
            assertTrue(sameLog(hostStore, phone, "t"), "the phone's copy is the host's whole log");
        } finally {
            if (client != null) {
                client.close();
            }
            host.stop();
        }
    }

    /**
     * A game id from the network is checked before it's used: a host sending one that isn't a plain folder name gets
     * nothing written on the phone, nothing remembered as followed, and the copy listeners hear it failed.
     */
    @Test(timeOut = 120_000)
    public void aPhoneRefusesAGameIdThatIsntOne() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game, "has space");
        addCard(table, game, "Grizzly Bears");

        CompanionShareHost host = new CompanionShareHost(table);
        CompanionStore phone = tempStore();
        List<RuntimeException> failed = new CopyOnWriteArrayList<>();
        CompanionClient client = null;
        try {
            host.start();
            JoinCode code = host.codeAt((Inet4Address) InetAddress.getByName("127.0.0.1"));
            client = new CompanionClient("Joiner", new HeadlessNetworkGuiGame(), code, phone);
            client.addCopyListener(new CompanionClient.CopyListener() {
                @Override
                public void failed(String game, RuntimeException e) {
                    failed.add(e);
                }
            });
            client.connect();
            await(() -> !failed.isEmpty(), table::runSystemOps, "the copy failing");
            assertTrue(failed.get(0) instanceof IllegalArgumentException, String.valueOf(failed.get(0)));
            assertNull(client.welcomedTo(), "a bad id isn't the game followed");
            assertEquals(phone.gameDir("x").getParentFile().list(), new String[0], "nothing written on the phone");
        } finally {
            if (client != null) {
                client.close();
            }
            host.stop();
        }
    }
}
