package forge.interfaces;

import org.apache.commons.lang3.tuple.Pair;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;

public interface IDeviceAdapter {
    boolean isConnectedToInternet();
    boolean isConnectedToWifi();
    boolean isTablet();
    String getDownloadsDir();
    String getVersionString();
    String getLatestChanges(String commitsAtom, Date buildDateOriginal, Date maxDate);
    String getReleaseTag(String releaseAtom);
    boolean openFile(String filename);
    void setLandscapeMode(boolean landscapeMode);
    void preventSystemSleep(boolean preventSleep);
    void restart();
    void exit();
    void closeSplashScreen();
    void convertToJPEG(InputStream input, OutputStream output) throws IOException;
    void convertToPNG(InputStream input, OutputStream output) throws IOException;
    Pair<Integer, Integer> getRealScreenSize(boolean real);
    ArrayList<String> getGamepads();
    UpnpServiceConfiguration getUpnpPlatformService();
    boolean needFileAccess();
    void requestFileAcces();

    /** Confine the mouse cursor to the window (desktop LibGDX only). Default no-op. */
    default void setMouseConfined(boolean confined) {
    }

    /**
     * {@code true}: a Companion join starts; keep the local network (Wi-Fi) up and known while it lasts, also when it
     * has no internet and mobile data is on; {@code false}: the join is over, let it go. Called again with the same
     * value, it does nothing more. Any thread. Default no-op (Android asks to keep the Wi-Fi network).
     */
    default void holdLocalNetwork(boolean hold) {
    }

    /**
     * A new, unconnected socket that connects over the local network (Wi-Fi) even when it has no internet and mobile
     * data is on, or null for one on the default route (no Wi-Fi network: this phone may be the hotspot; a desktop).
     * Only this socket is bound: the rest of the app (card image downloads, DNS) keeps the default network. Any thread,
     * between {@code holdLocalNetwork(true)} and {@code (false)}. Default null.
     */
    default SocketChannel openLocalNetworkSocket() {
        return null;
    }

    Set<String> LWJGL_SUPPORTED_AUDIO_TYPES = Set.of(".wav", ".mp3", ".ogg");
    default boolean isSupportedAudioFormat(File file) {
        String path = file.getPath().toLowerCase();
        return LWJGL_SUPPORTED_AUDIO_TYPES.stream().anyMatch(path::endsWith);
    }
}
