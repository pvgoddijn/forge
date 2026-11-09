package forge.cardocr.assist;

/**
 * What the gyroscope says at one finder frame.
 *
 * @param image how the scene moved in the frame since the previous finder frame
 * @param speedRadS angular speed now, averaged over a short window
 * @param peakRadS the highest angular speed in the last 500 ms
 */
public record FrameMotion(ImageMotion image, double speedRadS, double peakRadS) {
}
