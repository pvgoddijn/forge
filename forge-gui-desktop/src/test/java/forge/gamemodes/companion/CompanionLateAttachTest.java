package forge.gamemodes.companion;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.TrackableSerializer;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gui.control.GameEventForwarder;
import forge.trackable.TrackableCollection;

import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * A view attached to a Companion game after it started, as a joined phone's proxy is (multi-phone slice 0): it gets the
 * board once and deltas after, and an undo, which renumbers every card, sends it the whole board again. The peer is a
 * {@link ProtocolGuiGame} on an in-process {@link IRemote} that keeps, at each send, which card ids the peer would
 * hold, the way a client's tracker does.
 */
public class CompanionLateAttachTest extends AITest {

    /** What the peer was sent, with the card ids each message carried, taken at the time of the send. */
    private static final class Peer implements IRemote {
        final List<String> methods = new ArrayList<>();
        /** The card ids of each full state sent, in order. */
        final List<Set<Integer>> fullStates = new ArrayList<>();
        final List<DeltaPacket> deltas = new ArrayList<>();
        /** The card ids the peer holds: the last full state's, plus the new objects of each delta since. */
        final Set<Integer> held = new HashSet<>();
        /** The card ids of a delta that changed a card the peer doesn't hold. */
        final List<Integer> unresolved = new ArrayList<>();
        int lastFullBytes;

        @Override
        public void send(NetEvent event) {
            GuiGameEvent ev = (GuiGameEvent) event;
            methods.add(ev.getMethod().name());
            if (ev.getMethod() == ProtocolMethod.setGameView) {
                GameView view = (GameView) ev.getObjects()[0];
                Set<Integer> ids = cardIds(view);
                fullStates.add(ids);
                held.clear();
                held.addAll(ids);
                lastFullBytes = TrackableSerializer.measureSize(view, null);
            } else if (ev.getMethod() == ProtocolMethod.applyDelta) {
                DeltaPacket delta = (DeltaPacket) ev.getObjects()[0];
                deltas.add(delta);
                for (int key : delta.getNewObjects().keySet()) {
                    if (DeltaPacket.getTypeFromDeltaKey(key) == DeltaPacket.TYPE_CARD_VIEW) {
                        held.add(DeltaPacket.getIdFromDeltaKey(key));
                    }
                }
                for (int key : delta.getObjectDeltas().keySet()) {
                    if (DeltaPacket.getTypeFromDeltaKey(key) == DeltaPacket.TYPE_CARD_VIEW
                            && !held.contains(DeltaPacket.getIdFromDeltaKey(key))) {
                        unresolved.add(DeltaPacket.getIdFromDeltaKey(key));
                    }
                }
            }
        }

        @Override
        public Object sendAndWait(IdentifiableNetEvent event) {
            send(event);
            return null;
        }
    }

    private static Set<Integer> cardIds(GameView view) {
        Set<Integer> ids = new HashSet<>();
        for (PlayerView player : view.getPlayers()) {
            for (ZoneType zone : ZoneType.values()) {
                if (player.getCards(zone) != null) {
                    player.getCards(zone).forEach(c -> ids.add(c.getId()));
                }
            }
        }
        if (view.getStack() != null) {
            for (StackItemView item : view.getStack()) {
                ids.add(item.getSourceCard().getId());
            }
        }
        return ids;
    }

    private static Set<Integer> newCardIds(DeltaPacket delta) {
        Set<Integer> ids = new HashSet<>();
        for (int key : delta.getNewObjects().keySet()) {
            if (DeltaPacket.getTypeFromDeltaKey(key) == DeltaPacket.TYPE_CARD_VIEW) {
                ids.add(DeltaPacket.getIdFromDeltaKey(key));
            }
        }
        return ids;
    }

