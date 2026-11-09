package forge.cardocr.finder;

/**
 * A perspective mapping from the unit square to an image quad. Unit square corners
 * (0,0) (1,0) (1,1) (0,1) go to the quad's c0, c1, c2, c3.
 */
public final class Homography {

    private final double h0, h1, h2, h3, h4, h5, h6, h7, h8;

    private Homography(double h0, double h1, double h2, double h3, double h4, double h5,
            double h6, double h7, double h8) {
        this.h0 = h0;
        this.h1 = h1;
        this.h2 = h2;
        this.h3 = h3;
        this.h4 = h4;
        this.h5 = h5;
        this.h6 = h6;
        this.h7 = h7;
        this.h8 = h8;
    }

    static Homography fromUnitSquare(Corner c0, Corner c1, Corner c2, Corner c3) {
        double dx1 = c1.x() - c2.x();
        double dx2 = c3.x() - c2.x();
        double sx = c0.x() - c1.x() + c2.x() - c3.x();
        double dy1 = c1.y() - c2.y();
        double dy2 = c3.y() - c2.y();
        double sy = c0.y() - c1.y() + c2.y() - c3.y();
        double det = dx1 * dy2 - dx2 * dy1;
        double a = (sx * dy2 - dx2 * sy) / det;
        double b = (dx1 * sy - sx * dy1) / det;
        return new Homography(
                c1.x() - c0.x() + a * c1.x(), c3.x() - c0.x() + b * c3.x(), c0.x(),
                c1.y() - c0.y() + a * c1.y(), c3.y() - c0.y() + b * c3.y(), c0.y(),
                a, b, 1);
    }

    public static Homography fromUnitSquare(CardQuad quad) {
        return fromUnitSquare(quad.c0(), quad.c1(), quad.c2(), quad.c3());
    }

    public double mapX(double u, double v) {
        return (h0 * u + h1 * v + h2) / (h6 * u + h7 * v + h8);
    }

    public double mapY(double u, double v) {
        return (h3 * u + h4 * v + h5) / (h6 * u + h7 * v + h8);
    }

    Corner map(double u, double v) {
        return new Corner(mapX(u, v), mapY(u, v));
    }

    /** The mapping from image coordinates back to the unit square, as (u, v) in a Corner. */
    Corner unmap(double x, double y) {
        // The inverse of a 3x3 matrix is its adjugate, up to a scale that homogeneous coordinates
        // divide away.
        double a0 = h4 * h8 - h5 * h7;
        double a1 = h2 * h7 - h1 * h8;
        double a2 = h1 * h5 - h2 * h4;
        double a3 = h5 * h6 - h3 * h8;
        double a4 = h0 * h8 - h2 * h6;
        double a5 = h2 * h3 - h0 * h5;
        double a6 = h3 * h7 - h4 * h6;
        double a7 = h1 * h6 - h0 * h7;
        double a8 = h0 * h4 - h1 * h3;
        double z = a6 * x + a7 * y + a8;
        return new Corner((a0 * x + a1 * y + a2) / z, (a3 * x + a4 * y + a5) / z);
    }
}
