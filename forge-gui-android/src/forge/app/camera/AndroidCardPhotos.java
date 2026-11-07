package forge.app.camera;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.os.Build;
import android.util.Log;
import forge.cardocr.Dim;
import forge.cardocr.ImageScaling;
import forge.cardocr.OcrCardNameReader;
import forge.cardocr.PhotoDecoder;
import forge.cardocr.reader.ArgbImage;
import forge.gamemodes.companion.CardNameMatcher;
import forge.gamemodes.companion.CardNameReader;
import forge.gamemodes.companion.CardPhoto;
import forge.gamemodes.companion.CompanionPreferences;
import forge.gamemodes.companion.CompanionPreferences.CPref;
import forge.gamemodes.companion.TrainingCaptures;
import forge.gui.FThreads;
import forge.toolbox.FOptionPane;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/** Companion's card photos on Android: {@link CardCaptureActivity} takes them, and the OCR reader reads them. */
public final class AndroidCardPhotos {
    private static CardNameReader reader;
    private static TrainingCaptures training; // made on the first photo kept

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
            if (photoPath == null) {
                onResult.accept(null);
            } else if (keepPhotos()) {
                // copying a full-size photo to the external files would stall the UI thread
                new Thread(() -> onResult.accept(keepForTraining(activity, new File(photoPath), corners)),
                        "keep-photo").start();
            } else {
                // a camera photo is only for reading, so it's deleted once read (AC-7)
                onResult.accept(new CardPhoto(new File(photoPath), corners, true));
            }
        }));
    }

    /** The hidden "Keep photos for training" setting (slice 8): the one exception to AC-7. */
    private static boolean keepPhotos() {
        return CompanionPreferences.get().getPrefBoolean(CPref.KEEP_PHOTOS);
    }

    /**
     * Moves the photo to a new run under {@code getExternalFilesDir(null)/companion-captures}, where {@code adb pull}
     * reaches it without root, for the OCR benchmark. If it can't be kept, it's read and deleted as usual.
     */
    private static CardPhoto keepForTraining(Activity activity, File photo, float[] corners) {
        try {
            TrainingCaptures captures = trainingCaptures(activity);
            if (captures == null) {
                Log.w(CaptureLog.TAG, "No external files folder: the photo isn't kept");
                return new CardPhoto(photo, corners, true);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photo.getPath(), bounds);
            int orientation = new ExifInterface(photo.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);
            TrainingCaptures.Capture capture = new TrainingCaptures.Capture(photo.lastModified(),
                    Build.MANUFACTURER + " " + Build.MODEL, bounds.outWidth, bounds.outHeight, orientation, corners);
            TrainingCaptures.Run run = captures.keep(photo, capture);
            Log.d(CaptureLog.TAG, "Photo kept for training in " + run.dir());
            return new CardPhoto(run.photo(), corners, false, run);
        } catch (IOException | RuntimeException e) {
            Log.e(CaptureLog.TAG, "Could not keep the photo for training", e);
            return photo.isFile() ? new CardPhoto(photo, corners, true) : null;
        }
    }

    private static synchronized TrainingCaptures trainingCaptures(Activity activity) {
        if (training == null) {
            File external = activity.getExternalFilesDir(null);
            if (external == null) {
                return null; // shared storage isn't mounted: try again next photo
            }
            training = new TrainingCaptures(new File(external, TrainingCaptures.FOLDER));
        }
        return training;
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
