package forge.gamemodes.companion.share;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What a phone types to join a shared game (plan 3.4): the host's IPv4 address (4 bytes), its Companion port (2) and
 * the share token (4), as 16 characters of Crockford base32 shown as {@code XXXX-XXXX-XXXX-XXXX}. Crockford's alphabet
 * leaves out I, L, O and U; reading, I and L count as 1 and O as 0, and case, dashes and spaces don't matter. The code
 * has no check character: a mistyped one that still reads names some other address, and the join just fails.
 */
public record JoinCode(Inet4Address address, int port, int token) {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int CHARS = 16;
    private static final int BYTES = 10; // 80 bits, 5 per character

    public JoinCode {
        Objects.requireNonNull(address, "address");
        if (address.isAnyLocalAddress()) {
            throw new IllegalArgumentException("not an address to join: " + address.getHostAddress());
        }
        if (port < 1 || port > 0xFFFF) {
            throw new IllegalArgumentException("not a port: " + port);
        }
    }

    /** The code as shown: {@code XXXX-XXXX-XXXX-XXXX}. */
    public String text() {
        byte[] bytes = new byte[BYTES];
        System.arraycopy(address.getAddress(), 0, bytes, 0, 4);
        bytes[4] = (byte) (port >>> 8);
        bytes[5] = (byte) port;
        for (int i = 0; i < 4; i++) {
            bytes[6 + i] = (byte) (token >>> (24 - 8 * i));
        }
        StringBuilder sb = new StringBuilder(CHARS + 3);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                if (sb.length() == 4 || sb.length() == 9 || sb.length() == 14) {
                    sb.append('-');
                }
                sb.append(ALPHABET.charAt((buffer >>> bits) & 0x1F));
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return text();
    }

    /** The code {@code typed} stands for, or empty when it isn't one. */
    public static Optional<JoinCode> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String text = typed.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "")
                .replace('O', '0').replace('I', '1').replace('L', '1');
        if (text.length() != CHARS) {
            return Optional.empty();
        }
        byte[] bytes = new byte[BYTES];
        int buffer = 0;
        int bits = 0;
        int next = 0;
        for (int i = 0; i < CHARS; i++) {
            int value = ALPHABET.indexOf(text.charAt(i));
            if (value < 0) {
                return Optional.empty();
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes[next++] = (byte) (buffer >>> bits);
            }
        }
        byte[] ip = new byte[4];
        System.arraycopy(bytes, 0, ip, 0, 4);
        int port = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
        int token = 0;
        for (int i = 0; i < 4; i++) {
            token = (token << 8) | (bytes[6 + i] & 0xFF);
        }
        try {
            return Optional.of(new JoinCode(ipv4(ip), port, token));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Inet4Address ipv4(byte[] ip) {
        try {
            return (Inet4Address) InetAddress.getByAddress(ip);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("4 bytes are always an IPv4 address", e); // only for a wrong length
        }
    }
}
