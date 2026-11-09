package forge.cardocr.assist;

import java.util.*;

/** The words on the preview screen. The main line says what the app is doing and nothing else. */
public final class StatusText {

    private StatusText() {
    }

    public static String main(boolean cardTracked, boolean latched, boolean capturing, boolean autoEnabled) {
        if (capturing) {
            return "Capturing";
        }
        if (!cardTracked) {
            return "Looking for a card";
        }
        if (latched) {
            return "Remove or change the card";
        }
        return autoEnabled ? "Hold steady" : "Card found. Press the shutter";
    }

    /** Worded so that no outcome reads as the app rejecting the card. */
    public static String gyroCheck(String verdict) {
        return "gyro direction check: " + switch (verdict) {
            case MotionAudit.OK -> "fine";
            case MotionAudit.NOT_ENOUGH_MOTION -> "needs movement to judge";
            case MotionAudit.UNCLEAR -> "can't tell yet";
            case MotionAudit.WRONG_SIGN -> "an axis looks backwards";
            case MotionAudit.AXES_SWAPPED -> "axes look swapped";
            case MotionAudit.WRONG_ROLL_SIGN -> "roll looks backwards";
            default -> verdict;
        };
    }

    /**
     * The numbers behind the Debug checkbox.
     *
     * @param analysed frames the finder ran on
     * @param skipped frames that arrived while the finder was busy, which is by design
     * @param cardSteadyMs how long the card has held still, or -1 with no card
     */
    public static String debug(double finderMs, double meanMs, double framesPerSecond, long analysed, long skipped,
            String gyroVerdict, Double speedRadS, long phoneSteadyMs, long cardSteadyMs, boolean focusSettled, int refocuses) {
        String phone = speedRadS == null ? "gyro: not in use, comparing outlines"
                : String.format(Locale.ROOT, "phone %.3f rad/s, steady %d ms\n%s", speedRadS, phoneSteadyMs, gyroCheck(gyroVerdict));
        String card = cardSteadyMs < 0 ? "no card tracked"
                : String.format(Locale.ROOT, "card steady %d ms, %s", cardSteadyMs, focusSettled ? "focused" : "focusing");
        return String.format(Locale.ROOT, "finder %.0f ms (mean %.0f), %.1f frames/s\nframes analysed %d\nframes skipped (normal) %d\n%s\n%s\nrefocuses %d",
                finderMs, meanMs, framesPerSecond, analysed, skipped, phone, card, refocuses);
    }
}
