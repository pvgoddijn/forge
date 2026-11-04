package forge.cardocr.assist;

import forge.cardocr.finder.*;

/** How the scene moved in the analysis frame over some interval, as a function of image position. */
public interface ImageMotion {

    ImageMotion NONE = point -> point;

    /** Where a scene point seen at {@code point} at the interval's start is seen at its end. */
    Corner moved(Corner point);

    default CardQuad moved(CardQuad q) {
        return new CardQuad(moved(q.c0()), moved(q.c1()), moved(q.c2()), moved(q.c3()), q.score(), q.refined(), q.evidence());
    }

    static ImageMotion shift(double dx, double dy) {
        return point -> new Corner(point.x() + dx, point.y() + dy);
    }
}
