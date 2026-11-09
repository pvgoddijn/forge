package forge.gamemodes.companion;

/**
 * Holds the device's {@link CardCamera}, so Companion's camera needs nothing from Forge's device adapters. A backend
 * with a camera installs it at startup; on the others there is none.
 */
public final class CardCameras {
    private static volatile CardCamera camera;

    private CardCameras() {
    }

    /** Makes {@code camera0} the device's camera; null removes it. */
    public static void install(CardCamera camera0) {
        camera = camera0;
    }

    /** The device's camera, or null where there is none. */
    public static CardCamera get() {
        return camera;
    }
}
