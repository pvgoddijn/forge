package forge.gamemodes.companion.share;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How a game in this device's store is shared, kept as {@value #FILE} in its folder (plan 2.2, 4.1). The share's token
 * lasts for the game (spec B3), so the same code works after a restart:
 * <ul>
 * <li>{@link Role#JOINED}: a game followed from another phone, with the host's address, port and token, written when
 * the host welcomes this phone. Such a game is never resumed as this device's own ({@link #isFollowed}): opening it
 * would write to a copy that must stay the host's byte for byte.</li>
 * <li>{@link Role#HOSTING}: a game of this device's own that it shares, with the port and token it shares on and its
 * addresses then, written when sharing starts. Resuming it shares again on the same port and token.</li>
 * </ul>
 * The phone's own seat, the last roster and the current term are added by slices 05 and M3.
 *
 * @param role        how this device takes part
 * @param hostAddress the host's IPv4 address, as text: the one joined at; for {@link Role#HOSTING}, the first of
 *                    {@code addresses}, or null when it had none
 * @param port        the host's share port
 * @param token       the share's token, to join again
 * @param addresses   the host's IPv4 addresses known, as text; for {@link Role#JOINED} the one joined at
 */
public record ShareState(Role role, String hostAddress, int port, int token, List<String> addresses) {
    /** The file in the game's folder. */
    public static final String FILE = "share.json";

    public ShareState {
        addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
    }

    /** How this device takes part in the shared game. */
    public enum Role {
        /** It follows a game another phone hosts. */
        JOINED,
        /** It hosts the game and shares it. */
        HOSTING
    }

    /** The state of a game followed from the host at {@code code}. */
    public static ShareState joined(JoinCode code) {
        String address = code.address().getHostAddress();
        return new ShareState(Role.JOINED, address, code.port(), code.token(), List.of(address));
    }

    /** The state of a game this device shares on {@code port} with {@code token}, at {@code addresses}. */
    public static ShareState hosting(int port, int token, List<Inet4Address> addresses) {
        List<String> texts = new ArrayList<>();
        for (Inet4Address address : addresses) {
            texts.add(address.getHostAddress());
        }
        return new ShareState(Role.HOSTING, texts.isEmpty() ? null : texts.get(0), port, token, texts);
    }

    /**
     * The code to join the host again. Throws IllegalStateException when the address isn't an IPv4 address, or there
     * is none.
     */
    public JoinCode code() {
        if (hostAddress == null) {
            throw new IllegalStateException("No address in " + FILE);
        }
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

    /** Whether the game {@code game} has a share state, hosted or followed. */
    public static boolean exists(CompanionStore store, String game) {
        return new File(store.gameDir(game), FILE).exists();
    }

    /**
     * Whether the game {@code game} is followed from another phone, so never resumed as this device's own: it has a
     * share state that isn't {@link Role#HOSTING}. One that can't be read counts as followed, to be safe. Known limit:
     * that includes a game this device hosted whose {@code share.json} got damaged: Load game then offers it as
     * followed (Stop following, or a code to type), and it can't be continued as this device's own any more.
     */
    public static boolean isFollowed(CompanionStore store, String game) {
        if (!exists(store, game)) {
            return false;
        }
        try {
            return read(store, game).map(state -> state.role() != Role.HOSTING).orElse(false);
        } catch (RuntimeException e) {
            System.err.println("Can't read the share state of companion game " + game + ": " + e);
            return true;
        }
    }

    /** The game's share state when it's one this device hosts, else empty (also when it can't be read). */
    public static Optional<ShareState> hosted(CompanionStore store, String game) {
        try {
            return read(store, game).filter(state -> state.role() == Role.HOSTING);
        } catch (RuntimeException e) {
            System.err.println("Can't read the share state of companion game " + game + ": " + e);
            return Optional.empty();
        }
    }

    /**
     * The game's share state, or empty when it has none (a game of this device's own, never shared). Throws
     * {@link UncheckedIOException} when it can't be read, and IllegalArgumentException when it doesn't read: such a
     * game still {@link #exists}. A file from before slice 04 (joined, no {@code addresses}) reads as having the one
     * address.
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
            JsonElement host = json.get("hostAddress");
            String hostAddress = host == null || host.isJsonNull() ? null : host.getAsString();
            List<String> addresses = new ArrayList<>();
            if (json.has("addresses")) {
                for (JsonElement address : json.getAsJsonArray("addresses")) {
                    addresses.add(address.getAsString());
                }
            } else if (hostAddress != null) {
                addresses.add(hostAddress);
            }
            return Optional.of(new ShareState(Role.valueOf(json.get("role").getAsString().toUpperCase(Locale.ROOT)),
                    hostAddress, json.get("port").getAsInt(), json.get("token").getAsInt(), addresses));
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
        JsonArray list = new JsonArray();
        for (String address : addresses) {
            list.add(address);
        }
        json.add("addresses", list);
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

    /** Deletes the game's share state, if any: it's no longer shared. Throws {@link UncheckedIOException} on failure. */
    public static void delete(CompanionStore store, String game) {
        File file = new File(store.gameDir(game), FILE);
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
