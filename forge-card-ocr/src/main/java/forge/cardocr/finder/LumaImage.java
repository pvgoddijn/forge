package forge.cardocr.finder;

/** A greyscale image with values 0 to 255, stored row by row. */
record LumaImage(float[] pixels, int width, int height) {

    static LumaImage fromBytes(byte[] luma, int width, int height) {
        checkSize(luma.length, width, height);
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = luma[i] & 0xFF;
        }
        return new LumaImage(pixels, width, height);
    }

    static void checkSize(int length, int width, int height) {
        if (width <= 0 || height <= 0 || length < width * height) {
            throw new IllegalArgumentException(
                    "Need " + width + "x" + height + " pixels, got an array of " + length);
        }
    }

    /** This image scaled down so its long side fits, or this same image if it already does. */
    LumaImage fitted(int longSide) {
        int current = Math.max(width, height);
        if (current <= longSide) {
            return this;
        }
        double scale = (double) longSide / current;
        return boxScaled(Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)));
    }

    private LumaImage boxScaled(int newWidth, int newHeight) {
        float[] out = new float[newWidth * newHeight];
        for (int y = 0; y < newHeight; y++) {
            int y0 = y * height / newHeight;
            int y1 = Math.max(y0 + 1, (y + 1) * height / newHeight);
            for (int x = 0; x < newWidth; x++) {
                int x0 = x * width / newWidth;
                int x1 = Math.max(x0 + 1, (x + 1) * width / newWidth);
                float sum = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int row = sy * width;
                    for (int sx = x0; sx < x1; sx++) {
                        sum += pixels[row + sx];
                    }
                }
                out[y * newWidth + x] = sum / ((x1 - x0) * (y1 - y0));
            }
        }
        return new LumaImage(out, newWidth, newHeight);
    }

    /** Bilinear sample, or -1 if the point is outside the image. */
    float sample(double x, double y) {
        if (x < 0 || y < 0) {
            return -1;
        }
        int ix = (int) x;
        int iy = (int) y;
        if (ix >= width - 1 || iy >= height - 1) {
            return -1;
        }
        double fx = x - ix;
        double fy = y - iy;
        int i = iy * width + ix;
        return (float) (pixels[i] * (1 - fx) * (1 - fy)
                + pixels[i + 1] * fx * (1 - fy)
                + pixels[i + width] * (1 - fx) * fy
                + pixels[i + width + 1] * fx * fy);
    }
}
