package forge.gamemodes.companion.share;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.event.GameEvent;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CompanionPlayerController;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.match.input.InputQueue;
import forge.gamemodes.net.NetworkByteTracker;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.control.GameEventForwarder;
import forge.trackable.TrackableCollection;
import forge.util.IHasForgeLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One joined phone on the host: net play's {@link RemoteClient} and {@link RemoteClientGuiGame} for its connection,
 * and a {@link GameEventForwarder} that feeds the proxy from the host's game, as net play wires a remote player and
 * {@code ReplaySession} its hidden GUI. Every seat is the phone's, with {@link PeerGate} as each seat's controller, so
 * the phone builds a controller per seat, shows every hand as the host does, and changes nothing (M1).
 *
 * <p>Attach and detach run on the game loop thread, through {@link CompanionTable#runSystem}, as the proxy walks the
 * view there. {@link #close} may come from any thread: it stops every send at once and leaves the rest to the loop.
 */
public final class CompanionPeer implements IHasForgeLog {

    /** The proxy, which also tells the phone the view epoch after each full view it sends (plan 3.2). */
    private final class PeerGuiGame extends RemoteClientGuiGame {
        /** True while attaching, whose epoch goes after {@code openView}, not between it and the view. */
        private boolean attaching;

        PeerGuiGame(RemoteClient client) {
            super(client);
        }

        @Override
        public void sendFullState() {
            boolean sends = getGameView() != null && !isPaused();
            long bytes0 = bytesSent();
            long t0 = System.nanoTime();
            super.sendFullState();
            if (meter != null && sends) {
                meter.fullState(getClient().getUsername(), System.nanoTime() - t0, bytesSent() - bytes0);
            }
            if (sends && !attaching) {
                sendEpoch();
            }
        }

        /** A flush: timed and sized for the measurement when it's on. Game thread. */
        @Override
        public void handleGameEvents(List<GameEvent> events) {
            if (meter == null || events.isEmpty() || isPaused()) {
                super.handleGameEvents(events);
                return;
            }
            long bytes0 = bytesSent();
            long t0 = System.nanoTime();
            super.handleGameEvents(events);
            meter.flushed(events.get(events.size() - 1), getClient().getUsername(), System.nanoTime() - t0,
                    bytesSent() - bytes0);
        }

        private long bytesSent() {
            return bytes == null ? 0 : bytes.getTotalBytesSent();
        }
    }

    /** Passes the game's events on to the forwarder until {@link #close} lets go of it. Any thread may empty it. */
    private static final class Relay {
        volatile GameEventForwarder target;

        @Subscribe
        public void receiveGameEvent(GameEvent ev) {
            GameEventForwarder f = target;
            if (f != null) {
                f.receiveGameEvent(ev);
            }
        }
    }

    private final CompanionTable table;
    private final RemoteClient client;
    /** The measurement (slice 01), and what this phone's encoder wrote; both null when it's off. */
    private final ShareMeter meter;
    private final NetworkByteTracker bytes;
    private final Runnable disconnect;
    private final PeerGuiGame gui;
    private final PeerGate gate = new PeerGate();
    /** What the game's event bus holds instead of the forwarder, which it would keep until the game ends. */
    private final Relay relay = new Relay();
    /** The input queues the forwarder observes, for the detach. */
    private final List<InputQueue> observed = new CopyOnWriteArrayList<>();
    /** Set on the game loop thread, read by {@link #close} from any. */
    private volatile GameEventForwarder forwarder;
    /** Full views sent so far. Game loop thread only. */
    private int epoch;
    private volatile boolean closed;

    /**
     * {@code disconnect} closes the phone's connection, which then detaches it and takes it off the share's count.
     * {@code meter} and the byte tracker of this phone's encoder are for the measurement; both null when it's off.
     */
    CompanionPeer(CompanionTable table, RemoteClient client, Runnable disconnect, ShareMeter meter,
                  NetworkByteTracker bytes) {
        this.table = table;
        this.client = client;
        this.meter = meter;
        this.bytes = bytes;
        this.disconnect = disconnect;
        this.gui = new PeerGuiGame(client);
    }

    RemoteClient client() {
        return client;
    }

    RemoteClientGuiGame gui() {
        return gui;
    }

    PeerGate gate() {
        return gate;
    }

    /** The full views sent so far. Game loop thread. */
    int epoch() {
        return epoch;
    }

    /** Attaches the phone to the running game at the game loop's next priority prompt. Any thread. */
    void attach() {
        table.runSystem(this::attachNow);
    }

    /** Game loop thread: the phone gets the board once, its seats, then the epoch; deltas follow. */
    void attachNow() {
        List<CompanionPlayerController> seats = table.seats();
        if (closed) {
            return;
        }
        if (seats.isEmpty()) {
            // no game to show (the table has no seats yet, or no more): let the phone go rather than count it
            netLog.warn("[Companion] {} can't attach to {}: no seats; closing", client.getUsername(), table.id());
            close();
            disconnect.run();
            return;
        }
        Game game = seats.get(0).getGame();
        forwarder = new GameEventForwarder(gui);
        gui.setForwarder(forwarder);
        // Game has no unsubscribe: the bus keeps the relay, which close() empties, so the forwarder can go
        relay.target = forwarder;
        game.subscribeToEvents(relay);
        // so events reach the phone before the game thread blocks for input, as for a net play client
        for (CompanionPlayerController seat : seats) {
            InputQueue queue = seat.getInputQueue();
            queue.addObserver(forwarder);
            observed.add(queue);
        }
        List<PlayerView> all = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            gui.setOriginalGameController(player.getView(), gate);
            all.add(player.getView());
        }
        gui.attaching = true;
        try {
            gui.attachMidGame(game.getView(), new TrackableCollection<>(all));
        } finally {
            gui.attaching = false;
        }
        sendEpoch();
        netLog.info("[Companion] {} attached to {}, {} seats", client.getUsername(), table.id(), all.size());
        if (closed) {
            relay.target = null; // closed while attaching
            stopObserving();
        }
    }

    private void sendEpoch() {
        epoch++;
        client.send(new ShareMessages.Epoch(epoch));
    }

    /**
     * The phone left, or the share stopped: nothing more is sent to it, any reply the host waits for is cancelled, and
     * the forwarder is taken off the game at the loop's next priority prompt. Any thread; once only.
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        relay.target = null;
        gui.pause();
        stopObserving();
        client.getReplyPool().cancelAll();
        table.runSystem(gui::shutdownForwarder);
    }

    private void stopObserving() {
        GameEventForwarder f = forwarder;
        for (InputQueue queue : observed) {
            if (f != null) {
                queue.deleteObserver(f);
            }
        }
        observed.clear();
    }

    boolean isClosed() {
        return closed;
    }
}
