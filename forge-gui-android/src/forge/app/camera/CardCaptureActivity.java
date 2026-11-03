package forge.app.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaActionSound;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import forge.cardocr.*;
import forge.cardocr.assist.*;
import forge.cardocr.finder.*;
import java.io.*;
import java.util.*;

/**
 * Takes a photo of one card: a full-screen preview that finds the card under the crosshair, focuses
 * and meters on it, and captures it once it's steady. The shutter works at any time. Camera work is
 * in {@link CameraController}, card finding in {@link AssistedAnalyser}. Nothing is read here.
 *
 * <p>Result contract. Start it with {@link #start(Activity, Callback)}, which also delivers the
 * result to the callback, once, on the main thread, before the activity finishes. The same result
 * is also set with {@code setResult} for a caller that uses {@code startActivityForResult}:
 * <ul>
 * <li>{@code RESULT_OK}: String extra {@value #EXTRA_PHOTO} is the absolute path of the JPEG, under
 *     {@code getCacheDir()/companion-photos/}. Float array extra {@value #EXTRA_CORNERS}, if a card was
 *     tracked, is its outline in the stored photo's pixels, before any EXIF rotation, as 8 values
 *     x0,y0..x3,y3 in the order the tracker reports them. It's left out if no card was tracked.</li>
 * <li>{@code RESULT_CANCELED}: Cancel, back, or a camera error (shown briefly first). If camera access
 *     was refused, the boolean extra {@value #EXTRA_REFUSED} is true.</li>
 * </ul>
 * The callback gets the photo's path and corners, or a null path for every cancelled outcome.
 *
 * <p>The activity must be portrait-locked: the photo's JPEG orientation assumes device rotation 0.
 */
public final class CardCaptureActivity extends Activity implements CameraController.Listener, AssistedAnalyser.Host {

    /** The photo, or null for cancelled, refused or an error. Called once, on the main thread, before finish(). */
    public interface Callback {
        void onResult(String photoPath, float[] corners, boolean refused);
    }

    public static final String EXTRA_PHOTO = "photo";
    public static final String EXTRA_CORNERS = "corners";
    public static final String EXTRA_REFUSED = "refused";
    private static final String PHOTO_DIR = "companion-photos";

    private static final int REQUEST_CAMERA = 1;
    private static final String STATE_REQUEST_IN_FLIGHT = "requestInFlight";
    /** No hold before auto capture: only a phone plainly being moved holds it back. */
    private static final long STEADY_MS = 0;
    /** Guess: from this ISO without the torch, the room counts as dark. Dim rooms ran at 10,000 and more, bright ones under 400. */
    private static final int DARK_ISO = 1600;
    /** Guess: how long a pulse stays on after the last outline, so a moment's loss doesn't flicker it. */
    private static final long TORCH_HOLD_MS = 1000;
    /** How long a camera error stays on screen before the activity gives up. */
    private static final long ERROR_SHOWN_MS = 2500;
    private static final int BANNER_DONE = 0xEE2E7D32;
    private static final float DISABLED_SHUTTER_ALPHA = 0.35f;

    /** Set by {@link #start}; a recreated activity picks up the same one. */
    private static Callback pendingCallback;

    /** The torch only ever comes on in a dark room. */
    private enum TorchMode {
        CONTINUOUS("continuous"), PULSE("short pulse"), OFF("off");

        final String label;

        TorchMode(String label) {
            this.label = label;
        }
    }

    /** What was known when a capture was triggered. */
    private record PendingCapture(CaptureTrigger trigger, AssistedPipeline.Outcome outcome,
            CameraController.AssistedGeometry geometry, AssistedAnalyser analyser) {
    }

    private Callback callback;
    private boolean resultSent;
    /** A result is on its way or the activity is giving up, so nothing new may start. */
    private boolean done;

    private TextureView preview;
    private Button shutter;
    private TextView message;

    private CameraController camera;
    private Dim streamSize;
    private int sensorOrientation;
    private boolean resumed;
    private boolean cameraStarted;
    private boolean requestInFlight;

    private OverlayView overlay;
    private TextView assistNotice;
    private TextView stats;
    private TextView glareWarning;
    private TextView captureBanner;
    private View captureFlash;
    private final MediaActionSound shutterSound = new MediaActionSound();
    private TorchMode torchMode = TorchMode.CONTINUOUS;

