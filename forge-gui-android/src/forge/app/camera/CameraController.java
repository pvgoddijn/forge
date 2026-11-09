package forge.app.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.os.*;
import android.util.*;
import android.view.Surface;
import forge.cardocr.*;
import forge.cardocr.assist.*;
import forge.cardocr.finder.*;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Owns the Camera2 preview and still capture. All camera state lives on one background thread, so
 * nothing here needs locks. start, stop and takePhoto can be called from the main thread.
 *
 * <p>Every open bumps {@code generation}. Callbacks from an older generation are ignored, and a
 * device that opens after stop is closed straight away, which covers quick pause and resume.
 */
public final class CameraController {

    /** Called on the camera thread. Implementations hop to the main thread for UI work. */
    public interface Listener {
        void onPreviewReady(Dim streamSize, int sensorOrientation);

        void onPhotoTaken(byte[] jpeg, long captureTimeMs);

        void onCameraError(String message);

        /** The session has an analysis stream. Sizes and sensor geometry for mapping its coordinates. */
        void onAssistedReady(AssistedGeometry geometry);

        /** Assisted mode can't run here. The camera carries on in Plain mode. */
        void onAssistedUnavailable(String reason);

        /** SCALER_CROP_REGION differs from what the geometry last said. */
        void onCropChanged(ActiveRect crop);

        /**
         * An Assisted photo was taken.
         *
         * @param exposureNs when its exposure started on the elapsedRealtimeNanos clock, or 0
         * @param oisOn whether the camera reported optical stabilisation on for it
         * @param frame what the camera reported, or null if the result never came
         * @param jpegMs when the photo arrived, on the elapsedRealtime clock
         * @param photoCrop the camera crop it was taken with, or null for the whole sensor
         */
        void onStillTaken(byte[] jpeg, long captureTimeMs, long exposureNs, boolean oisOn, StillFrame frame, long jpegMs, ActiveRect photoCrop);
    }

    /** What's needed to map between an analysis frame and the other streams. */
    public record AssistedGeometry(Dim preview, Dim analysis, Dim jpeg, Dim activeArray, ActiveRect crop,
            int sensorOrientation, boolean mirrored) {
    }

    private interface CameraCall {
        void run() throws CameraAccessException;
    }

    private static final Dim MAX_PREVIEW = new Dim(1920, 1080);
    private static final long CAPTURE_WATCHDOG_MS = 10_000;
    /** About the size the reader decodes a JPEG to. */
    private static final int YUV_MAX_WIDTH = 2048;
    private static final byte JPEG_QUALITY = 95;
    /** Guess: how long after the JPEG to wait for the camera's result, which can come later. */
    private static final long RESULT_GRACE_MS = 500;

    private final CameraManager manager;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("camera");
    private final Handler handler;

    private int generation;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private ImageReader jpegReader;
    private ImageReader analysisReader;
    private AssistedGeometry assistedGeometry;
    private FrameAnalyser analyser;
    private SurfaceTexture texture;
    private int sensorOrientation;
    private int afMode;
    private boolean oisSupported;
    private boolean flashAvailable;
    private volatile boolean torchOn;
    private volatile boolean fastPhoto;
    private volatile boolean yuvPhoto;
    private volatile boolean cropToCard;
    private volatile boolean yuvInUse;
    private CardQuad cropCard;
    private ActiveRect stillCrop;

    private boolean capturing;
    private long shutterNanos;
    private CaptureSequence sequence;
    private final Runnable captureWatchdog = this::onCaptureTimedOut;
    private final Runnable resultLate = this::finishStill;

