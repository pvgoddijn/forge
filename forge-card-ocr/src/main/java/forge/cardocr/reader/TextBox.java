package forge.cardocr.reader;

import forge.cardocr.finder.*;

/**
 * An oriented box around detected text: its centre, its direction as a unit vector, and how far it
 * reaches along and across that direction.
 */
public record TextBox(double centreX, double centreY, double unitX, double unitY, double alongLow, double alongHigh,
        double acrossLow, double acrossHigh) {

    public double length() {
        return alongHigh - alongLow;
    }

    public double thickness() {
        return acrossHigh - acrossLow;
    }

    /** Its corners in the order a card's go: along the text, then across it. */
    CardQuad asQuad() {
        return new CardQuad(corner(alongLow, acrossLow), corner(alongHigh, acrossLow), corner(alongHigh, acrossHigh),
                corner(alongLow, acrossHigh), 0, false);
    }

    private Corner corner(double along, double across) {
        return new Corner(centreX + along * unitX - across * unitY, centreY + along * unitY + across * unitX);
    }
}