    private static CompanionTable newTable() {
        try {
            File dir = Files.createTempDirectory("companion").toFile();
            assertTrue(new File(dir, "t").mkdir());
            CompanionSetup setup = CompanionSetup.withDefaults(List.of(new CompanionSetup.Seat("Ann", true),
                    new CompanionSetup.Seat("Bo", false), new CompanionSetup.Seat("Cy", false)));
            return new CompanionTable("t", setup, new CompanionStore(dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs {@code op} and then the save the next prompt would make, as the game loop does. */
    private static void act(CompanionTable table, Game game, CompanionTable.Op op) {
        table.submit(op);
        assertTrue(table.runPending(game));
        table.save(game);
    }

    @Test
    public void aViewAttachedLateGetsTheBoardOnceAndTheWholeBoardAgainOnUndo() {
        Game game = initAndCreateThreePlayerGame();
        CompanionTable table = newTable();
        table.startFromLatest(game);
        Player ann = game.getPhaseHandler().getPlayerTurn();
        table.atPrompt(game); // the start, saved as the first step
        act(table, game, new CompanionTable.AddCard(ann.getName(), "Grizzly Bears", false,
                TableEditor.Mode.PUT_ONTO_BATTLEFIELD));

        // the game has started: a peer attaches, on the game loop thread
        Peer peer = new Peer();
        ProtocolGuiGame proxy = new ProtocolGuiGame(peer);
        GameEventForwarder forwarder = new GameEventForwarder(proxy);
        table.runSystem(() -> {
            proxy.setGameView(null);
            proxy.setGameView(game.getView());
            proxy.setForwarder(forwarder);
            game.subscribeToEvents(forwarder);
            List<PlayerView> seats = new ArrayList<>();
            game.getPlayers().forEach(p -> seats.add(p.getView()));
            proxy.openView(new TrackableCollection<>(seats));
            proxy.attachMidGame();
        });
        assertTrue(peer.methods.isEmpty(), "nothing runs until the loop runs the system ops");
        table.runSystemOps();
        assertFalse(peer.fullStates.isEmpty(), "the peer got the board on attach");
        Set<Integer> attached = peer.fullStates.get(peer.fullStates.size() - 1);
        int bears = game.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().equals("Grizzly Bears")).findFirst().orElseThrow().getId();
        assertTrue(attached.contains(bears), "the board as it is, with the card added before the attach");
        int fullBytes = peer.lastFullBytes;
        int fullStatesOnAttach = peer.fullStates.size();
        assertFalse(table.ranSinceSave(), "a system op isn't an action: nothing to save");

        // the first flush after the attach is a delta over what the peer has, not the whole graph again
        int deltasBefore = peer.deltas.size();
        act(table, game, new CompanionTable.AddCard(ann.getName(), "Llanowar Elves", false,
                TableEditor.Mode.PUT_ONTO_BATTLEFIELD));
        forwarder.flush();
        assertEquals(peer.fullStates.size(), fullStatesOnAttach, "no full state for an ordinary action");
        assertTrue(peer.deltas.size() > deltasBefore, "the action reached the peer as a delta");
        DeltaPacket first = peer.deltas.get(deltasBefore);
        Set<Integer> resent = newCardIds(first);
        resent.retainAll(attached);
        System.out.printf("[late attach] full state %d bytes (%d full sends on attach), first flush %d bytes,"
                        + " %d new objects, %d changed%n", fullBytes, fullStatesOnAttach,
                TrackableSerializer.measureSize(first.withoutEvents(), game.getTracker()),
                first.getNewObjects().size(), first.getObjectDeltas().size());
        assertEquals(resent, Set.of(), "the first flush doesn't send the attached board again");
        assertEquals(peer.unresolved, List.of(), "every changed card is one the peer holds");

        // the host undoes: every card is renumbered, so the peer gets the whole board again
        Set<Integer> beforeUndo = cardIds(game.getView());
        table.undo();
        assertTrue(table.runPending(game));
        forwarder.flush();
        assertEquals(peer.fullStates.size(), fullStatesOnAttach + 1, "one full state for the undo");
        Set<Integer> afterUndo = peer.fullStates.get(peer.fullStates.size() - 1);
        assertEquals(afterUndo, cardIds(game.getView()), "the full state is the board after the undo");
        assertFalse(afterUndo.equals(beforeUndo), "the undo renumbered the cards");
        assertFalse(game.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getName().equals("Llanowar Elves")),
                "the undo took the elves back");

        // and the deltas after it resolve against the new ids
        int deltasAfterUndo = peer.deltas.size();
        act(table, game, new CompanionTable.AddCard(ann.getName(), "Llanowar Elves", false,
                TableEditor.Mode.PUT_ONTO_BATTLEFIELD));
        forwarder.flush();
        assertTrue(peer.deltas.size() > deltasAfterUndo, "the action after the undo reached the peer");
        assertEquals(peer.unresolved, List.of(), "every changed card is one the peer holds");
        CardView elves = game.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().equals("Llanowar Elves")).findFirst().orElseThrow().getView();
        assertTrue(peer.held.contains(elves.getId()), "the peer holds the card added after the undo");
        assertNotNull(proxy.getGameView());
    }
}