    private boolean assistedUnavailable;
    private AssistedAnalyser analyser;
    private volatile CameraController.AssistedGeometry geometry;
    private volatile ActiveRect crop;
    private AssistedPipeline.Outcome latestOutcome;
    private long latestFrameNs;
    private boolean captureInFlight;
    private volatile PendingCapture pending;
    private boolean roomIsDark;
    private long cardLastSeenMs;

    /** Starts the capture. The callback replaces any earlier one. */
    public static void start(Activity from, Callback callback) {
        pendingCallback = callback;
        from.startActivity(new Intent(from, CardCaptureActivity.class));
    }

    /**
     * The capture screen is {@code singleTask} in a task of its own, so a capture left in the background is brought
     * back by the next {@link #start}, not opened twice. The newer request takes over: the older one hears it was
     * cancelled, and the photo goes to whoever asked last.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Callback older = callback;
        callback = pendingCallback;
        if (older != null && older != callback) {
            older.onResult(null, null, false);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        callback = pendingCallback;
        deleteOldPhotos();
        requestInFlight = state != null && state.getBoolean(STATE_REQUEST_IN_FLIGHT);
        layOutEdgeToEdge();
        setContentView(buildUi());
        camera = new CameraController(this, this);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(STATE_REQUEST_IN_FLIGHT, requestInFlight);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (hasCameraPermission()) {
            startCameraIfPossible();
        } else if (!requestInFlight) {
            requestCameraPermission();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        cameraStarted = false;
        stopAnalyser();
        camera.stop();
        setCaptureControlsEnabled(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        camera.shutdown();
        shutterSound.release();
        if (isFinishing()) {
            // Back, or finished by the system: the caller still hears once.
            sendResult(null, null, false);
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_CAMERA) {
            return;
        }
        requestInFlight = false;
        // An empty result means the request was cancelled, which also leaves no permission.
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            done = true;
            setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_REFUSED, true));
            sendResult(null, null, true);
            finish();
        } else {
            startCameraIfPossible();
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        requestInFlight = true;
        requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    /** Calls this activity's callback at most once, and forgets it if no newer one has replaced it. */
    private void sendResult(String photoPath, float[] corners, boolean refused) {
        if (resultSent) {
            return;
        }
        resultSent = true;
        Callback target = callback;
        callback = null;
        if (pendingCallback == target) {
            pendingCallback = null;
        }
        if (target != null) {
            target.onResult(photoPath, corners, refused);
        }
    }

    private void finishWithPhoto(File photo, float[] corners) {
        if (resultSent) {
            // cancelled while the photo was being saved: nobody will read it, and photos aren't kept (AC-7)
            if (!photo.delete()) {
                Log.w(CaptureLog.TAG, "Could not delete unused photo " + photo);
            }
            return;
        }
        done = true;
        Intent data = new Intent().putExtra(EXTRA_PHOTO, photo.getAbsolutePath());
        if (corners != null) {
            data.putExtra(EXTRA_CORNERS, corners);
        }
        setResult(RESULT_OK, data);
        sendResult(photo.getAbsolutePath(), corners, false);
        finish();
    }

