package forge.app.camera;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import forge.cardocr.Dim;
import forge.cardocr.ImageScaling;
import forge.cardocr.OcrCardNameReader;
import forge.cardocr.PhotoDecoder;
import forge.cardocr.reader.ArgbImage;
import forge.gamemodes.companion.CardNameMatcher;
import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.CardPhoto;
import forge.gui.FThreads;
import forge.toolbox.FOptionPane;

import java.io.File;
import java.util.function.Consumer;

/** Companion's card photos on Android: {@link CardCaptureActivity} takes them, and the OCR reader reads them. */
public final class AndroidCardPhotos {
    private static CardNameReader reader;

    private AndroidCardPhotos() {
    }

    public static boolean canPhotograph(Activity activity) {
        return activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    /**
     * Opens the capture screen, showing {@code prompt} (null for none) at its top. {@code onResult} gets the photo, or
     * null if cancelled or the camera was refused.
     */
    public static void photograph(Activity activity, String prompt, Consumer<CardPhoto> onResult) {
        activity.runOnUiThread(() -> CardCaptureActivity.start(activity, prompt, (photoPath, corners, refused) -> {
            if (refused) {
                FThreads.invokeInEdtLater(() -> FOptionPane.showMessageDialog(
                        "Forge may not use the camera, so cards can't be photographed. Camera access can be given in "
                                + "the phone's settings for Forge. Type the card's name instead."));
            }
            // a camera photo is only for reading, so it's deleted once read (AC-7)
            onResult.accept(photoPath == null ? null : new CardPhoto(new File(photoPath), corners, true));
        }));
    }

    public static synchronized CardNameReader reader() {
        if (reader == null) {
            reader = new OcrCardNameReader(CardNameMatcher::cardFaceNames, AndroidCardPhotos::decode);
        }
        return reader;
    }

    /** As the camera PoC decoded: halved by Android's sample size, with EXIF left as stored, as the corners are. */
    private static PhotoDecoder.Decoded decode(File jpeg, int longSide) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(jpeg.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = ImageScaling.sampleSize(new Dim(options.outWidth, options.outHeight), longSide);
        Bitmap bitmap = BitmapFactory.decodeFile(jpeg.getPath(), options);
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        return new PhotoDecoder.Decoded(new ArgbImage(pixels, width, height), 1.0 / options.inSampleSize);
    }
}
