package forge.app.camera;

import android.content.Context;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;
import forge.cardocr.*;
import forge.cardocr.assist.*;
import forge.cardocr.finder.*;

/**
 * Runs the card finder on its own thread. The camera thread only copies the Y plane into a slot
 * that holds the newest frame, so a slow finder drops frames rather than delaying the camera.
 *
 * <p>Android has no virtual threads, so this is a plain thread. It holds the one CardFinder. After
 * stop it finishes any find in progress, reports nothing further and exits.
 */
final class AssistedAnalyser implements FrameAnalyser {

    /** Called on the analyser thread. Implementations hop to the main thread for UI work. */
    interface Host {
        FocusStatus focusStatus();

        /** The camera's latest exposure, or null if not known. */
        Long exposureNs();

        void focusOnCard(CardQuad quad, Dim frame, int generation);

        void releaseFocus();

        void onFrameAnalysed(AssistedAnalyser analyser, AssistedPipeline.Outcome outcome, Dim frame, long dropped, long frameNs);

        void onAutoCapture(AssistedAnalyser analyser);

        /** Whether an outline in analysis-frame pixels can be seen on the screen. Called on the analyser thread. */
        boolean onScreen(CardQuad quad);
    }

    /** @param timeNs mid-exposure on the elapsedRealtimeNanos clock, or arrival time on the same clock */
    private record Frame(byte[] luma, int width, int height, long timeNs) {
    }

    private static final Dim MAX_FRAME = new Dim(640, 480);
    /** Guess: an image timestamp marks the start of the first row, and the middle of the frame is about this later. */
    private static final long MID_FRAME_NS = 15_000_000L;

    private final Host host;
    private final AssistedPipeline pipeline;
    private final LatestFrameSlot<Frame> slot = new LatestFrameSlot<>();
    private final Thread thread = new Thread(this::run, "card-finder");
    private final MotionModel motion = new MotionModel();
    private final MotionSensor sensor;
    private long previousFrameNs;
    private volatile boolean realtimeClock;

    private volatile boolean stopped;
    private final boolean autoEnabled;
    private final long steadyMs;
    private volatile boolean captureInFlight;
    private volatile boolean latchRequested;
    private volatile boolean releaseRequested;
    private volatile boolean paused;

    AssistedAnalyser(Context context, Host host, float[] capturedCard, boolean autoEnabled, long steadyMs) {
        this.host = host;
        this.sensor = new MotionSensor(context, motion.history());
        this.pipeline = new AssistedPipeline(capturedCard);
        this.autoEnabled = autoEnabled;
        this.steadyMs = steadyMs;
    }

    void start() {
        sensor.start();
        thread.start();
    }

    /** Doesn't block: a find can't be interrupted, and this is called on the main thread. */
    void stop() {
        stopped = true;
        sensor.stop();
        slot.close();
    }

    /** A shutter press: suspends refocusing, and marks the current card as captured. */
    void onCaptureStarted() {
        captureInFlight = true;
        latchRequested = true;
    }

    /** @param failed true re-arms auto capture, since nothing was saved for this card */
    void onCaptureEnded(boolean failed) {
        paused = false;
        captureInFlight = false;
        releaseRequested = failed;
    }

    /** Stops finding while a photo is saved, so the two don't compete for the CPU. Ends with the capture. */
    void pauseWhileSaving() {
        paused = true;
    }

    /** Moves a result from its frame to the preview on screen. Any thread. */
    ImageMotion overlayMotion(long frameNs, long previewTimestampNs) {
        long previewNs = realtimeClock && previewTimestampNs > 0 ? previewTimestampNs + MID_FRAME_NS : 0;
        return motion.forOverlay(frameNs, previewNs, SystemClock.elapsedRealtimeNanos());
    }

    @Override
    public void onOptics(GyroMapping mapping, boolean realtime) {
        motion.setMapping(mapping);
        realtimeClock = realtime;
    }

    @Override
    public Dim maxFrameSize() {
        return MAX_FRAME;
    }

    @Override
    public void onFrame(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Frame frame = slot.acquire();
        byte[] luma = frame == null || frame.width() != width || frame.height() != height ? new byte[width * height] : frame.luma();
        long timeNs = realtimeClock ? image.getTimestamp() + MID_FRAME_NS : SystemClock.elapsedRealtimeNanos();
        frame = new Frame(luma, width, height, timeNs);
        Image.Plane y = image.getPlanes()[0];
        YPlane.compact(y.getBuffer(), y.getRowStride(), y.getPixelStride(), width, height, frame.luma());
        slot.publish(frame);
    }

    private void run() {
        Log.d(CaptureLog.TAG, "Analyser thread started");
        CardFinder finder = new CardFinder();
        Frame frame;
        while ((frame = slot.take()) != null) {
            try {
                if (paused) {
                    continue;
                }
                analyse(finder, frame);
            } catch (RuntimeException e) {
                // One bad frame shouldn't end the session, but it must show up in the collected log.
                Log.e(CaptureLog.TAG, "Frame analysis failed", e);
            } finally {
                slot.release(frame);
            }
        }
        Log.d(CaptureLog.TAG, "Analyser thread stopped");
    }

    private void analyse(CardFinder finder, Frame frame) {
        FindResult found = pipeline.find(finder, frame.luma(), frame.width(), frame.height(), SystemClock.elapsedRealtime(), host::onScreen);
        long now = SystemClock.elapsedRealtime();
        FrameMotion frameMotion = motion.forFrame(previousFrameNs, frame.timeNs(), SystemClock.elapsedRealtimeNanos());
        previousFrameNs = frame.timeNs();
        if (releaseRequested) {
            releaseRequested = false;
            pipeline.releaseLatch();
        }
        Dim size = new Dim(frame.width(), frame.height());
        AssistedPipeline.Outcome outcome = pipeline.onFrame(new AssistedPipeline.Input(found, frame.luma(),
                frame.width(), frame.height(), now, host.focusStatus(), autoEnabled, steadyMs, captureInFlight, frameMotion, host.exposureNs()));
        if (latchRequested) {
            latchRequested = false;
            pipeline.latch(outcome.signature());
        }
        if (stopped) {
            return;
        }
        switch (outcome.focusAction()) {
            case FOCUS_ON_CARD -> {
                Log.d(CaptureLog.TAG, "Refocus " + outcome.focusGeneration() + " on card");
                host.focusOnCard(outcome.focusQuad(), size, outcome.focusGeneration());
            }
            case RELEASE -> {
                Log.d(CaptureLog.TAG, "Focus released");
                host.releaseFocus();
            }
            case NONE -> {
            }
        }
        host.onFrameAnalysed(this, outcome, size, slot.dropped(), frame.timeNs());
        if (outcome.captureNow()) {
            captureInFlight = true;
            Log.d(CaptureLog.TAG, "Auto capture: card steady for " + outcome.card().steadyForMs(now) + " ms, phone steady for " + outcome.phoneSteadyMs()
                    + " ms, waited " + outcome.steadyWaitMs() + " ms, speed " + (frameMotion == null ? "unknown" : frameMotion.speedRadS() + " rad/s"));
            host.onAutoCapture(this);
        }
    }
}
