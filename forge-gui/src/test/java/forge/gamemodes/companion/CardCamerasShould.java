package forge.gamemodes.companion;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The device's camera, as the backend installs it. */
public class CardCamerasShould {
    @AfterMethod
    public void uninstall() {
        CardCameras.install(null);
    }

    @Test
    public void haveNoCameraUntilOneIsInstalled() {
        assertThat(CardCameras.get()).isNull();
    }

    @Test
    public void giveTheInstalledCamera() {
        CardCamera camera = mock(CardCamera.class);

        CardCameras.install(camera);

        assertThat(CardCameras.get()).isSameAs(camera);
    }
}