    // Assisted mode only.
    private boolean assistedStill;
    private byte[] stillJpeg;
    private StillFrame stillFrame;
    private Dim analysisSize;
    private Dim activeArray;
    private ActiveRect crop;
    private int maxAfRegions;
    private int maxAeRegions;
    private MeteringRectangle[] afRegions;
    private MeteringRectangle[] aeRegions;
    private final FocusSettler focusSettler = new FocusSettler();
    private volatile FocusStatus focusStatus = FocusStatus.NONE;
    private Float focalMm;
    private Double physicalWidthMm;
    private Double physicalHeightMm;
    private Dim pixelArray;
    private boolean frontFacing;
    private boolean realtimeClock;
    private long stillExposureNs;
    private boolean stillOisOn;
    private long stillJpegNanos;
    private long stillJpegMs;
    private volatile Long latestExposureNs;
    private volatile Integer latestIso;

    public CameraController(Context context, Listener listener) {
        this.manager = context.getSystemService(CameraManager.class);
        this.listener = listener;
        thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    /** @param frameAnalyser live frame analyser for Assisted mode; null for Plain mode */
    public void start(SurfaceTexture surfaceTexture, FrameAnalyser frameAnalyser) {
        handler.post(() -> open(surfaceTexture, frameAnalyser));
    }

    public void stop() {
        handler.post(this::close);
    }

    /** Stops for good. The controller can't be used afterwards. */
    public void shutdown() {
        stop();
        thread.quitSafely();
    }

    /** Starts one capture. Ignored if the camera isn't ready or a capture is running. */
    public void takePhoto() {
        long pressedAt = System.nanoTime();
        handler.post(() -> beginCapture(pressedAt, false));
    }

    /** Starts an Assisted capture of one photo. Ignored like {@link #takePhoto()}. */
    public void takeAssistedPhoto(CardQuad card) {
        long pressedAt = System.nanoTime();
        handler.post(() -> {
            cropCard = card;
            beginCapture(pressedAt, true);
        });
    }

    /** Whether Assisted photos use fast noise reduction and edge processing rather than high quality. */
    public void setFastPhoto(boolean fast) {
        fastPhoto = fast;
    }

    /** Takes effect the next time the camera opens. */
    public void setYuvPhoto(boolean yuv) {
        yuvPhoto = yuv;
    }

    /** Whether Assisted photos are cropped by the camera to the card. */
    public void setCropToCard(boolean crop) {
        cropToCard = crop;
    }

    /** Safe from any thread. Whether the session's photos are YUV. */
    public boolean yuvInUse() {
        return yuvInUse;
    }

    /** Safe from any thread. */
    public boolean torchOn() {
        return torchOn;
    }

    /** Keeps the flash on as a steady light while the camera runs, if the phone has one. */
    public void setTorch(boolean on) {
        torchOn = on;
        handler.post(() -> {
            if (session != null && !capturing) {
                guarded("change the torch", () -> session.setRepeatingRequest(previewRequest(null).build(), previewCallback, handler));
            }
        });
    }

    /** Safe from any thread. Tells how the latest {@link #focusOnCard} is going. */
    public FocusStatus focusStatus() {
        return focusStatus;
    }

    /** Safe from any thread. The latest preview frame's ISO, or null if not known. */
    public Integer iso() {
        return latestIso;
    }

    /** Safe from any thread. The latest preview frame's exposure, or null if not known. */
    public Long exposureNs() {
        return latestExposureNs;
    }

    /** Meters and focuses on a card given in analysis-frame pixels, and starts a focus scan. */
    public void focusOnCard(CardQuad quad, Dim frame, int focusGeneration) {
        handler.post(() -> applyCardFocus(quad, frame, focusGeneration));
    }

    /** Goes back to metering the whole scene. */
    public void releaseFocus() {
        handler.post(this::applyFocusRelease);
    }

    // CardCaptureActivity checks the permission first. If it's revoked anyway, the SecurityException is reported below.
    @SuppressLint("MissingPermission")
    private void open(SurfaceTexture surfaceTexture, FrameAnalyser frameAnalyser) {
        close();
        int openGeneration = ++generation;
        texture = surfaceTexture;
        analyser = frameAnalyser;
        try {
            String cameraId = pickCamera();
            if (cameraId == null) {
                listener.onCameraError("This device has no camera.");
                return;
            }
            CameraCharacteristics traits = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap streams = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            sensorOrientation = traits.get(CameraCharacteristics.SENSOR_ORIENTATION);
            afMode = pickAfMode(traits);
            flashAvailable = Boolean.TRUE.equals(traits.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            oisSupported = HardwareSupport.hasOpticalStabilization(traits.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));

            String unsupported = analyser == null ? null : assistedProblem(traits, streams);
            if (unsupported != null) {
                Log.d(CaptureLog.TAG, "Assisted unavailable: " + unsupported);
                analyser = null;
                listener.onAssistedUnavailable(unsupported);
            }

            Dim jpegSize = StreamSizes.largest(toDims(streams.getOutputSizes(ImageFormat.JPEG)));
            Dim previewSize = StreamSizes.select(toDims(streams.getOutputSizes(SurfaceTexture.class)), jpegSize, MAX_PREVIEW);
            texture.setDefaultBufferSize(previewSize.width(), previewSize.height());
            previewSurface = new Surface(texture);

            Dim yuvSize = analyser != null && yuvPhoto ? yuvStillSize(streams) : null;
            yuvInUse = yuvSize != null;
            jpegReader = yuvSize != null
                    ? ImageReader.newInstance(yuvSize.width(), yuvSize.height(), ImageFormat.YUV_420_888, 2)
                    : ImageReader.newInstance(jpegSize.width(), jpegSize.height(), ImageFormat.JPEG, 2);
            Log.d(CaptureLog.TAG, "Photo stream: " + (yuvSize != null ? "YUV " + yuvSize : "JPEG " + jpegSize));
            jpegReader.setOnImageAvailableListener(this::onJpegAvailable, handler);

            if (analyser != null) {
                Dim frameSize = StreamSizes.select(toDims(streams.getOutputSizes(ImageFormat.YUV_420_888)), jpegSize, analyser.maxFrameSize());
                analysisReader = ImageReader.newInstance(frameSize.width(), frameSize.height(), ImageFormat.YUV_420_888, 2);
                analysisReader.setOnImageAvailableListener(this::onFrameAvailable, handler);
                analysisSize = frameSize;
                prepareAssisted(traits, previewSize, frameSize, jpegSize);
            }

            manager.openCamera(cameraId, deviceCallback(openGeneration, previewSize), handler);
        } catch (CameraAccessException | RuntimeException e) {
            reportError("Could not open the camera: " + e.getMessage());
        }
    }

    /** The largest 4:3 YUV size up to about what the reader uses, or null. */
    private static Dim yuvStillSize(StreamConfigurationMap streams) {
        Dim best = null;
        for (Size size : streams.getOutputSizes(ImageFormat.YUV_420_888)) {
            boolean fourByThree = size.getWidth() * 3 == size.getHeight() * 4;
            if (fourByThree && size.getWidth() <= YUV_MAX_WIDTH && (best == null || size.getWidth() > best.width())) {
                best = new Dim(size.getWidth(), size.getHeight());
            }
        }
        return best;
    }

    /** Why this camera can't run Assisted mode, or null if it can. */
    private static String assistedProblem(CameraCharacteristics traits, StreamConfigurationMap streams) {
        Integer level = traits.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (!HardwareSupport.guaranteesPreviewYuvJpeg(level)) {
            return "This camera's hardware level (" + level + ") doesn't guarantee preview, analysis and full-size photos together.";
        }
        Size[] yuv = streams.getOutputSizes(ImageFormat.YUV_420_888);
        return yuv == null || yuv.length == 0 ? "This camera offers no YUV stream." : null;
    }

    private void prepareAssisted(CameraCharacteristics traits, Dim previewSize, Dim frameSize, Dim jpegSize) {
        Rect active = traits.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        activeArray = new Dim(active.width(), active.height());
        crop = new ActiveRect(0, 0, active.width(), active.height());
        maxAfRegions = orZero(traits.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF));
        maxAeRegions = orZero(traits.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE));
        Integer facing = traits.get(CameraCharacteristics.LENS_FACING);
        assistedGeometry = new AssistedGeometry(previewSize, frameSize, jpegSize, activeArray, crop, sensorOrientation,
                facing != null && facing == CameraMetadata.LENS_FACING_FRONT);
        frontFacing = assistedGeometry.mirrored();
        Log.d(CaptureLog.TAG, "Assisted streams: " + assistedGeometry + " maxRegionsAf=" + maxAfRegions + " maxRegionsAe=" + maxAeRegions
                + " afMode=" + afMode + " ois=" + oisSupported);
        prepareOptics(traits);
    }

    private void prepareOptics(CameraCharacteristics traits) {
        float[] focalLengths = traits.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        focalMm = focalLengths == null || focalLengths.length == 0 ? null : (Float) focalLengths[0];
        SizeF physical = traits.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        physicalWidthMm = physical == null ? null : (Double) (double) physical.getWidth();
        physicalHeightMm = physical == null ? null : (Double) (double) physical.getHeight();
        Size pixels = traits.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        pixelArray = pixels == null ? null : new Dim(pixels.getWidth(), pixels.getHeight());
        Integer source = traits.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        realtimeClock = source != null && source == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        Log.d(CaptureLog.TAG, "Optics: focalMm=" + focalMm + " physical=" + physical + " pixelArray=" + pixelArray
                + " timestampSource=" + source + (realtimeClock ? "" : " (not REALTIME: using arrival times)"));
        publishOptics();
    }

    private void publishOptics() {
        GyroMapping mapping = GyroMapping.of(focalMm, physicalWidthMm, physicalHeightMm, pixelArray,
                sensorOrientation, frontFacing, analysisSize, crop);
        Log.d(CaptureLog.TAG, "Gyro mapping: " + mapping);
        analyser.onOptics(mapping, realtimeClock);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String pickCamera() throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) {
                return id;
            }
        }
        return ids.length > 0 ? ids[0] : null;
    }

    private static int pickAfMode(CameraCharacteristics traits) {
        int[] modes = traits.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        boolean hasAuto = false;
        for (int mode : modes) {
            if (mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                return mode;
            }
            hasAuto |= mode == CameraMetadata.CONTROL_AF_MODE_AUTO;
        }
        return hasAuto ? CameraMetadata.CONTROL_AF_MODE_AUTO : CameraMetadata.CONTROL_AF_MODE_OFF;
    }

    private static List<Dim> toDims(Size[] sizes) {
        List<Dim> dims = new ArrayList<>();
        for (Size size : sizes) {
            dims.add(new Dim(size.getWidth(), size.getHeight()));
        }
        return dims;
    }

    private CameraDevice.StateCallback deviceCallback(int openGeneration, Dim previewSize) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice opened) {
                if (openGeneration != generation) {
                    opened.close();
                    return;
                }
                device = opened;
                createSession(openGeneration, previewSize);
            }

            @Override
            public void onDisconnected(CameraDevice lost) {
                lost.close();
                if (openGeneration == generation) {
                    reportError("The camera was disconnected, possibly taken by another app.");
                }
            }

            @Override
            public void onError(CameraDevice failed, int error) {
                failed.close();
                if (openGeneration == generation) {
                    reportError("Camera error " + error + ".");
                }
            }
        };
    }

    // The SessionConfiguration replacement needs API 28; minSdk is 26.
    @SuppressWarnings("deprecation")
    private void createSession(int openGeneration, Dim previewSize) {
        List<Surface> targets = new ArrayList<>(List.of(previewSurface, jpegReader.getSurface()));
        if (analysisReader != null) {
            targets.add(analysisReader.getSurface());
        }
        guarded("start the preview", () -> device.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession configured) {
                if (openGeneration != generation) {
                    configured.close();
                    return;
                }
                session = configured;
                guarded("start the preview", () -> {
                    session.setRepeatingRequest(previewRequest(null).build(), previewCallback, handler);
                    listener.onPreviewReady(previewSize, sensorOrientation);
                    if (analyser != null) {
                        listener.onAssistedReady(assistedGeometry);
                    }
                });
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession failed) {
                if (openGeneration != generation) {
                    return;
                }
                if (analyser != null) {
                    // The hardware level promised this combination, but this device refused it.
                    Log.d(CaptureLog.TAG, "Session with analysis stream refused");
                    listener.onAssistedUnavailable("This camera refused preview, analysis and full-size photos together.");
                    open(texture, null);
                } else {
                    reportError("The camera rejected the preview configuration.");
                }
            }
        }, handler));
    }

    private CaptureRequest.Builder previewRequest(Integer afTrigger) throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        if (analysisReader != null) {
            builder.addTarget(analysisReader.getSurface());
        }
        applyAutoControls(builder);
        if (afTrigger != null) {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger);
        }
        return builder;
    }

    private void applyAutoControls(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        if (oisSupported) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
        builder.set(CaptureRequest.FLASH_MODE, torchOn && flashAvailable ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
        if (afRegions != null) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
        }
        if (aeRegions != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions);
        }
    }

    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (s != session) {
                return;
            }
            if (analyser != null) {
                followAssistedResult(result);
            }
            if (sequence == null) {
                return;
            }
            CaptureSequence.Action action = sequence.onResult(
                    result.get(CaptureResult.CONTROL_AF_STATE),
                    result.get(CaptureResult.CONTROL_AE_STATE),
                    SystemClock.elapsedRealtime());
            switch (action) {
                case TRIGGER_PRECAPTURE -> guardedCapture(() -> {
                    CaptureRequest.Builder builder = previewRequest(null);
                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                    session.capture(builder.build(), null, handler);
                });
                case TAKE_PHOTO -> guardedCapture(CameraController.this::sendStillRequest);
                case NONE -> {
                }
            }
        }
    };

    private void followAssistedResult(TotalCaptureResult result) {
        focusSettler.onAfState(result.get(CaptureResult.CONTROL_AF_STATE), SystemClock.elapsedRealtime());
        publishFocusStatus();
        latestExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        latestIso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Rect current = result.get(CaptureResult.SCALER_CROP_REGION);
        if (current != null) {
            ActiveRect latest = new ActiveRect(current.left, current.top, current.width(), current.height());
            if (!latest.equals(crop)) {
                crop = latest;
                publishOptics();
                listener.onCropChanged(latest);
            }
        }
    }

    private void publishFocusStatus() {
        FocusStatus latest = focusSettler.status();
        if (latest != focusStatus) {
            focusStatus = latest;
            if (latest.settled()) {
                Log.d(CaptureLog.TAG, "Focus " + latest.generation() + " " + latest.outcome() + " after " + latest.settleMs() + " ms");
            }
        }
    }

    private void applyCardFocus(CardQuad quad, Dim frame, int focusGeneration) {
        if (session == null || analyser == null) {
            return;
        }
        if (capturing) {
            // The capture does its own focusing. Answer anyway, so this request isn't left pending.
            focusSettler.begin(focusGeneration, false, SystemClock.elapsedRealtime());
            publishFocusStatus();
            return;
        }
        MeteringRegions.Region region = MeteringRegions.forCard(quad, frame, crop, activeArray);
        MeteringRectangle[] rectangles = region == null ? null : new MeteringRectangle[] {
                new MeteringRectangle(region.x(), region.y(), region.width(), region.height(), MeteringRectangle.METERING_WEIGHT_MAX)};
        afRegions = maxAfRegions > 0 ? rectangles : null;
        aeRegions = maxAeRegions > 0 ? rectangles : null;
        boolean hasAutofocus = afMode != CameraMetadata.CONTROL_AF_MODE_OFF;
        focusSettler.begin(focusGeneration, hasAutofocus, SystemClock.elapsedRealtime());
        publishFocusStatus();
        guarded("set the focus area", () -> {
            if (hasAutofocus) {
                // A held lock ignores new regions until cancelled.
                session.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL).build(), null, handler);
            }
            session.setRepeatingRequest(previewRequest(null).build(), previewCallback, handler);
            if (hasAutofocus) {
                session.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_START).build(), focusTriggerCallback, handler);
            }
        });
    }

    private final CameraCaptureSession.CaptureCallback focusTriggerCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (s == session) {
                focusSettler.onTriggerApplied();
                focusSettler.onAfState(result.get(CaptureResult.CONTROL_AF_STATE), SystemClock.elapsedRealtime());
                publishFocusStatus();
            }
        }
    };

    private void applyFocusRelease() {
        if (session == null || capturing || analyser == null) {
            return;
        }
        afRegions = null;
        aeRegions = null;
        focusSettler.lockCancelled();
        guarded("release the focus area", () -> {
            if (afMode != CameraMetadata.CONTROL_AF_MODE_OFF) {
                session.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL).build(), null, handler);
            }
            session.setRepeatingRequest(previewRequest(null).build(), previewCallback, handler);
        });
    }

    private void beginCapture(long pressedAt, boolean assisted) {
        if (session == null || capturing) {
            return;
        }
        capturing = true;
        shutterNanos = pressedAt;
        assistedStill = assisted && analyser != null;
        stillJpeg = null;
        stillFrame = null;
        stillCrop = cropToCard && assistedStill && cropCard != null && activeArray != null && analysisSize != null && crop != null
                ? CardZoom.region(cropCard, analysisSize, crop, activeArray) : null;
        stillExposureNs = 0;
        stillOisOn = false;
        handler.postDelayed(captureWatchdog, CAPTURE_WATCHDOG_MS);
        boolean hasAutofocus = afMode != CameraMetadata.CONTROL_AF_MODE_OFF;
        sequence = new CaptureSequence(hasAutofocus, SystemClock.elapsedRealtime());
        boolean reuseLock = analyser != null && focusSettler.lockStillGood(SystemClock.elapsedRealtime());
        Log.d(CaptureLog.TAG, "Capture focus: " + (reuseLock ? "using the current lock" : "scanning"));
        if (hasAutofocus && !reuseLock) {
            guardedCapture(() -> session.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_START).build(), null, handler));
        }
    }

    private void sendStillRequest() throws CameraAccessException {
        sequence = null;
        CaptureRequest.Builder still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        still.addTarget(jpegReader.getSurface());
        still.addTarget(previewSurface);
        applyAutoControls(still);
        // The activity is portrait-locked, so the device rotation is always 0.
        still.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
        still.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY);
        if (stillCrop != null) {
            still.set(CaptureRequest.SCALER_CROP_REGION, new Rect((int) stillCrop.left(), (int) stillCrop.top(),
                    (int) (stillCrop.left() + stillCrop.width()), (int) (stillCrop.top() + stillCrop.height())));
        }
        if (assistedStill && fastPhoto) {
            still.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST);
            still.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST);
        }
        session.capture(still.build(), assistedStill ? assistedStillCallback : new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                if (s == session && afMode != CameraMetadata.CONTROL_AF_MODE_OFF) {
                    guarded("resume autofocus", () -> session.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL).build(), null, handler));
                }
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
                if (s == session) {
                    failCapture("The camera failed to take the photo (reason " + failure.getReason() + ").");
                }
            }
        }, handler);
    }

    private final CameraCaptureSession.CaptureCallback assistedStillCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession s, CaptureRequest request, long timestamp, long frameNumber) {
            stillExposureNs = realtimeClock ? timestamp : SystemClock.elapsedRealtimeNanos();
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (s != session || !assistedStill) {
                return;
            }
            Integer ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
            stillOisOn = ois != null && ois == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON;
            Rect usedCrop = result.get(CaptureResult.SCALER_CROP_REGION);
            if (stillCrop != null && usedCrop != null) {
                stillCrop = new ActiveRect(usedCrop.left, usedCrop.top, usedCrop.width(), usedCrop.height());
            }
            stillFrame = new StillFrame(result.get(CaptureResult.SENSOR_TIMESTAMP), result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    result.get(CaptureResult.SENSOR_SENSITIVITY), result.get(CaptureResult.EDGE_MODE), result.get(CaptureResult.NOISE_REDUCTION_MODE),
                    result.get(CaptureResult.LENS_STATE), result.get(CaptureResult.CONTROL_AF_STATE), result.get(CaptureResult.LENS_FOCUS_DISTANCE));
            if (stillJpeg != null) {
                finishStill();
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
            if (s == session) {
                failCapture("The camera failed to take the photo (reason " + failure.getReason() + ").");
            }
        }
    };

    private void finishStill() {
        byte[] jpeg = stillJpeg;
        long captureTimeMs = (stillJpegNanos - shutterNanos) / 1_000_000;
        long exposureNs = stillExposureNs;
        boolean oisOn = stillOisOn;
        StillFrame frame = stillFrame;
        long jpegMs = stillJpegMs;
        ActiveRect photoCrop = stillCrop;
        endCapture();
        focusSettler.lockCancelled();
        if (afMode != CameraMetadata.CONTROL_AF_MODE_OFF) {
            guarded("resume autofocus", () -> session.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL).build(), null, handler));
        }
        Log.d(CaptureLog.TAG, "Photo took " + captureTimeMs + " ms: " + frame);
        listener.onStillTaken(jpeg, captureTimeMs, exposureNs, oisOn, frame, jpegMs, photoCrop);
    }

    /** A photo whose result never came is still a photo. */
    private void onCaptureTimedOut() {
        if (stillJpeg != null) {
            finishStill();
        } else {
            failCapture("The capture timed out.");
        }
    }

    private void onJpegAvailable(ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            if (image == null || !capturing) {
                return;
            }
            long arrivedNanos = System.nanoTime();
            long arrivedMs = SystemClock.elapsedRealtime();
            byte[] jpeg;
            if (image.getFormat() == ImageFormat.YUV_420_888) {
                // The time is taken before encoding, to compare what the camera itself takes.
                jpeg = YuvPhoto.toJpeg(image);
                Log.d(CaptureLog.TAG, "YUV photo encoded in " + (System.nanoTime() - arrivedNanos) / 1_000_000 + " ms");
            } else {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                jpeg = new byte[buffer.remaining()];
                buffer.get(jpeg);
            }
            if (assistedStill) {
                stillJpeg = jpeg;
                stillJpegNanos = arrivedNanos;
                stillJpegMs = arrivedMs;
                if (stillFrame != null) {
                    finishStill();
                } else {
                    handler.postDelayed(resultLate, RESULT_GRACE_MS);
                }
                return;
            }
            long captureTimeMs = (System.nanoTime() - shutterNanos) / 1_000_000;
            endCapture();
            listener.onPhotoTaken(jpeg, captureTimeMs);
        }
    }

    private void onFrameAvailable(ImageReader reader) {
        try (Image frame = reader.acquireLatestImage()) {
            if (frame != null) {
                analyser.onFrame(frame);
            }
        }
    }

    private void endCapture() {
        capturing = false;
        sequence = null;
        assistedStill = false;
        stillJpeg = null;
        handler.removeCallbacks(captureWatchdog);
        handler.removeCallbacks(resultLate);
    }

    private void failCapture(String message) {
        endCapture();
        listener.onCameraError(message);
    }

    private void guardedCapture(CameraCall call) {
        try {
            call.run();
        } catch (CameraAccessException | RuntimeException e) {
            failCapture("Could not take the photo: " + e.getMessage());
        }
    }

    private void guarded(String what, CameraCall call) {
        try {
            call.run();
        } catch (CameraAccessException | RuntimeException e) {
            reportError("Could not " + what + ": " + e.getMessage());
        }
    }

    private void reportError(String message) {
        close();
        listener.onCameraError(message);
    }

    private void close() {
        generation++;
        endCapture();
        afRegions = null;
        aeRegions = null;
        focusSettler.begin(0, true, 0);
        focusStatus = FocusStatus.NONE;
        if (session != null) {
            session.close();
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        if (analysisReader != null) {
            analysisReader.close();
            analysisReader = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }
}
