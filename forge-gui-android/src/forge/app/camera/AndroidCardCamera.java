package forge.app.camera;

import android.app.Activity;
import forge.gamemodes.companion.CardCamera;
import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.CardPhoto;

import java.util.function.Consumer;

/** Companion's camera on Android: {@link AndroidCardPhotos}, from {@code activity}. */
public final class AndroidCardCamera implements CardCamera {
    private final Activity activity;

    public AndroidCardCamera(Activity activity) {
        this.activity = activity;
    }

    @Override
    public boolean canPhotograph() {
        return AndroidCardPhotos.canPhotograph(activity);
    }

    @Override
    public void photograph(String prompt, Consumer<CardPhoto> onResult) {
        AndroidCardPhotos.photograph(activity, prompt, onResult);
    }

    @Override
    public CardNameReader reader() {
        return AndroidCardPhotos.reader();
    }
}
