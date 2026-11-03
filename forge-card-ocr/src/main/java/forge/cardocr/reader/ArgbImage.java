package forge.cardocr.reader;

/** Pixels row by row, with no Android or AWT types. */
public record ArgbImage(int[] pixels, int width, int height) {

    /** Averages source pixels when shrinking, and picks the nearest when growing. */
    public ArgbImage scaledTo(int newWidth, int newHeight) {
        int[] out = new int[newWidth * newHeight];
        for (int y = 0; y < newHeight; y++) {
            int y0 = y * height / newHeight;
            int y1 = Math.max(y0 + 1, (y + 1) * height / newHeight);
            for (int x = 0; x < newWidth; x++) {
                int x0 = x * width / newWidth;
                int x1 = Math.max(x0 + 1, (x + 1) * width / newWidth);
                long r = 0;
                long g = 0;
                long b = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int row = sy * width;
                    for (int sx = x0; sx < x1; sx++) {
                        int p = pixels[row + sx];
                        r += p >> 16 & 255;
                        g += p >> 8 & 255;
                        b += p & 255;
                    }
                }
                int n = (x1 - x0) * (y1 - y0);
                out[y * newWidth + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        return new ArgbImage(out, newWidth, newHeight);
    }
}
