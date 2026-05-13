package com.hondata.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 赛车风格刻度进度条。
 * 刻度标记在数据条上方, 数据条加粗。
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

        tickPaint.setColor(0xFF666666);
        tickPaint.setStrokeWidth(Math.max(1, density * 0.8f));
        tickPaint.setStyle(Paint.Style.STROKE);

        labelPaint.setColor(0xFF999999);
        labelPaint.setTextSize(11 * density);
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

        // 数据条在底部, 高度加倍
        float barH = 10 * density;
        float barBottom = getHeight() - pB;
        float barTop = barBottom - barH;

        // 刻度线在数据条上方
        float tickLen = 5 * density;
        float tickBottom = barTop - density;
        float tickTop = tickBottom - tickLen;

        // 刻度数字在刻度线上方
        float labelY = tickTop - density;

        // 1. 先画刻度数字和刻度线 (上方)
        for (int i = 0; i < ticks.length; i++) {
            float x = valToX(ticks[i], barLeft, barW);

            if (showLabels && tickLabels != null && i < tickLabels.length
                    && tickLabels[i] != null && !tickLabels[i].isEmpty()) {
                // 边缘刻度文字对齐修正: 左边界靠左对齐, 右边界靠右对齐
                Paint.Align saved = labelPaint.getTextAlign();
                float tw = labelPaint.measureText(tickLabels[i]);
                if (x - tw / 2 < pL) {
                    labelPaint.setTextAlign(Paint.Align.LEFT);
                } else if (x + tw / 2 > getWidth() - pR) {
                    labelPaint.setTextAlign(Paint.Align.RIGHT);
                }
                canvas.drawText(tickLabels[i], x, labelY, labelPaint);
                labelPaint.setTextAlign(saved);
            }

            canvas.drawLine(x, tickTop, x, tickBottom, tickPaint);
        }

        // 2. 数据条背景
        canvas.drawRect(barLeft, barTop, barLeft + barW, barBottom, bgPaint);

        // 3. 颜色区间背景 (半透明)
        for (Zone z : zones) {
            float x1 = valToX(Math.max(z.start, minVal), barLeft, barW);
            float x2 = valToX(Math.min(z.end, maxVal), barLeft, barW);
            zoneBgPaint.setColor(z.color);
            zoneBgPaint.setAlpha(25);
            canvas.drawRect(x1, barTop, x2, barBottom, zoneBgPaint);
        }
        zoneBgPaint.setAlpha(255);

        // 区间分界线
        for (int i = 0; i < zones.size() - 1; i++) {
            float x = valToX(zones.get(i).end, barLeft, barW);
            zoneLinePaint.setColor(zones.get(i + 1).color);
            zoneLinePaint.setAlpha(80);
            canvas.drawLine(x, barTop, x, barBottom, zoneLinePaint);
        }
        zoneLinePaint.setAlpha(255);

        // 4. 填充条 (锚点到当前值)
        if (!Float.isNaN(curVal)) {
            float clamped = Math.max(minVal, Math.min(maxVal, curVal));
            float ax = valToX(Math.max(minVal, Math.min(maxVal, anchorVal)), barLeft, barW);
            float vx = valToX(clamped, barLeft, barW);
            fillPaint.setColor(zoneColor(curVal));
            fillPaint.setAlpha(200);
            canvas.drawRect(Math.min(ax, vx), barTop, Math.max(ax, vx), barBottom, fillPaint);
            fillPaint.setAlpha(255);

            // 当前值指示线
            canvas.drawLine(vx, barTop, vx, barBottom, indicatorPaint);
        }
    }
}
