package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.util.AttributeSet;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;

/** A centred 12-unit raster: equal CYL cells AND exact quarter-column joins. */
public final class DashboardGridLayout extends LinearLayout {
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    public DashboardGridLayout(Context c) { super(c); }
    public DashboardGridLayout(Context c, AttributeSet a) { super(c, a); }
    public DashboardGridLayout(Context c, AttributeSet a, int s) { super(c, a, s); }

    static int gridWidth(int width, int minimumMargin) {
        int available = Math.max(0, width - 2 * minimumMargin);
        return available < 12 ? available : available / 12 * 12;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) {
            int width = MeasureSpec.getSize(widthSpec);
            int content = gridWidth(width, getPaddingTop());
            int left = (width - content) / 2, right = width - content - left;
            if (left != getPaddingLeft() || right != getPaddingRight()) {
                setPadding(left, getPaddingTop(), right, getPaddingBottom());
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        drawFrames(canvas, 1, 1);
    }

    void drawFrames(Canvas canvas, float mainAlpha, float bottomAlpha) {
        float stroke = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        outline.setStyle(Paint.Style.STROKE); outline.setStrokeWidth(stroke);
        outline.setColor(DashboardPalette.LINE);
        // V2.9.1: the eight main instruments own their individual rounded frames,
        // exactly like the supplied reference. Only the bottom strip keeps one
        // continuous outer frame around its tightly packed cells.
        View bottom = findViewById(R.id.bottomRow);
        outline.setAlpha(Math.round(255 * bottomAlpha));
        drawOutline(canvas, bottom, bottom, stroke);
    }

    private void drawOutline(Canvas c, View first, View last, float stroke) {
        if (first == null || last == null) return;
        float inset = stroke / 2f, radius = 4 * getResources().getDisplayMetrics().density;
        frame.set(first.getLeft() + inset, first.getTop() + inset,
                last.getRight() - inset, last.getBottom() - inset);
        c.drawRoundRect(frame, radius, radius, outline);
    }
}
