package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public final class HondaBrandView extends View {
    private final HondaMark mark = new HondaMark();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public HondaBrandView(Context c) { super(c); init(); }
    public HondaBrandView(Context c, AttributeSet a) { super(c, a); init(); }
    public HondaBrandView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }
    private void init() { paint.setTypeface(DashboardTypeface.get(getContext(), false)); }

    @Override protected void onDraw(Canvas c) {
        float scale = Math.min(getWidth() / 145f, getHeight() / 42f);
        int save = c.save(); c.translate(0, (getHeight() - 42 * scale) / 2f); c.scale(scale, scale);
        mark.draw(c, 3, 10, 25, 22, DashboardPalette.PRIMARY);
        paint.setColor(DashboardPalette.PRIMARY); paint.setTextSize(22); paint.setTextScaleX(1);
        float width = paint.measureText("HONDA");
        paint.setTextScaleX(Math.min(1, 79f / Math.max(1, width)));
        c.drawText("HONDA", 35, 28, paint); paint.setTextScaleX(1);
        paint.setColor(DashboardPalette.HONDA_RED); paint.setStrokeWidth(2.5f);
        c.drawLine(124, 31, 136, 10, paint); c.restoreToCount(save);
    }
}
