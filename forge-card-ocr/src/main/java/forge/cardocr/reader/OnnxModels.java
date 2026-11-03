package forge.cardocr.reader;

import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * The PP-OCR models under ONNX Runtime. Android and the desktop share this API. A session can run
 * calls from several threads at once. Sessions are kept per threading setup, created when first
 * needed, and live as long as this object.
 */
public final class OnnxModels implements TextModels, AutoCloseable {

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final byte[] recogniserModel;
    private final byte[] detectorModel;
    private final Map<Threads, OrtSession> recognisers = new HashMap<>();
    private final Map<Threads, OrtSession> detectors = new HashMap<>();

    public OnnxModels(byte[] recogniserModel, byte[] detectorModel) {
        this.recogniserModel = recogniserModel;
        this.detectorModel = detectorModel;
    }

    /** Creates the sessions now rather than on the first read. */
    public void prepare(Threads recogniser, Threads detector) {
        session(recognisers, recogniserModel, recogniser);
        session(detectors, detectorModel, detector);
    }

    @Override
    public float[][] recognise(float[] input, int height, int width, Threads threads) {
        OrtSession session = session(recognisers, recogniserModel, threads);
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[] {1, 3, height, width});
                OrtSession.Result result = session.run(Map.of(session.getInputNames().iterator().next(), tensor))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            long[] shape = output.getInfo().getShape();
            int frames = (int) shape[1];
            int classes = (int) shape[2];
            FloatBuffer values = output.getFloatBuffer();
            float[][] out = new float[frames][classes];
            for (float[] frame : out) {
                values.get(frame);
            }
            return out;
        } catch (OrtException e) {
            throw new IllegalStateException("Recogniser failed", e);
        }
    }

    @Override
    public float[] detect(float[] input, int height, int width, Threads threads) {
        OrtSession session = session(detectors, detectorModel, threads);
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[] {1, 3, height, width});
                OrtSession.Result result = session.run(Map.of(session.getInputNames().iterator().next(), tensor))) {
            FloatBuffer values = ((OnnxTensor) result.get(0)).getFloatBuffer();
            float[] out = new float[values.remaining()];
            values.get(out);
            return out;
        } catch (OrtException e) {
            throw new IllegalStateException("Detector failed", e);
        }
    }

    private synchronized OrtSession session(Map<Threads, OrtSession> sessions, byte[] model, Threads threads) {
        OrtSession existing = sessions.get(threads);
        if (existing != null) {
            return existing;
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads.intraOp());
            if (threads.xnnpack()) {
                options.addXnnpack(Map.of("intra_op_num_threads", Integer.toString(threads.intraOp())));
            }
            OrtSession created = env.createSession(model, options);
            sessions.put(threads, created);
            return created;
        } catch (OrtException e) {
            throw new IllegalStateException("Could not load a model", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            for (OrtSession session : recognisers.values()) {
                session.close();
            }
            for (OrtSession session : detectors.values()) {
                session.close();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Could not close a model", e);
        }
        recognisers.clear();
        detectors.clear();
    }
}
