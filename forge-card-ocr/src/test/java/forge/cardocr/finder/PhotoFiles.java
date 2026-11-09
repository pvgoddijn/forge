package forge.cardocr.finder;

import java.awt.image.*;
import java.io.*;
import java.util.*;
import javax.imageio.*;

/** Loads the OCR spike's real photos, which live outside this project and may be missing. */
public final class PhotoFiles {

    /**
     * The OCR spike's work folder, which holds its photos. Not in the repository: the tests that need photos skip
     * without them. Set the system property {@code forge.cardocr.photos} to point elsewhere.
     */
    private static final File SPIKE = new File(System.getProperty("forge.cardocr.photos", "/mnt/wsl/git/forge-ocr-spike/work"));

    public static final File FOLDER = new File(SPIKE, "oriented");

    public static final File SYNTHETIC_FOLDER = new File(SPIKE, "synth");

    private PhotoFiles() {
    }

    public static List<File> list() {
        File[] files = FOLDER.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null) {
            return List.of();
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort((a, b) -> a.getName().compareTo(b.getName()));
        return sorted;
    }

    public static List<File> allSynthetic() {
        File[] files = SYNTHETIC_FOLDER.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null) {
            return List.of();
        }
        Arrays.sort(files);
        return List.of(files);
    }

    /** A fixed sample of the OCR spike's composited photos: every 10th by name, the first 30. */
    public static List<File> synthetic() {
        File[] files = SYNTHETIC_FOLDER.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null) {
            return List.of();
        }
        Arrays.sort(files);
        List<File> chosen = new ArrayList<>();
        for (int i = 0; i < 30 && i * 10 < files.length; i++) {
            chosen.add(files[i * 10]);
        }
        return chosen;
    }

    public static Argb load(File file) {
        try {
            BufferedImage image = ImageIO.read(file);
            int w = image.getWidth();
            int h = image.getHeight();
            return new Argb(image.getRGB(0, 0, w, h, null, 0, w), w, h);
        } catch (IOException e) {
            throw new RuntimeException("Can't read " + file, e);
        }
    }

    /** Pixels and size. */
    public record Argb(int[] pixels, int width, int height) {

        /** Box-averaged to a long side, standing in for a camera's lower-resolution stream. */
        public Argb fitted(int longSide) {
            double scale = (double) longSide / Math.max(width, height);
            int nw = (int) Math.round(width * scale);
            int nh = (int) Math.round(height * scale);
            int[] out = new int[nw * nh];
            for (int y = 0; y < nh; y++) {
                int y0 = y * height / nh;
                int y1 = Math.max(y0 + 1, (y + 1) * height / nh);
                for (int x = 0; x < nw; x++) {
                    int x0 = x * width / nw;
                    int x1 = Math.max(x0 + 1, (x + 1) * width / nw);
                    long r = 0;
                    long g = 0;
                    long b = 0;
                    for (int sy = y0; sy < y1; sy++) {
                        for (int sx = x0; sx < x1; sx++) {
                            int p = pixels[sy * width + sx];
                            r += p >> 16 & 255;
                            g += p >> 8 & 255;
                            b += p & 255;
                        }
                    }
                    int n = (x1 - x0) * (y1 - y0);
                    out[y * nw + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8
                            | (int) (b / n);
                }
            }
            return new Argb(out, nw, nh);
        }

        /** Rec. 601 luma, the same weights a camera's Y plane uses. */
        byte[] luma() {
            byte[] out = new byte[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                out[i] = (byte) ((299 * (p >> 16 & 255) + 587 * (p >> 8 & 255) + 114 * (p & 255))
                        / 1000);
            }
            return out;
        }
    }
}
