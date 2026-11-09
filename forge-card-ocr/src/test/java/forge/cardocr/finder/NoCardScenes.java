package forge.cardocr.finder;

import java.io.*;
import java.util.*;

/**
 * Images with no card in them: drawn scenes of things that have caused or might cause false cards,
 * and crops of the real photos. Each crop was checked by eye to hold no card or part of one.
 */
final class NoCardScenes {

    private static final int W = 640;
    private static final int H = 480;

    /** One image, with luma stored without gaps between rows. */
    record Scene(String name, byte[] luma, int width, int height) {
    }

    /** A card-free part of a real photo, in shares of its width and height. */
    private record Crop(String photo, double x0, double y0, double x1, double y1) {
    }

    private static final List<Crop> CROPS = List.of(
            new Crop("slash-reptile-rampager", 0.76, 0.05, 1.00, 0.90),
            new Crop("wind-scarred-crag", 0.68, 0.00, 1.00, 1.00),
            new Crop("solemn-simulacrum", 0.00, 0.30, 0.20, 0.55),
            new Crop("solemn-simulacrum", 0.00, 0.72, 0.40, 1.00),
            new Crop("boros-signet", 0.82, 0.00, 1.00, 0.85),
            new Crop("vandalblast", 0.02, 0.15, 0.25, 1.00),
            new Crop("combat-celebrant", 0.00, 0.30, 0.20, 0.75),
            new Crop("ao-the-dawn-sky", 0.00, 0.00, 0.70, 0.20));

    private NoCardScenes() {
    }

