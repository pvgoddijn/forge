package forge.cardocr.assist;

/** A rectangle in sensor active-array coordinates, such as SCALER_CROP_REGION. */
public record ActiveRect(double left, double top, double width, double height) {
}
