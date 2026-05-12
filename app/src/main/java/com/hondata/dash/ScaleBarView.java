package com.hondata.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 带刻度线的赛车风格进度条。
 * 支持: 刻度标记 + 数字标签 + 颜色区间 + 锚点填充。
 */
public class ScaleBarView extends View {

    private float minVal = 0, maxVal = 100;
    private float curVal = Float.NaN;
    private float anchorVal = 0;
    private float[] ticks = new float[0];
    private String[] tickLabels = new String[0];
    private boolean showLabels = true;
    private final List<Zone> zones = new ArrayList<>();

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float density;

    public static class Zone {
        public final float start, end;
        public final int color;
        public Zone(float s, float e, int c) { start = s; end = e; color = c; }
    }

    public ScaleBarView(Context ctx) { super(ctx); init(ctx); }
    public ScaleBarView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(ctx); }
    public ScaleBarView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init(ctx);
    }

    private void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;

        bgPaint.setColor(0xFF1A1A1A);
        bgPaint.setStyle(Paint.Style.FILL);

        fillPaint.setStyle(Paint.Style.FILL);

        tickPaint.setColor(0xFF444444);
        tickPaint.setStrokeWidth(Math.max(1, density * 0.8f));
        tickPaint.setStyle(Paint.Style.STROKE);

        labelPaint.setColor(0xFF555555);
        labelPaint.setTextSize(7 * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        indicatorPaint.setColor(0xFFFFFFFF);
        indicatorPaint.setStrokeWidth(1.5f * density);
        indicatorPaint.setStyle(Paint.Style.STROKE);

        zoneBgPaint.setStyle(Paint.Style.FILL);

        zoneLinePaint.setStyle(Paint.Style.STROKE);
        zoneLinePaint.setStrokeWidth(density * 0.5f);
    }

    public void setRange(float min, float max) { minVal = min; maxVal = max; invalidate(); }
    public void setTicks(float[] v, String[] l) { ticks = v; tickLabels = l; invalidate(); }
    public void addZone(float s, float e, int c) { zones.add(new Zone(s, e, c)); invalidate(); }
    public void setAnchor(float a) { anchorVal = a; }
    public void setValue(float v) { curVal = v; invalidate(); }
    public void setShowLabels(boolean show) { showLabels = show; invalidate(); }

    private float valToX(float v, float left, float w) {
        float ratio = (v - minVal) / (maxVal - minVal);
        return left + ratio * w;
    }

    private int zoneColor(float v) {
        for (Zone z : zones) {
            if (v >= z.start && v <= z.end) return z.color;
        }
        return 0xFF00D8FF;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pL = getPaddingLeft(), pR = getPaddingRight();
        float pT = getPaddingTop(), pB = getPaddingBottom();
        float barW = getWidth() - pL - pR;
        float barLeft = pL;
        float barH = 3 * density;
        float barTop = pT;

        // Background bar
        canvas.drawRect(barLeft, barTop, barLeft + barW, barTop + barH, bgPaint);

        // Zone backgrounds (subtle)
        for (Zone z : zones) {
            float x1 = valToX(Math.max(z.start, minVal), barLeft, barW);
            float x2 = valToX(Math.min(z.end, maxVal), barLeft, barW);
            zoneBgPaint.setColor(z.color);
            zoneBgPaint.setAlpha(25);
            canvas.drawRect(x1, barTop, x2, barTop + barH, zoneBgPaint);
        }
        zoneBgPaint.setAlpha(255);

        // Zone boundary lines
        for (int i = 0; i < zones.size() - 1; i++) {
            float x = valToX(zones.get(i).end, barLeft, barW);
            zoneLinePaint.setColor(zones.get(i + 1).color);
            zoneLinePaint.setAlpha(80);
            canvas.drawLine(x, barTop - density, x, barTop + barH + density, zoneLinePaint);
        }
        zoneLinePaint.setAlpha(255);

        // Filled bar from anchor to current value
        if (!Float.isNaN(curVal)) {
            float clamped = Math.max(minVal, Math.min(maxVal, curVal));
            float ax = valToX(Math.max(minVal, Math.min(maxVal, anchorVal)), barLeft, barW);
            float vx = valToX(clamped, barLeft, barW);
            fillPaint.setColor(zoneColor(curVal));
            fillPaint.setAlpha(200);
            canvas.drawRect(Math.min(ax, vx), barTop, Math.max(ax, vx), barTop + barH, fillPaint);
            fillPaint.setAlpha(255);

            // Value indicator (bright vertical line)
            canvas.drawLine(vx, barTop - density, vx, barTop + barH + density, indicatorPaint);
        }

        // Tick marks and labels
        float tickLen = 3 * density;
        for (int i = 0; i < ticks.length; i++) {
            float x = valToX(ticks[i], barLeft, barW);
            canvas.drawLine(x, barTop - tickLen, x, barTop + barH + tickLen, tickPaint);

            if (showLabels && tickLabels != null && i < tickLabels.length
                    && tickLabels[i] != null && !tickLabels[i].isEmpty()) {
                canvas.drawText(tickLabels[i], x,
                        barTop + barH + tickLen + 7 * density, labelPaint);
            }
        }
    }
}
