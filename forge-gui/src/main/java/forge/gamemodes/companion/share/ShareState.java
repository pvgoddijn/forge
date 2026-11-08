package forge.gamemodes.companion.share;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.gamemodes.companion.CompanionStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * How a game in this phone's store is shared, kept as {@value #FILE} in its folder (plan 4.1): a game followed from
 * another phone has one with {@link Role#JOINED} and the host's address, port and token, written when the host
 * welcomes this phone. A game with this file is never resumed as the phone's own: opening it would write to a copy
 * that must stay the host's byte for byte. Slice 04 adds to it.
 *
 * @param role        how this phone takes part
 * @param hostAddress the host's IPv4 address, as text
 * @param port        the host's share port
 * @param token       the share's token, to join again
 */
public record ShareState(Role role, String hostAddress, int port, int token) {
    /** The file in the game's folder. */
    public static final String FILE = "share.json";

    /** How this phone takes part in the shared game. */
    public enum Role {
        /** It follows a game another phone hosts. */
        JOINED
    }

    /** The state of a game followed from the host at {@code code}. */
    public static ShareState joined(JoinCode code) {
        return new ShareState(Role.JOINED, code.address().getHostAddress(), code.port(), code.token());
    }

    /** The code to join the host again. Throws IllegalStateException when the address isn't an IPv4 address. */
    public JoinCode code() {
        try {
            InetAddress address = InetAddress.getByName(hostAddress); // a literal: no lookup
            if (!(address instanceof Inet4Address v4)) {
                throw new IllegalStateException("Not an IPv4 address in " + FILE + ": " + hostAddress);
            }
            return new JoinCode(v4, port, token);
        } catch (IOException e) {
            throw new IllegalStateException("Not an address in " + FILE + ": " + hostAddress, e);
        }
    }

    /** Whether the game {@code game} has a share state: if so, it is never resumed as this phone's own. */
    public static boolean exists(CompanionStore store, String game) {
        return new File(store.gameDir(game), FILE).exists();
    }

    /**
     * The game's share state, or empty when it has none (a game of this phone's own). Throws
     * {@link UncheckedIOException} when it can't be read, and IllegalArgumentException when it doesn't read: such a
     * game still {@link #exists}.
     */
    public static Optional<ShareState> read(CompanionStore store, String game) {
        File file = new File(store.gameDir(game), FILE);
        if (!file.exists()) {
            return Optional.empty();
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            return Optional.of(new ShareState(Role.valueOf(json.get("role").getAsString().toUpperCase(Locale.ROOT)),
                    json.get("hostAddress").getAsString(), json.get("port").getAsInt(),
                    json.get("token").getAsInt()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Can't read " + file + ": " + e, e);
        }
    }

    /** Writes this as the share state of the game {@code game}, whose folder exists, replacing any. */
    public void write(CompanionStore store, String game) {
        JsonObject json = new JsonObject();
        json.addProperty("role", role.name().toLowerCase(Locale.ROOT));
        json.addProperty("hostAddress", hostAddress);
        json.addProperty("port", port);
        json.addProperty("token", token);
        File file = new File(store.gameDir(game), FILE);
        File temp = new File(file.getParentFile(), FILE + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            temp.delete();
            throw new UncheckedIOException(e);
        }
    }
}
