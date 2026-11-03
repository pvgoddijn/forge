package forge.cardocr.reader;

/** The seam to the neural networks, so everything else runs and tests without them. */
public interface TextModels {

    /** How a model call may use the CPU. */
    record Threads(int intraOp, boolean xnnpack) {
        public static final Threads ONE = new Threads(1, false);
    }

    /** Character probabilities per frame, for a band in the recogniser's input form. */
    float[][] recognise(float[] input, int height, int width, Threads threads);

    /** Text probability per pixel, row by row. */
    float[] detect(float[] input, int height, int width, Threads threads);
}
