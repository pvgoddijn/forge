package forge.app.camera;

import android.content.Context;
import android.hardware.*;
import android.os.*;
import android.util.Log;
import forge.cardocr.assist.*;

/**
 * Feeds the gyroscope into a history on its own thread. TYPE_GYROSCOPE is used, since its factory and
 * running bias corrections only help here, and the uncalibrated one would need the bias removed by
 * hand.
 */
final class MotionSensor implements SensorEventListener {

    /** 100 Hz is well above hand shake, and below the 200 Hz limit for apps without a special permission. */
    private static final int SAMPLE_PERIOD_US = 10_000;
    /** Sensor timestamps further than this from the realtime clock aren't on it. */
    private static final long MAX_CLOCK_OFFSET_NS = 500_000_000L;

    private final SensorManager manager;
    private final Sensor gyroscope;
    private final GyroHistory history;
    private HandlerThread thread;
    private boolean checkedClock;
    private boolean useArrivalTimes;

    MotionSensor(Context context, GyroHistory history) {
        this.manager = context.getSystemService(SensorManager.class);
        this.gyroscope = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        this.history = history;
    }

    /** False reports "unavailable", and steadiness falls back to comparing outlines. */
    boolean available() {
        return gyroscope != null;
    }

    void start() {
        if (gyroscope == null || thread != null) {
            Log.d(CaptureLog.TAG, "Gyroscope: " + (gyroscope == null ? "unavailable" : "already running"));
            return;
        }
        thread = new HandlerThread("gyroscope");
        thread.start();
        boolean registered = manager.registerListener(this, gyroscope, SAMPLE_PERIOD_US, 0, new Handler(thread.getLooper()));
        Log.d(CaptureLog.TAG, "Gyroscope " + gyroscope.getName() + " registered=" + registered);
    }

    void stop() {
        if (thread != null) {
            manager.unregisterListener(this);
            thread.quitSafely();
            thread = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = SystemClock.elapsedRealtimeNanos();
        if (!checkedClock) {
            checkedClock = true;
            long offset = now - event.timestamp;
            useArrivalTimes = Math.abs(offset) > MAX_CLOCK_OFFSET_NS;
            Log.d(CaptureLog.TAG, "Gyroscope clock offset " + offset / 1_000_000 + " ms, arrivalTimes=" + useArrivalTimes);
        }
        history.add(useArrivalTimes ? now : event.timestamp, event.values[0], event.values[1], event.values[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
