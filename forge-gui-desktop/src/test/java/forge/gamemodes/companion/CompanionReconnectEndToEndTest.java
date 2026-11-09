package forge.gamemodes.companion;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.event.GameEvent;
import forge.game.event.GameEventFlipCoin;
import forge.game.event.GameEventPlayerPriority;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.share.CompanionClient;
import forge.gamemodes.companion.share.CompanionClient.Link;
import forge.gamemodes.companion.share.CompanionShareHost;
import forge.gamemodes.companion.share.JoinCode;
import forge.gamemodes.companion.share.ShareState;
import forge.gamemodes.net.client.FGameClient;
import forge.net.HeadlessNetworkGuiGame;
import forge.trackable.TrackableCollection;

import org.testng.annotations.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static forge.gamemodes.companion.CompanionShareEndToEndTest.board;
import static forge.gamemodes.companion.CompanionShareEndToEndTest.cardIds;
import static forge.gamemodes.companion.CompanionShareEndToEndTest.flushViews;
import static forge.gamemodes.companion.CompanionShareEndToEndTest.onBattlefield;
import static forge.gamemodes.companion.CompanionShareEndToEndTest.sameCopy;
import static forge.gamemodes.companion.CompanionShareEndToEndTest.sameLog;
import static forge.gamemodes.companion.CompanionShareEndToEndTest.startedTable;
import static forge.gamemodes.companion.CompanionShareEndToEndTest.tempStore;
import static forge.gamemodes.companion.CompanionShareEndToEndTest.trackerHolds;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * Multi-phone slice 04 over real localhost sockets: a joined phone that loses the host keeps trying, without end, and
 * catches up by itself when the host is back on the same code (AC-111); a restarted phone rejoins from its copy
 * (AC-112); a host that restarts its game gives every phone the new board; a phone coming back to an ended game gets
 * the end. The test thread plays the host's game loop, as in {@link CompanionShareEndToEndTest}.
 */
public class CompanionReconnectEndToEndTest extends AITest {
    /** Long enough for Companion's backoff to pass net play's 5 attempts (0.5 + 1 + 2 + 4 + 5 s) with room to spare. */
    private static final long LONG_WAIT_MS = 40_000;

    /** A joined view that counts its {@code openView}s (one per attach) and remembers the reconnect attempts. */
    private static final class CountingView extends HeadlessNetworkGuiGame {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger lastAttempt = new AtomicInteger(-1);
        final List<FGameClient.ReconnectState> states = new CopyOnWriteArrayList<>();
        /** The kinds of game event that reached this view. */
        final Set<String> events = ConcurrentHashMap.newKeySet();

        @Override
        public void handleGameEvents(List<GameEvent> batch) {
            for (GameEvent event : batch) {
                events.add(event.getClass().getSimpleName());
            }
            super.handleGameEvents(batch);
        }

        @Override
        public void openView(TrackableCollection<PlayerView> myPlayers) {
            super.openView(myPlayers);
            opened.incrementAndGet();
        }

        @Override
        public void onReconnectStateChanged(FGameClient.ReconnectState state, int attemptIndex, int nextDelaySeconds) {
            states.add(state);
            if (state == FGameClient.ReconnectState.RECONNECTING) {
                lastAttempt.accumulateAndGet(attemptIndex, Math::max);
            }
        }
    }

    /** Adds {@code card} on the host and saves, as {@link CompanionShareEndToEndTest#addCard} (AITest has its own). */
    private static void share(CompanionTable table, Game game, String card) {
        CompanionShareEndToEndTest.addCard(table, game, card);
    }

    private static Inet4Address loopback() throws UnknownHostException {
        return (Inet4Address) InetAddress.getByName("127.0.0.1");
    }

