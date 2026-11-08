package forge.interfaces;

import org.apache.commons.lang3.tuple.Pair;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
     * {@code true}: send this process's new connections over the local network (Wi-Fi), even when it has no internet
     * and mobile data is on, so a Companion join reaches the host; {@code false}: undo it. Called again with the same
     * value, it does nothing more. Any thread. Default no-op (Android binds the process to the Wi-Fi network).
     */
    default void bindToLocalNetwork(boolean bind) {
    }

    Set<String> LWJGL_SUPPORTED_AUDIO_TYPES = Set.of(".wav", ".mp3", ".ogg");
    default boolean isSupportedAudioFormat(File file) {
        String path = file.getPath().toLowerCase();
        return LWJGL_SUPPORTED_AUDIO_TYPES.stream().anyMatch(path::endsWith);
    }
}
