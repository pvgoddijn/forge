package forge.gamemodes.companion;

import java.util.function.Consumer;

/**
 * A device's camera for Companion (AC-6, AC-7): photographs one card and reads its name. The Android and desktop dev
 * backends install one in {@link CardCameras} at startup; elsewhere there is none, and cards are typed.
 */
public interface CardCamera {
    /** Whether {@link #photograph} can take a photo here. */
    boolean canPhotograph();

    /**
     * Lets the operator photograph one card: the camera on a phone, a file chooser on the dev build. {@code onResult}
     * gets the photo, or null if there is none: cancelled, or the camera refused. It may be called on any thread.
     *
     * @param prompt what to photograph, such as "Ann's commander: photograph it", shown with the camera; null for none
     */
    void photograph(String prompt, Consumer<CardPhoto> onResult);

    /** Reads card names from photos, or null where this device has no reader. */
    CardNameReader reader();
}
