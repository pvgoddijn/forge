package forge.gamemodes.companion.share;

import forge.util.IHasForgeLog;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * This device's addresses another phone on the same local network can join on: IPv4, site-local (10/8, 172.16/12,
 * 192.168/16), on an interface that is up and isn't loopback, cellular, a VPN or a virtual bridge. Found by walking the
 * interfaces, with no route lookup, so it works without internet (unlike {@code FServerManager.getLocalAddress}, which
 * routes towards 8.8.8.8). Interface names are the raw ones ({@code wlan0}, {@code ap0}); friendly names are slice 09.
 */
public final class LocalAddresses implements IHasForgeLog {

    /** One address to show a code for, with the interface it's on. */
    public record Usable(String interfaceName, Inet4Address address) { }

    /** Name prefixes of interfaces a phone on the same Wi-Fi or hotspot can't reach us through. */
    private static final String[] EXCLUDED_PREFIXES = {
            // cellular (Android: Qualcomm, MediaTek, older modems; clat is IPv4 over a cellular IPv6 network)
            "rmnet", "ccmni", "pdp", "wwan", "clat", "v4-",
            // VPNs
            "tun", "ppp", "wg", "ipsec", "utun", "tailscale", "zt",
            // virtual bridges on a desktop
            "docker", "veth", "br-", "virbr", "vboxnet", "vmnet",
    };

    private LocalAddresses() {
    }

    /** The usable addresses, in interface order, or none when the interfaces can't be read. */
    public static List<Usable> find() {
        List<Usable> found = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!usableInterface(nif.getName(), nif.isUp(), nif.isLoopback(), nif.isPointToPoint(),
                        nif.isVirtual())) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address ip && usableAddress(ip)) {
                        found.add(new Usable(nif.getName(), ip));
                    }
                }
            }
        } catch (SocketException | RuntimeException e) {
            // Android throws for interfaces it hides from the app; with none found the dialog says so
            netLog.warn(e, "Could not list the network interfaces");
        }
        return found;
    }

    static boolean usableInterface(String name, boolean up, boolean loopback, boolean pointToPoint, boolean virtual) {
        if (!up || loopback || pointToPoint || virtual || name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : EXCLUDED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    static boolean usableAddress(Inet4Address address) {
        return address.isSiteLocalAddress() && !address.isLoopbackAddress() && !address.isLinkLocalAddress();
    }
}
