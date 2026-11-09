package forge.app.camera;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import forge.cardocr.finder.*;
import java.util.*;

/** Draws the tracked card's outline over the preview. */
final class OverlayView extends View {

    private final Paint provisionalPaint = stroke(0xFFFFFFFF, 2);
    private final Paint pendingPaint = stroke(0xFFFF9800, 5);
    private final Paint readyPaint = stroke(0xFF4CAF50, 5);
    private final Path cardPath = new Path();
    private boolean focusSettled;
    private boolean provisional;

    OverlayView(Context context) {
        super(context);
    }

    private Paint stroke(int color, float dp) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp * getResources().getDisplayMetrics().density);
        return paint;
    }

    /** The outline may be null. Thin until confirmed, then orange until focus has settled on the card, then green. */
    void show(List<Corner> card, boolean confirmed, boolean settled) {
        provisional = !confirmed;
        fill(cardPath, card);
        focusSettled = settled;
        invalidate();
    }

    void clear() {
        show(null, false, false);
    }

    private static void fill(Path path, List<Corner> corners) {
        path.rewind();
        if (corners == null) {
            return;
        }
        path.moveTo((float) corners.get(0).x(), (float) corners.get(0).y());
        for (int i = 1; i < corners.size(); i++) {
            path.lineTo((float) corners.get(i).x(), (float) corners.get(i).y());
        }
        path.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(cardPath, provisional ? provisionalPaint : focusSettled ? readyPaint : pendingPaint);
    }
}