    private static void await(BooleanSupplier condition, Runnable loop, long millis, String why)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting: " + why);
            }
            loop.run();
            Thread.sleep(20);
        }
    }

    private static void await(BooleanSupplier condition, Runnable loop, String why) throws InterruptedException {
        await(condition, loop, CompanionShareEndToEndTest.WAIT_MS, why);
    }

    /** A client that joins {@code code} by typing it, attached once the loop ran the attach. */
    private static CompanionClient joined(CompanionTable table, JoinCode code, CountingView view, CompanionStore phone)
            throws InterruptedException {
        CompanionClient client = new CompanionClient("Joiner", view, code, phone);
        client.connect();
        await(() -> client.epoch() >= 1 && view.opened.get() == 1, table::runSystemOps, "the attach");
        return client;
    }

    private static void close(CompanionClient client) {
        if (client != null) {
            client.close();
        }
    }

    private static void stop(CompanionShareHost host) {
        if (host != null) {
            host.stop();
        }
    }

    /** AC-111: the host goes and comes back on the same code; the phone reconnects by itself and catches up. */
    @Test(timeOut = 120_000)
    public void aPhoneCatchesUpByItselfWhenTheHostComesBackOnTheSameCode() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionStore hostStore = table.store();
        share(table, game, "Grizzly Bears");
        CompanionStore phone = tempStore();
        CountingView view = new CountingView();
        List<Link> links = new CopyOnWriteArrayList<>();
        CompanionShareHost host = new CompanionShareHost(table);
        CompanionShareHost restarted = null;
        CompanionClient client = null;
        try {
            int port = host.start();
            JoinCode code = host.codeAt(loopback());
            ShareState shared = ShareState.read(hostStore, "t").orElseThrow();
            assertEquals(shared.role(), ShareState.Role.HOSTING, "the host wrote its share state");
            assertEquals(shared.port(), port);
            assertEquals(shared.token(), code.token());
            assertFalse(ShareState.isFollowed(hostStore, "t"), "a hosted game stays the host's own");

            client = joined(table, code, view, phone);
            client.addLinkListener(links::add);
            CompanionClient c = client;
            await(() -> sameCopy(hostStore, phone, "t"), () -> { }, "the first copy");
            assertEquals(client.link(), Link.CONNECTED);

            // the host goes (killed, or its Wi-Fi drops): the phone keeps its board and tries again
            host.stop();
            await(() -> c.link() == Link.RECONNECTING, () -> { }, "the phone reconnecting");
            assertEquals(client.getReconnectState(), FGameClient.ReconnectState.RECONNECTING);
            assertEquals(host.peerCount(), 0, "the phone was let go of at once");
            long held = client.heldSeq();
            List<long[]> written = new CopyOnWriteArrayList<>();
            client.addCopyListener(new CompanionClient.CopyListener() {
                @Override
                public void written(String g, long fromSeq, long toSeq) {
                    written.add(new long[] { fromSeq, toSeq });
                }
            });

            // the host plays on meanwhile, unshared
            share(table, game, "Llanowar Elves");
            share(table, game, "Forest");

            // restarted: a new share of the game, from its share.json, on the same port and token
            restarted = new CompanionShareHost(table);
            assertEquals(restarted.start(), port, "the same port");
            assertFalse(restarted.codeChanged());
            assertEquals(restarted.codeAt(loopback()), code, "the same code");

            // the phone comes back with no action: the welcome answers its reconnect, though the attach waits for the
            // host's next prompt (the loop isn't run here), so it doesn't try again meanwhile
            await(() -> c.link() == Link.CONNECTED, () -> { }, "the welcome on the reconnect");
            assertEquals(client.getReconnectState(), FGameClient.ReconnectState.CONNECTED,
                    "the welcome counts as the reconnect's answer");
            // then a fresh attach
            await(() -> view.opened.get() == 2 && c.link() == Link.CONNECTED, table::runSystemOps,
                    "the fresh attach after the reconnect");
            assertEquals(client.getReconnectState(), FGameClient.ReconnectState.CONNECTED);
            await(() -> sameCopy(hostStore, phone, "t"), () -> { }, "the copy caught up: log and steps");
            await(() -> board(view.getGameView()).equals(board(game.getView())), () -> { },
                    "the board caught up");
            assertTrue(onBattlefield(view.getGameView(), "Llanowar Elves"), "what the host did meanwhile");
            assertEquals(restarted.peerCount(), 1);
            assertEquals(links, List.of(Link.RECONNECTING, Link.CONNECTED));
            assertFalse(written.isEmpty());
            assertEquals(written.get(0)[0], held + 1, "the hello said what the copy held: only the rest came");

            // and it follows live again
            share(table, game, "Island");
            flushViews(table);
            await(() -> onBattlefield(view.getGameView(), "Island"), () -> { }, "a live action after the reconnect");
            await(() -> sameCopy(hostStore, phone, "t"), () -> { }, "the copies the same after it");
        } finally {
            close(client);
            stop(host);
            stop(restarted);
        }
    }

    /** A host restarted while its port is taken shares on the next free one, and says the code changed. */
    @Test(timeOut = 120_000)
    public void aHostRestartedWhileItsPortIsTakenSharesOnANewCode() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionShareHost host = new CompanionShareHost(table);
        CompanionShareHost restarted = null;
        try {
            int port = host.start();
            JoinCode code = host.codeAt(loopback());
            assertFalse(host.codeChanged(), "a first share has no earlier code");
            host.stop();
            try (ServerSocket taken = new ServerSocket()) {
                taken.bind(new InetSocketAddress(port));
                restarted = new CompanionShareHost(table);
                int now = restarted.start();
                assertNotEquals(now, port, "the earlier port is taken");
                assertTrue(restarted.codeChanged(), "the code changed, so the host can say so");
                JoinCode newCode = restarted.codeAt(loopback());
                assertEquals(newCode.token(), code.token(), "the token lasts for the game");
                ShareState state = ShareState.read(table.store(), "t").orElseThrow();
                assertEquals(state.port(), now, "the new port kept for the next restart");
                assertEquals(state.token(), code.token());
            }
        } finally {
            stop(host);
            stop(restarted);
        }
    }

    /**
     * AC-111/112: a phone restarted while the host is down rejoins from its copy and keeps trying past net play's last
     * attempt; when the host is back it catches up, with no code typed.
     */
    @Test(timeOut = 120_000)
    public void aRestartedPhoneNeverGivesUpAndCatchesUpWhenTheHostIsBack() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionStore hostStore = table.store();
        share(table, game, "Grizzly Bears");
        CompanionStore phone = tempStore();
        CompanionShareHost host = new CompanionShareHost(table);
        CompanionShareHost restarted = null;
        CompanionClient first = null;
        CompanionClient again = null;
        try {
            host.start();
            JoinCode code = host.codeAt(loopback());
            first = joined(table, code, new CountingView(), phone);
            await(() -> sameCopy(hostStore, phone, "t"), () -> { }, "the first copy");
            // the phone is killed, then the host: both gone
            first.close();
            host.stop();
            share(table, game, "Llanowar Elves");

            // the phone reopens the game it follows: no host to reach, so it keeps trying
            CountingView view = new CountingView();
            again = new CompanionClient("Joiner", view, ShareState.read(phone, "t").orElseThrow().code(), phone);
            again.rejoin("t");
            CompanionClient c = again;
            assertEquals(again.link(), Link.RECONNECTING, "the host can't be reached: reconnecting");
            await(() -> view.lastAttempt.get() >= FGameClient.getTotalReconnectAttempts(), () -> { }, LONG_WAIT_MS,
                    "more attempts than net play makes before it gives up");
            assertEquals(again.getReconnectState(), FGameClient.ReconnectState.RECONNECTING, "never gives up");
            assertFalse(view.states.contains(FGameClient.ReconnectState.FAILED));

            restarted = new CompanionShareHost(table);
            restarted.start();
            await(() -> view.opened.get() == 1 && c.link() == Link.CONNECTED, table::runSystemOps, LONG_WAIT_MS,
                    "the rejoin once the host is back");
            await(() -> sameCopy(hostStore, phone, "t"), () -> { }, "the copy caught up");
            await(() -> board(view.getGameView()).equals(board(game.getView())), () -> { }, "the board");
            assertTrue(onBattlefield(view.getGameView(), "Llanowar Elves"));
        } finally {
            close(first);
            close(again);
            stop(host);
            stop(restarted);
        }
    }

    /**
     * The same code after a restart, when the earlier port wasn't the first: the share was on the next port, as the
     * first was taken then; restarted once it's free, it goes back to the earlier port, not the first, so the code
     * stays and the phone comes back by itself.
     */
    @Test(timeOut = 120_000)
    public void aHostRestartedSharesOnItsEarlierPortThoughTheFirstIsFreeAgain() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionStore hostStore = table.store();
        share(table, game, "Grizzly Bears");
        CompanionShareHost host = new CompanionShareHost(table);
        CompanionShareHost restarted = null;
        CompanionClient client = null;
        try {
            int port;
            try (ServerSocket taken = new ServerSocket()) {
                taken.bind(new InetSocketAddress(CompanionShareHost.FIRST_PORT));
                port = host.start();
            }
            assertNotEquals(port, CompanionShareHost.FIRST_PORT, "the first port was taken");
            JoinCode code = host.codeAt(loopback());
            CompanionStore phone = tempStore();
            CountingView view = new CountingView();
            client = joined(table, code, view, phone);
            CompanionClient c = client;
            await(() -> sameCopy(hostStore, phone, "t"), () -> { }, "the first copy");

            host.stop(); // the first port is free again now
            await(() -> c.link() == Link.RECONNECTING, () -> { }, "the phone reconnecting");
            restarted = new CompanionShareHost(table);
            assertEquals(restarted.start(), port, "the earlier port, before the first");
            assertFalse(restarted.codeChanged(), "the same code");
            assertEquals(restarted.codeAt(loopback()), code);

            await(() -> view.opened.get() == 2 && c.link() == Link.CONNECTED, table::runSystemOps,
                    "the phone back by itself on the same code");
            assertEquals(restarted.peerCount(), 1);
        } finally {
            close(client);
            stop(host);
            stop(restarted);
        }
    }

    /**
     * "Stop sharing" is for good: the game's share state goes, so a later share of the game (Share game… again, or
     * Continue) is a new one, with a new token, and the old code no longer gets in.
     */
    @Test(timeOut = 120_000)
    public void aShareStoppedForGoodIsForgotten() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionShareHost host = new CompanionShareHost(table);
        CompanionShareHost again = null;
        try {
            int port = host.start();
            JoinCode code = host.codeAt(loopback());
            assertTrue(ShareState.hosted(table.store(), "t").isPresent());

            host.stopForGood();
            assertFalse(host.isStarted());
            // the stop waited for the listener to close: its port is free the moment it returns, for a restart
            try (ServerSocket free = new ServerSocket()) {
                free.bind(new InetSocketAddress(port));
            }
            assertFalse(ShareState.hosted(table.store(), "t").isPresent(), "the share state is gone");
            assertFalse(ShareState.isFollowed(table.store(), "t"), "still the host's own game");

            again = new CompanionShareHost(table);
            again.start();
            assertNotEquals(again.codeAt(loopback()).token(), code.token(), "a new token: the old code is no good");
            assertFalse(again.codeChanged(), "no earlier share to compare with");
        } finally {
            stop(host);
            stop(again);
        }
    }

    /**
     * A reconnect attempt the host never welcomes is closed before the next one opens, so the host never holds two
     * connections of one phone: a host that accepts and never answers, several attempts.
     */
    @Test(timeOut = 120_000)
    public void aStaleReconnectAttemptIsClosedBeforeTheNextOpens() throws Exception {
        CompanionStore phone = tempStore();
        phone.createDir("t");
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort(); // free for now: nothing listens there when the phone first tries
        }
        JoinCode code = new JoinCode(loopback(), port, 12345);
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        Set<Socket> open = ConcurrentHashMap.newKeySet();
        CompanionClient client = new CompanionClient("Joiner", new CountingView(), code, phone);
        ServerSocket mute = null;
        try {
            client.rejoin("t");
            assertEquals(client.link(), Link.RECONNECTING, "nothing listens: reconnecting");
            mute = new ServerSocket();
            mute.setReuseAddress(true);
            mute.bind(new InetSocketAddress(port));
            ServerSocket server = mute;
            Thread acceptor = new Thread(() -> acceptAndNeverWelcome(server, accepted, open), "Mute-Host");
            acceptor.setDaemon(true);
            acceptor.start();

            // each attempt waits 5 s for a welcome, then the next comes; the phone counts the host silent only
            // after 15 s, so without the close the attempts would pile up
            for (int n = 2; n <= 3; n++) {
                int count = n;
                await(() -> accepted.size() >= count, () -> { }, LONG_WAIT_MS, "attempt " + count);
                Socket earlier = accepted.get(count - 2);
                await(() -> !open.contains(earlier), () -> { }, 2_000,
                        "the attempt before " + count + " closed by the phone");
                assertTrue(open.size() <= 1, "at most one connection of the phone open: " + open.size());
            }
            assertEquals(client.link(), Link.RECONNECTING, "still trying");
        } finally {
            client.close();
            if (mute != null) {
                mute.close();
            }
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    /** Accepts each connection and reads what it sends, never answering, until the phone closes it. */
    private static void acceptAndNeverWelcome(ServerSocket server, List<Socket> accepted, Set<Socket> open) {
        while (!server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (java.io.IOException e) {
                return; // the test closed it
            }
            open.add(socket);
            accepted.add(socket);
            Thread reader = new Thread(() -> {
                byte[] buffer = new byte[4096];
                try {
                    while (socket.getInputStream().read(buffer) >= 0) {
                        // what the phone says goes unanswered
                    }
                } catch (SocketException e) {
                    // closed by the test
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                } finally {
                    open.remove(socket);
                }
            }, "Mute-Host-Reader");
            reader.setDaemon(true);
            reader.start();
        }
    }

    /** A new game on the host (restart from the match screen) gives the phone a fresh attach to the new board. */
    @Test(timeOut = 120_000)
    public void aPhoneFollowsTheHostsNewGame() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        share(table, game, "Grizzly Bears");
        share(table, game, "Llanowar Elves");
        CompanionShareHost host = new CompanionShareHost(table);
        CompanionClient client = null;
        try {
            host.start();
            CountingView view = new CountingView();
            client = joined(table, host.codeAt(loopback()), view, tempStore());
            CompanionClient c = client;
            assertEquals(client.epoch(), 1);
            Set<Integer> oldIds = cardIds(game.getView());

            // the host restarts the game: a new Game on the same table, started from its latest save
            Game next = initAndCreateThreePlayerGame();
            for (Player p : next.getPlayers()) {
                new CompanionPlayerController(next, p, new forge.ai.LobbyPlayerAi(p.getName(), null), table);
            }
            table.startFromLatest(next);
            table.atPrompt(next);

            await(() -> view.opened.get() == 2 && c.epoch() == 2, table::runSystemOps,
                    "a fresh attach to the new game, with the next epoch");
            await(() -> board(view.getGameView()).equals(board(next.getView())), () -> { }, "the new board");
            Set<Integer> stale = new TreeSet<>(oldIds);
            Set<Integer> newIds = cardIds(next.getView());
            stale.removeAll(newIds);
            await(() -> trackerHolds(view, newIds, stale), () -> { }, "the tracker holding the new game's cards");

            // the old game is let go of: what still happens on it no longer reaches the phone (its forwarder is shut
            // down and taken off its bus and inputs). A coin flip, which nothing else here makes, then a priority
            // that would flush it; the new game's action after it shows the phone has read past where it would be
            Player oldActive = game.getPhaseHandler().getPlayerTurn();
            game.fireEvent(new GameEventFlipCoin(oldActive.getView(), true, false));
            game.fireEvent(new GameEventPlayerPriority(oldActive.getView(), PhaseType.MAIN1, oldActive.getView()));
            flushViews(table);

            // the new game's actions reach the phone
            share(table, next, "Forest");
            flushViews(table);
            await(() -> onBattlefield(view.getGameView(), "Forest"), () -> { }, "a live action in the new game");
            await(() -> board(view.getGameView()).equals(board(next.getView())), () -> { }, "the board after it");
            assertEquals(client.epoch(), 2, "an action is a delta");
            assertEquals(host.peerCount(), 1);
            assertFalse(view.events.contains("GameEventFlipCoin"), "nothing of the old game after the new one: "
                    + view.events);
        } finally {
            close(client);
            host.stop();
        }
    }

    /**
     * A phone attached when the host ends the game stops following, with no reconnect; one coming back to the ended
     * game later gets the end, and is let go.
     */
    @Test(timeOut = 120_000)
    public void aPhoneComingBackToAnEndedGameGetsTheEnd() throws Exception {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = startedTable(game);
        CompanionStore hostStore = table.store();
        share(table, game, "Grizzly Bears");
        CompanionStore phone = tempStore();
        CompanionStore killed = tempStore();
        CompanionShareHost host = new CompanionShareHost(table);
        CompanionClient live = null;
        CompanionClient early = null;
        CompanionClient late = null;
        try {
            host.start();
            JoinCode code = host.codeAt(loopback());
            live = joined(table, code, new CountingView(), phone);
            early = joined(table, code, new CountingView(), killed);
            await(() -> sameCopy(hostStore, killed, "t"), () -> { }, "the second phone's copy");
            early.close(); // killed before the end
            await(() -> host.peerCount() == 1, table::runSystemOps, "the killed phone let go of");

            hostStore.end("t");
            CompanionClient c = live;
            await(() -> c.link() == Link.ENDED && phone.isEnded("t"), () -> { }, "the live phone seeing the end");
            assertTrue(live.hostEnded());

            // the killed phone reopens the game: it rejoins, and gets the end instead of a board
            CountingView view = new CountingView();
            late = new CompanionClient("Joiner", view, code, killed);
            late.rejoin("t");
            CompanionClient l = late;
            await(() -> l.link() == Link.ENDED && killed.isEnded("t"), table::runSystemOps, "the end for the late phone");
            assertTrue(sameLog(hostStore, killed, "t"), "its copy is the host's, ended");
            await(() -> host.peerCount() == 1, table::runSystemOps, "the late phone let go of by the host");
            Thread.sleep(1500); // longer than the first reconnect waits: none must follow
            table.runSystemOps();
            assertEquals(view.opened.get(), 0, "no board for an ended game");
            assertNotEquals(late.getReconnectState(), FGameClient.ReconnectState.RECONNECTING);
            assertEquals(late.link(), Link.ENDED);
            assertEquals(host.peerCount(), 1, "only the live phone, which saw the end");
        } finally {
            close(live);
            close(early);
            close(late);
            host.stop();
        }
    }
}