    static List<Scene> crops() {
        List<Scene> out = new ArrayList<>();
        for (Crop crop : CROPS) {
            File file = new File(PhotoFiles.FOLDER, crop.photo + ".jpg");
            if (!file.exists()) {
                continue;
            }
            PhotoFiles.Argb full = PhotoFiles.load(file);
            int x0 = (int) (crop.x0 * full.width());
            int y0 = (int) (crop.y0 * full.height());
            int w = (int) (crop.x1 * full.width()) - x0;
            int h = (int) (crop.y1 * full.height()) - y0;
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                System.arraycopy(full.pixels(), (y0 + y) * full.width() + x0, pixels, y * w, w);
            }
            PhotoFiles.Argb small = new PhotoFiles.Argb(pixels, w, h).fitted(640);
            out.add(new Scene("crop-" + crop.photo + "-" + out.size(), small.luma(), small.width(), small.height()));
        }
        return out;
    }

    /** Three variations of each kind of scene, repeatable from fixed seeds. */
    static List<Scene> drawn() {
        List<Scene> out = new ArrayList<>();
        for (int seed = 1; seed <= 3; seed++) {
            out.add(finish("plain-wood", seed, wood(seed, 0)));
            out.add(finish("wood-grain", seed, wood(seed, 22)));
            out.add(finish("wrong-shape-rectangles", seed, wrongShapes(seed)));
            out.add(finish("keyboard-grid", seed, keyboard(seed)));
            out.add(finish("case-slots", seed, caseSlots(seed)));
            out.add(finish("case-on-desk", seed, caseOnDesk(seed)));
        }
        return out;
    }

    /** A plain dark rectangle of the card's own shape. Nothing tells it from a blank card back. */
    static List<Scene> blankCardShapes() {
        List<Scene> out = new ArrayList<>();
        for (int seed = 1; seed <= 3; seed++) {
            Random r = new Random(seed);
            float[] f = wood(seed, 8);
            box(f, 320, 240, 150 + 40 * r.nextDouble(), 210 + 56 * r.nextDouble(), r.nextDouble() * 40 - 20, 30);
            out.add(finish("blank-rectangle-card-shape", seed, f));
        }
        return out;
    }

    private static float[] wood(int seed, double grainContrast) {
        Random r = new Random(seed * 31L);
        float[] f = new float[W * H];
        float base = 90 + r.nextInt(70);
        double angle = Math.toRadians((seed % 2 == 0 ? 0 : 90) + (r.nextDouble() - 0.5) * 16);
        double nx = Math.cos(angle);
        double ny = Math.sin(angle);
        double phase = r.nextDouble() * 10;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double t = x * nx + y * ny;
                double waves = Math.sin(t * 0.09 + phase) + 0.6 * Math.sin(t * 0.31 + 2 * phase)
                        + 0.4 * Math.sin(t * 0.73 + 3 * phase);
                f[y * W + x] = (float) (base + grainContrast * waves / 2);
            }
        }
        return f;
    }

    private static float[] wrongShapes(int seed) {
        Random r = new Random(seed * 37L);
        float[] f = wood(seed, 6);
        // A square, a long bar and a shape slightly too wide to be a card.
        box(f, 170 + 30 * r.nextDouble(), 150, 150, 150, r.nextDouble() * 30, 25);
        box(f, 460, 110 + 40 * r.nextDouble(), 240, 60, r.nextDouble() * 20 - 10, 35);
        box(f, 400, 340, 220, 120, r.nextDouble() * 30 - 15, 20);
        return f;
    }

    private static float[] keyboard(int seed) {
        Random r = new Random(seed * 41L);
        float[] f = wood(seed, 5);
        double angle = r.nextDouble() * 24 - 12;
        double pitch = 42 + r.nextInt(14);
        box(f, 320, 240, 620, 380, angle, 20);
        double c = Math.cos(Math.toRadians(angle));
        double s = Math.sin(Math.toRadians(angle));
        for (int row = -3; row <= 3; row++) {
            for (int column = -7; column <= 7; column++) {
                double u = column * pitch + (row % 2) * pitch / 4;
                double v = row * pitch;
                box(f, 320 + u * c - v * s, 240 + u * s + v * c, pitch * 0.82, pitch * 0.82, angle, 48);
            }
        }
        return f;
    }

    private static float[] caseSlots(int seed) {
        Random r = new Random(seed * 43L);
        float[] f = new float[W * H];
        Arrays.fill(f, 70 + r.nextInt(40));
        double angle = r.nextDouble() * 10 - 5;
        double pitch = 20 + r.nextInt(14);
        double c = Math.cos(Math.toRadians(angle));
        double s = Math.sin(Math.toRadians(angle));
        for (int i = -14; i <= 14; i++) {
            box(f, 320 + i * pitch * c, 240 + i * pitch * s, pitch * 0.45, 330, angle, 15);
        }
        return f;
    }

    /** A dark tower case with a grille and a side panel, on a plain desk under a wall edge. */
    private static float[] caseOnDesk(int seed) {
        Random r = new Random(seed * 47L);
        float[] f = wood(seed, 8);
        for (int i = 0; i < 150 * W; i++) {
            f[i] = 170 + r.nextInt(3);
        }
        double angle = r.nextDouble() * 8 - 4;
        double cx = 300 + r.nextInt(60);
        box(f, cx, 240, 260, 400, angle, 32);
        box(f, cx, 150, 200, 170, angle, 55);
        double c = Math.cos(Math.toRadians(angle));
        double s = Math.sin(Math.toRadians(angle));
        for (int i = -7; i <= 7; i++) {
            box(f, cx + i * 14 * c - 110 * s, 340 + i * 14 * s + 0 * c, 7, 90, angle, 18);
        }
        box(f, cx - 130 * c, 80, 4, 300, angle, 90);
        return f;
    }

    private static void box(float[] f, double cx, double cy, double width, double height, double degrees, int value) {
        double c = Math.cos(Math.toRadians(degrees));
        double s = Math.sin(Math.toRadians(degrees));
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double dx = x - cx;
                double dy = y - cy;
                if (Math.abs(dx * c + dy * s) <= width / 2 && Math.abs(-dx * s + dy * c) <= height / 2) {
                    f[y * W + x] = value;
                }
            }
        }
    }

    /** Softens the edges like a lens, then adds uneven light and sensor noise. */
    private static Scene finish(String name, int seed, float[] f) {
        Random r = new Random(seed * 53L + name.hashCode());
        byte[] out = new byte[W * H];
        double lightX = W * r.nextDouble();
        double lightY = H * r.nextDouble();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int x0 = Math.max(0, x - 1);
                int x1 = Math.min(W - 1, x + 1);
                int y0 = Math.max(0, y - 1);
                int y1 = Math.min(H - 1, y + 1);
                double soft = (f[y0 * W + x0] + f[y0 * W + x1] + f[y1 * W + x0] + f[y1 * W + x1] + 4 * f[y * W + x]) / 8;
                double d = Math.hypot(x - lightX, y - lightY) / Math.hypot(W, H);
                double value = soft * (1.1 - 0.5 * d * d) + r.nextGaussian() * 2.5;
                out[y * W + x] = (byte) Math.max(0, Math.min(255, Math.round(value)));
            }
        }
        return new Scene(name + "-" + seed, out, W, H);
    }
}
