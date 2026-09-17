package io.github.asteroidb612zs.hondatadash;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Small, code-native H badge: no bitmap scaling, shadows or metallic effects. */
final class HondaMark {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF outer = new RectF(4, 4, 96, 82);
    private final Path h = new Path();

    HondaMark() {
        h.moveTo(20, 12); h.lineTo(31, 12); h.lineTo(35, 36);
        h.cubicTo(39, 42, 61, 42, 65, 36); h.lineTo(69, 12); h.lineTo(80, 12);
        h.lineTo(76, 74); h.lineTo(61, 74); h.lineTo(60, 53);
        h.cubicTo(55, 48, 45, 48, 40, 53); h.lineTo(39, 74); h.lineTo(24, 74); h.close();
    }

    void draw(Canvas c, float x, float y, float width, float height, int color) {
        int save = c.save(); c.translate(x, y); c.scale(width / 100f, height / 86f);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4);
        c.drawRoundRect(outer, 15, 15, paint);
        paint.setStyle(Paint.Style.FILL); c.drawPath(h, paint); c.restoreToCount(save);
    }
}
