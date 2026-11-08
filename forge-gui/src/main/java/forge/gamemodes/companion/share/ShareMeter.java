package forge.gamemodes.companion.share;

import forge.localinstance.properties.ForgeNetPreferences;
import forge.model.FModel;
import forge.util.IHasForgeLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Timing and size logging for the multi-phone measurement (slice 01), off by default: the net preference
 * {@code COMPANION_SHARE_MEASURE=true} ({@code forge.net.preferences}) or {@code -Dforge.companion.measure=true}. When
 * off the host makes none and nothing changes.
 *
 * <p>Logs one {@code netLog} line per flush, for all peers: the time the game thread spent in the peers' sends (the
 * view walk and each peer's encoding) and the bytes each peer got, and one line per full state. The peers' flushes at
 * one flush point carry the same last event (each peer's forwarder sees the same events), so a flush is told from the
 * next by that event; its line is written when the next flush starts, or when the share stops. Bytes are what the
 * peer's encoder wrote, LZ4 included, with the 4-byte frame length; a heartbeat answer that lands meanwhile counts too.
 */
final class ShareMeter implements IHasForgeLog {
    static final String PROPERTY = "forge.companion.measure";

    /** Whether the measurement is on. Read once per share. */
    static boolean enabled() {
        if (Boolean.getBoolean(PROPERTY)) {
            return true;
        }
        try {
            return FModel.getNetPreferences().getPrefBoolean(ForgeNetPreferences.FNetPref.COMPANION_SHARE_MEASURE);
        } catch (RuntimeException e) {
            return false; // no preferences loaded: off
        }
    }

    /** The flush being summed: its last event, and per peer the name and bytes. */
    private Object key;
    private long nanos;
    private final List<String> perPeer = new ArrayList<>();
    private int flushes;

    /** One peer's send of one batch ending with {@code lastEvent}. Game thread. */
    synchronized void flushed(Object lastEvent, String peer, long sendNanos, long bytes) {
        if (lastEvent != key) {
            write();
            key = lastEvent;
        }
        nanos += sendNanos;
        perPeer.add(peer + "=" + bytes);
    }

    /** A full state sent to one peer (an attach, an undo, a resync). Game thread. */
    void fullState(String peer, long sendNanos, long bytes) {
        netLog.info("[CompanionMeasure] full state to {}: {} bytes, {} ms", peer, bytes, millis(sendNanos));
    }

    /** Writes the flush being summed, if any. */
    synchronized void write() {
        if (key == null) {
            return;
        }
        flushes++;
        netLog.info("[CompanionMeasure] flush #{}: {} peers, {} ms on the game thread, bytes {}", flushes,
                perPeer.size(), millis(nanos), perPeer);
        key = null;
        nanos = 0;
        perPeer.clear();
    }

    private static String millis(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }
}