    /**
     * Photos are deleted once read, but one can be left behind if the app was closed before it was read. Only older
     * ones go, so a photo still being read is safe.
     */
    private void deleteOldPhotos() {
        File[] photos = new File(getCacheDir(), PHOTO_DIR).listFiles();
        if (photos == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000;
        for (File photo : photos) {
            if (photo.lastModified() < cutoff && !photo.delete()) {
                Log.w(CaptureLog.TAG, "Could not delete old photo " + photo);
            }
        }
    }

    private void cancel() {
        done = true;
        setResult(RESULT_CANCELED);
        sendResult(null, null, false);
        finish();
    }

    private void showError(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void startCameraIfPossible() {
        if (resumed && !cameraStarted && !done && preview.isAvailable() && hasCameraPermission()) {
            cameraStarted = true;
            if (!assistedUnavailable) {
                analyser = new AssistedAnalyser(this, this, null, true, STEADY_MS);
                analyser.start();
            }
            // The PoC's default settings.
            camera.setFastPhoto(true);
            camera.setYuvPhoto(true);
            camera.setCropToCard(true);
            camera.setTorch(false);
            roomIsDark = false;
            camera.start(preview.getSurfaceTexture(), analyser);
        }
    }

    private void applyPreviewTransform() {
        if (streamSize == null) {
            return;
        }
        int width = preview.getWidth();
        int height = preview.getHeight();
        PreviewTransform.Scale scale = PreviewTransform.centerCrop(streamSize, sensorOrientation, width, height);
        Matrix matrix = new Matrix();
        matrix.setScale(scale.x(), scale.y(), width / 2f, height / 2f);
        preview.setTransform(matrix);
    }

    @Override
    public void onPreviewReady(Dim size, int orientation) {
        runOnUiThread(() -> {
            streamSize = size;
            sensorOrientation = orientation;
            applyPreviewTransform();
            setCaptureControlsEnabled(resumed && !done);
        });
    }

    private void setCaptureControlsEnabled(boolean enabled) {
        shutter.setEnabled(enabled);
        // The shutter's custom background has no disabled look of its own.
        shutter.setAlpha(enabled ? 1f : DISABLED_SHUTTER_ALPHA);
    }

    private void stopAnalyser() {
        if (analyser != null) {
            analyser.stop();
            analyser = null;
        }
        geometry = null;
        crop = null;
        latestOutcome = null;
        captureInFlight = false;
        pending = null;
        overlay.clear();
        captureBanner.setVisibility(View.GONE);
        glareWarning.setVisibility(View.GONE);
        stats.setText("");
    }

    @Override
    public void onAssistedUnavailable(String reason) {
        runOnUiThread(() -> {
            // The camera is already falling back to a plain preview, so don't restart it. The shutter still works.
            assistedUnavailable = true;
            stopAnalyser();
            stats.setVisibility(View.GONE);
            assistNotice.setText("This phone can't find the card by itself. Aim at it and press the shutter.\n" + reason);
            assistNotice.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onAssistedReady(CameraController.AssistedGeometry ready) {
        runOnUiThread(() -> {
            if (analyser != null) {
                geometry = ready;
                crop = ready.crop();
            }
        });
    }

    @Override
    public void onCropChanged(ActiveRect changed) {
        runOnUiThread(() -> {
            if (analyser != null) {
                crop = changed;
            }
        });
    }

    /** Until the geometry is known nothing counts as visible, so nothing can be chosen off screen. */
    @Override
    public boolean onScreen(CardQuad quad) {
        CameraController.AssistedGeometry g = geometry;
        ActiveRect c = crop;
        int width = overlay.getWidth();
        int height = overlay.getHeight();
        if (g == null || c == null || width == 0 || height == 0) {
            return false;
        }
        ViewMapper mapper = new ViewMapper(g.preview(), g.analysis(), c, g.sensorOrientation(), g.mirrored(), width, height);
        List<Corner> corners = mapper.toView(quad);
        return OnScreen.contains(corners, width, height) && OnScreen.underCrosshair(corners, width, height);
    }

    @Override
    public FocusStatus focusStatus() {
        return camera.focusStatus();
    }

    @Override
    public Long exposureNs() {
        return camera.exposureNs();
    }

    @Override
    public void focusOnCard(CardQuad quad, Dim frame, int generation) {
        camera.focusOnCard(quad, frame, generation);
    }

    @Override
    public void releaseFocus() {
        camera.releaseFocus();
    }

    @Override
    public void onFrameAnalysed(AssistedAnalyser from, AssistedPipeline.Outcome outcome, Dim frame, long dropped, long frameNs) {
        runOnUiThread(() -> {
            if (from == analyser) {
                latestFrameNs = frameNs;
                showOutcome(outcome);
            }
        });
    }

    private void showOutcome(AssistedPipeline.Outcome outcome) {
        latestOutcome = outcome;
        String status = StatusText.main(outcome.card() != null, outcome.latched(), captureInFlight, true);
        if (!status.contentEquals(stats.getText())) {
            stats.setText(status);
        }
        glareWarning.setVisibility(outcome.glareNow() ? View.VISIBLE : View.GONE);
        updateTorch(outcome);
        redrawOverlay();
    }

    /** Continuous lights the room for focus and the finder, while a pulse only lights a card until its photo is taken. */
    private void updateTorch(AssistedPipeline.Outcome outcome) {
        Integer iso = camera.iso();
        // Only without the torch: with it, the ISO says nothing about the room.
        if (!camera.torchOn() && iso != null && iso >= DARK_ISO) {
            roomIsDark = true;
        }
        if (outcome.shown() != null) {
            cardLastSeenMs = SystemClock.elapsedRealtime();
        }
        boolean want = roomIsDark && switch (torchMode) {
            case OFF -> false;
            case CONTINUOUS -> true;
            case PULSE -> !captureInFlight && SystemClock.elapsedRealtime() - cardLastSeenMs < TORCH_HOLD_MS;
        };
        if (want != camera.torchOn()) {
            Log.d(CaptureLog.TAG, "Torch " + (want ? "on" : "off"));
            camera.setTorch(want);
        }
    }

    /** Draws the latest result moved to the preview frame on screen, so it follows the phone between results. */
    private void redrawOverlay() {
        AssistedPipeline.Outcome outcome = latestOutcome;
        if (outcome == null || analyser == null || geometry == null || crop == null || !preview.isAvailable()) {
            return;
        }
        ImageMotion motion = analyser.overlayMotion(latestFrameNs, preview.getSurfaceTexture().getTimestamp());
        OutlineDisplay.Shown shown = outcome.shown();
        ViewMapper mapper = new ViewMapper(geometry.preview(), geometry.analysis(), crop, geometry.sensorOrientation(),
                geometry.mirrored(), overlay.getWidth(), overlay.getHeight());
        overlay.show(shown == null ? null : mapper.toView(OverlayCompensation.apply(shown.quad(), motion)),
                shown != null && shown.confirmed(), outcome.focusSettled());
    }

    @Override
    public void onAutoCapture(AssistedAnalyser from) {
        runOnUiThread(() -> {
            if (from != analyser) {
                return;
            }
            if (done || captureInFlight || !shutter.isEnabled()) {
                if (!captureInFlight) {
                    from.onCaptureEnded(true);
                }
                return;
            }
            startAssistedCapture(CaptureTrigger.AUTO);
        });
    }

    private void onShutterPressed() {
        if (done) {
            return;
        }
        setCaptureControlsEnabled(false);
        if (analyser != null) {
            analyser.onCaptureStarted();
            startAssistedCapture(CaptureTrigger.SHUTTER);
        } else {
            camera.takePhoto();
        }
    }

    private void startAssistedCapture(CaptureTrigger trigger) {
        setCaptureControlsEnabled(false);
        captureInFlight = true;
        pending = new PendingCapture(trigger, latestOutcome, geometry == null || crop == null ? null : withCrop(geometry, crop), analyser);
        stats.setText("Capturing");
        Log.d(CaptureLog.TAG, "Capture triggered: " + trigger.id());
        CardQuad card = latestOutcome == null || latestOutcome.card() == null ? null : latestOutcome.card().quad();
        camera.takeAssistedPhoto(card);
    }

    private static CameraController.AssistedGeometry withCrop(CameraController.AssistedGeometry g, ActiveRect current) {
        return new CameraController.AssistedGeometry(g.preview(), g.analysis(), g.jpeg(), g.activeArray(), current, g.sensorOrientation(), g.mirrored());
    }

    @Override
    public void onStillTaken(byte[] jpeg, long captureTimeMs, long exposureNs, boolean oisOn, StillFrame frame, long jpegMs, ActiveRect photoCrop) {
        PendingCapture capture = pending;
        if (capture != null && capture.analyser() != null) {
            capture.analyser().pauseWhileSaving();
            runOnUiThread(this::signalPhotoTaken);
        }
        // Decoding and writing a full-size photo would stall the camera thread.
        new Thread(() -> {
            try {
                saveStill(jpeg, captureTimeMs, capture, photoCrop);
            } catch (RuntimeException e) {
                Log.e(CaptureLog.TAG, "Could not save the photo", e);
                onCameraError("Could not save the photo: " + e.getMessage());
            }
        }, "save-photo").start();
    }

    private void saveStill(byte[] jpeg, long captureTimeMs, PendingCapture capture, ActiveRect photoCrop) {
        AssistedPipeline.Outcome outcome = capture == null ? null : capture.outcome();
        TrackedCard card = outcome == null ? null : outcome.card();
        CameraController.AssistedGeometry geo = capture == null ? null : capture.geometry();
        File photo = writePhoto(jpeg);
        if (camera.yuvInUse()) {
            markSideways(photo);
        }
        CardQuad trackedInPhoto = card == null || geo == null ? null : cardInPhoto(jpeg, card.quad(), geo, photoCrop);
        float[] corners = trackedInPhoto == null ? null : toArray(trackedInPhoto);
        Log.d(CaptureLog.TAG, "Photo saved: trigger=" + (capture == null ? CaptureTrigger.SHUTTER : capture.trigger()).id()
                + " captureMs=" + captureTimeMs + " tracked=" + (corners != null) + " photoCrop=" + photoCrop + " yuv=" + camera.yuvInUse());
        runOnUiThread(() -> finishWithPhoto(photo, corners));
    }

    private static float[] toArray(CardQuad quad) {
        float[] out = new float[8];
        List<Corner> corners = quad.corners();
        for (int i = 0; i < 4; i++) {
            out[2 * i] = (float) corners.get(i).x();
            out[2 * i + 1] = (float) corners.get(i).y();
        }
        return out;
    }

    /** The photo exists, so aiming can stop: seen, heard and felt, as the eyes may already be on the table. */
    private void signalPhotoTaken() {
        if (torchMode == TorchMode.PULSE) {
            camera.setTorch(false);
        }
        stats.setText("Saving");
        captureBanner.setText("Got it. You can stop aiming");
        captureBanner.setBackgroundColor(BANNER_DONE);
        captureBanner.setVisibility(View.VISIBLE);
        captureFlash.setAlpha(0.8f);
        captureFlash.setVisibility(View.VISIBLE);
        captureFlash.animate().alpha(0f).setDuration(250).withEndAction(() -> captureFlash.setVisibility(View.GONE)).start();
        shutterSound.play(MediaActionSound.SHUTTER_CLICK);
        captureBanner.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private File writePhoto(byte[] jpeg) {
        File dir = new File(getCacheDir(), PHOTO_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new UncheckedIOException(new IOException("Could not create " + dir));
        }
        File photo = new File(dir, "card-" + System.currentTimeMillis() + ".jpg");
        try (OutputStream out = new FileOutputStream(photo)) {
            out.write(jpeg);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return photo;
    }

    /** A YUV photo is encoded by the app with the sensor's pixels, so it needs the tag the camera's JPEG gets. */
    private static void markSideways(File photo) {
        try {
            ExifInterface exif = new ExifInterface(photo.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
            exif.saveAttributes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The tracked outline's place in the stored pixels. */
    private static CardQuad cardInPhoto(byte[] jpeg, CardQuad quad, CameraController.AssistedGeometry geo, ActiveRect photoCrop) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        Dim stored = new Dim(bounds.outWidth, bounds.outHeight);
        int exifDegrees = ImageScaling.rotationDegrees(readExifOrientation(jpeg));
        int rotation = StillMapper.pixelRotation(geo.sensorOrientation(), exifDegrees, geo.jpeg(), stored);
        return photoCrop != null
                ? StillMapper.toStoredCropped(quad, geo.analysis(), geo.crop(), photoCrop, rotation, stored)
                : StillMapper.toStored(quad, geo.analysis(), geo.jpeg(), geo.crop(), rotation, stored);
    }

    private static int readExifOrientation(byte[] jpeg) {
        try (InputStream in = new ByteArrayInputStream(jpeg)) {
            return new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Only when card finding isn't available here: a plain photo, without an outline. Runs on the camera thread. */
    @Override
    public void onPhotoTaken(byte[] jpeg, long captureTimeMs) {
        try {
            File photo = writePhoto(jpeg);
            Log.d(CaptureLog.TAG, "Plain photo saved: captureMs=" + captureTimeMs);
            runOnUiThread(() -> finishWithPhoto(photo, null));
        } catch (RuntimeException e) {
            onCameraError("Could not save the photo: " + e.getMessage());
        }
    }

    @Override
    public void onCameraError(String text) {
        Log.e(CaptureLog.TAG, "Camera error: " + text);
        runOnUiThread(() -> {
            if (done) {
                return;
            }
            done = true;
            showError(text);
            setCaptureControlsEnabled(false);
            stopAnalyser();
            message.postDelayed(this::cancel, ERROR_SHOWN_MS);
        });
    }

    @SuppressWarnings("deprecation") // The replacements need API 30; minSdk is 26.
    private void layOutEdgeToEdge() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
    }

    @SuppressWarnings("deprecation") // getSystemWindowInset* replacements need API 30; minSdk is 26.
    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                startCameraIfPossible();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                applyPreviewTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                stopAnalyser();
                camera.stop();
                cameraStarted = false;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                redrawOverlay();
            }
        });
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        overlay = new OverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildCrosshair(), new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));

        FrameLayout controls = new FrameLayout(this);
        controls.addView(buildTopPanel(), new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        shutter = new Button(this);
        shutter.setContentDescription("Shutter");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(4), Color.GRAY);
        shutter.setBackground(circle);
        shutter.setEnabled(false);
        shutter.setAlpha(DISABLED_SHUTTER_ALPHA);
        shutter.setOnClickListener(v -> onShutterPressed());
        FrameLayout.LayoutParams shutterParams = new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        shutterParams.bottomMargin = dp(24);
        controls.addView(shutter, shutterParams);

        Button cancelButton = new Button(this);
        cancelButton.setAllCaps(false);
        cancelButton.setText("Cancel");
        cancelButton.setOnClickListener(v -> cancel());
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        cancelParams.bottomMargin = dp(36);
        cancelParams.leftMargin = dp(16);
        controls.addView(cancelButton, cancelParams);

        Button torchButton = new Button(this);
        torchButton.setAllCaps(false);
        torchButton.setText("Torch: " + torchMode.label);
        torchButton.setOnClickListener(v -> {
            torchMode = TorchMode.values()[(torchMode.ordinal() + 1) % TorchMode.values().length];
            torchButton.setText("Torch: " + torchMode.label);
        });
        FrameLayout.LayoutParams torchParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        torchParams.bottomMargin = dp(36);
        torchParams.rightMargin = dp(16);
        controls.addView(torchButton, torchParams);

        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(18);
        message.setGravity(Gravity.CENTER);
        message.setBackgroundColor(0xCC000000);
        message.setPadding(dp(16), dp(16), dp(16), dp(16));
        message.setVisibility(View.GONE);
        FrameLayout.LayoutParams messageParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        messageParams.leftMargin = dp(16);
        messageParams.rightMargin = dp(16);
        controls.addView(message, messageParams);

        controls.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        captureFlash = new View(this);
        captureFlash.setBackgroundColor(Color.WHITE);
        captureFlash.setVisibility(View.GONE);
        root.addView(captureFlash, new FrameLayout.LayoutParams(-1, -1));

        captureBanner = new TextView(this);
        captureBanner.setTextColor(Color.WHITE);
        captureBanner.setTextSize(26);
        captureBanner.setGravity(Gravity.CENTER);
        captureBanner.setPadding(dp(16), dp(24), dp(16), dp(24));
        captureBanner.setVisibility(View.GONE);
        // High up, to leave the crosshair and the card in view.
        FrameLayout.LayoutParams bannerParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        bannerParams.topMargin = dp(150);
        root.addView(captureBanner, bannerParams);
        return root;
    }

    /** White with a dark edge, to show on light and dark cards alike. */
    private FrameLayout buildCrosshair() {
        FrameLayout cross = new FrameLayout(this);
        for (int[] bar : new int[][] {{dp(48), dp(4), Color.BLACK}, {dp(4), dp(48), Color.BLACK}, {dp(46), dp(2), Color.WHITE}, {dp(2), dp(46), Color.WHITE}}) {
            View line = new View(this);
            line.setBackgroundColor(bar[2]);
            cross.addView(line, new FrameLayout.LayoutParams(bar[0], bar[1], Gravity.CENTER));
        }
        return cross;
    }

    private View buildTopPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        stats = new TextView(this);
        stats.setTextColor(Color.WHITE);
        stats.setBackgroundColor(0x88000000);
        stats.setPadding(dp(8), dp(4), dp(8), dp(4));
        stats.setTextSize(18);
        panel.addView(stats);

        glareWarning = new TextView(this);
        glareWarning.setText("Glare on the title. Tilt the card or phone.");
        glareWarning.setTextColor(Color.WHITE);
        glareWarning.setTextSize(18);
        glareWarning.setBackgroundColor(0xCCD32F2F);
        glareWarning.setPadding(dp(12), dp(8), dp(12), dp(8));
        glareWarning.setVisibility(View.GONE);
        panel.addView(glareWarning);

        assistNotice = new TextView(this);
        assistNotice.setTextColor(Color.WHITE);
        assistNotice.setBackgroundColor(0xCC000000);
        assistNotice.setPadding(dp(12), dp(8), dp(12), dp(8));
        assistNotice.setVisibility(View.GONE);
        panel.addView(assistNotice);
        return panel;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
