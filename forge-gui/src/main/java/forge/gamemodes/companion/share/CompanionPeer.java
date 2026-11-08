package forge.gamemodes.companion.share;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.gamemodes.companion.CompanionPlayerController;
import forge.gamemodes.companion.CompanionTable;
import forge.gamemodes.match.input.InputQueue;
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
            super.sendFullState();
            if (sends && !attaching) {
                sendEpoch();
            }
        }
    }

    private final CompanionTable table;
    private final RemoteClient client;
    private final PeerGuiGame gui;
    private final PeerGate gate = new PeerGate();
    /** The input queues the forwarder observes, for the detach. */
    private final List<InputQueue> observed = new CopyOnWriteArrayList<>();
    /** Set on the game loop thread, read by {@link #close} from any. */
    private volatile GameEventForwarder forwarder;
    /** Full views sent so far. Game loop thread only. */
    private int epoch;
    private volatile boolean closed;

    CompanionPeer(CompanionTable table, RemoteClient client) {
        this.table = table;
        this.client = client;
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
        if (closed || seats.isEmpty()) {
            return;
        }
        Game game = seats.get(0).getGame();
        forwarder = new GameEventForwarder(gui);
        gui.setForwarder(forwarder);
        // Game has no unsubscribe: after a detach the paused proxy drops what this forwarder still passes it
        game.subscribeToEvents(forwarder);
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
            stopObserving(); // closed while attaching
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
