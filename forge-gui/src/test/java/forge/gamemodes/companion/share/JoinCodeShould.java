package forge.gamemodes.companion.share;

import org.testng.annotations.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/** The typed join code (multi-phone slice 0, plan 3.4): an IPv4 address, a port and a share token in 16 characters. */
public class JoinCodeShould {

    private static Inet4Address ip(String text) {
        try {
            return (Inet4Address) InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void giveBackTheAddressPortAndTokenItWasMadeFrom() {
        JoinCode code = new JoinCode(ip("192.168.43.1"), 36744, 0xCAFE_F00D);

        String text = code.text();
        assertThat(text).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}");
        assertThat(text).doesNotContain("I", "L", "O", "U");
        assertThat(JoinCode.parse(text)).contains(code);
        // as typed on a phone: lower case, no dashes, spaces, and the letters Crockford reads as digits
        String typed = text.toLowerCase().replace("-", " ").replace('0', 'o').replace('1', 'l');
        assertThat(JoinCode.parse(typed)).contains(code);
        // the edges of each field survive
        JoinCode edges = new JoinCode(ip("255.0.0.255"), 65535, -1);
        assertThat(JoinCode.parse(edges.text())).contains(edges);
    }

    @Test
    public void refuseWhatIsNotACode() {
        String good = new JoinCode(ip("10.0.0.7"), 36744, 42).text();

        assertThat(JoinCode.parse(good.substring(0, good.length() - 1))).isEmpty(); // a character short
        assertThat(JoinCode.parse(good + "7")).isEmpty(); // one too many
        assertThat(JoinCode.parse("U" + good.substring(1))).isEmpty(); // not a Crockford character
        assertThat(JoinCode.parse("")).isEmpty();
        assertThat(JoinCode.parse(null)).isEmpty();
        // well formed, but address 0.0.0.0 and port 0 can't be joined
        assertThat(JoinCode.parse("0000-0000-0000-0000")).isEmpty();
    }
}
