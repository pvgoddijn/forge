package forge.interfaces;

import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.CardPhoto;
import org.apache.commons.lang3.tuple.Pair;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;
import java.util.function.Consumer;

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

    /** Whether {@link #photographCard} can take a photo here. Default false, so other backends need no changes. */
    default boolean canPhotographCards() {
        return false;
    }

    /**
     * Lets the operator photograph one card (Companion AC-6): the camera on a phone, a file chooser on the dev build.
     * {@code onResult} gets the photo, or null if there is none: cancelled, the camera refused, or not supported.
     * It may be called on any thread.
     */
    default void photographCard(Consumer<CardPhoto> onResult) {
        onResult.accept(null);
    }

    /** Reads card names from photos, or null where this backend has no reader. */
    default CardNameReader getCardNameReader() {
        return null;
    }

    Set<String> LWJGL_SUPPORTED_AUDIO_TYPES = Set.of(".wav", ".mp3", ".ogg");
    default boolean isSupportedAudioFormat(File file) {
        String path = file.getPath().toLowerCase();
        return LWJGL_SUPPORTED_AUDIO_TYPES.stream().anyMatch(path::endsWith);
    }
}
