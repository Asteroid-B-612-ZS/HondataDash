package com.hondata.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 弧形仪表盘 View - 赛车仪表风格。
 *
 * 绘制: 底部弧形轨道 + 彩色填充弧 + 中央数值 + 标签 + 单位 + 刻度线
 * 支持: 进度颜色自定义、>80% 自动警告变色、圆角弧线、底部渐变分隔线
 */
public class GaugeView extends View {

    /** 弧形参数 - 240° 弧 (从左下 150° 到右下 30°) */
    private static final float START_ANGLE = 150f;
    private static final float SWEEP_ANGLE = 240f;

    /** 数据 */
    private double minValue = 0.0;
    private double maxValue = 8000.0;
    private float progress = 0f; // 0~1

    /** 显示文字 */
    private String labelText = "";
    private String valueText = "--";
    private String unitText = "";

    /** 颜色 */
    private int arcColor = 0xFF3FB950;
    private int warningColor = 0xFFF85149;
    private boolean enableWarning = false;

    /** 画笔 */
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 弧形区域 */
    private final RectF arcRect = new RectF();
    private int arcPadding = 14;

    public GaugeView(Context context) {
        super(context);
        init();
    }

    public GaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GaugeView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        valuePaint.setColor(0xFFFFFFFF);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setFakeBoldText(true);

        labelPaint.setColor(0xFF999999);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);

        unitPaint.setColor(0xFF666666);
        unitPaint.setTextAlign(Paint.Align.CENTER);

        tickPaint.setColor(0xFF333333);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(1.5f);

        // 底部渐变分隔线
        dividerPaint.setStyle(Paint.Style.FILL);
    }

    // ===== Setter =====

    public void setRange(double min, double max) {
        this.minValue = min;
        this.maxValue = max;
    }

    public void setColor(int color) {
        this.arcColor = color;
    }

    public void setWarningEnabled(boolean enabled) {
        this.enableWarning = enabled;
    }

    public void setLabel(String label) {
        this.labelText = label;
        invalidate();
    }

    public void setUnit(String unit) {
        this.unitText = unit;
        invalidate();
    }

    public void setValue(double val) {
        double range = maxValue - minValue;
        if (range > 0) {
            this.progress = (float) ((val - minValue) / range);
            this.progress = Math.max(0f, Math.min(1f, this.progress));
        }
        invalidate();
    }

    public void setDisplayText(String text) {
        this.valueText = text;
        invalidate();
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 动态计算弧线粗细
        float arcStroke = Math.min(w, h) * 0.055f;
        float halfStroke = arcStroke / 2f;

        // 布局: 上方留空给标签，下方留空给单位 + 分隔线
        float topMargin = h * 0.10f;
        float bottomMargin = h * 0.20f;

        float left = arcPadding + halfStroke;
        float top = topMargin + halfStroke;
        float right = w - arcPadding - halfStroke;
        float bottom = h - bottomMargin - halfStroke;

        // 限制弧形宽高比，保持视觉协调
        float arcW = right - left;
        float arcH = bottom - top;
        if (arcW > arcH * 1.4f) {
            float targetW = arcH * 1.4f;
            float cx = (left + right) / 2f;
            left = cx - targetW / 2f;
            right = cx + targetW / 2f;
        }

        arcRect.set(left, top, right, bottom);

        // 设置画笔宽度
        trackPaint.setStrokeWidth(arcStroke);
        arcPaint.setStrokeWidth(arcStroke);
        glowPaint.setStrokeWidth(arcStroke + 8f);

        // 计算当前弧色（含警告渐变）
        int currentColor = arcColor;
        if (enableWarning && progress > 0.75f) {
            float t = (progress - 0.75f) / 0.25f;
            currentColor = lerpColor(arcColor, warningColor, t);
        }

        float sweepFill = progress * SWEEP_ANGLE;

        // 1. 发光层
        if (sweepFill > 2f) {
            glowPaint.setColor(currentColor);
            glowPaint.setAlpha(35);
            canvas.drawArc(arcRect, START_ANGLE, sweepFill, false, glowPaint);
        }

        // 2. 轨道弧
        trackPaint.setColor(0xFF1C1C1C);
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint);

        // 3. 填充弧
        if (sweepFill > 2f) {
            arcPaint.setColor(currentColor);
            canvas.drawArc(arcRect, START_ANGLE, sweepFill, false, arcPaint);
        }

        // 4. 弧形两端小圆点
        if (sweepFill > 2f) {
            drawArcEndpoint(canvas, START_ANGLE, currentColor, arcStroke);
            drawArcEndpoint(canvas, START_ANGLE + sweepFill, currentColor, arcStroke);
        }

        // 5. 刻度线
        drawTickMarks(canvas, arcStroke);

        // 6. 文字
        float centerX = w / 2f;
        float arcCenterY = arcRect.centerY();

        // 标签
        float labelSize = Math.min(w, h) * 0.060f;
        labelPaint.setTextSize(labelSize);
        canvas.drawText(labelText, centerX, top - arcStroke * 0.4f, labelPaint);

        // 数值
        float valueSize = Math.min(w, h) * 0.20f;
        valuePaint.setTextSize(valueSize);
        float valueY = arcCenterY + valueSize * 0.05f;
        canvas.drawText(valueText, centerX, valueY, valuePaint);

        // 单位
        float unitSize = Math.min(w, h) * 0.065f;
        unitPaint.setTextSize(unitSize);
        canvas.drawText(unitText, centerX, valueY + unitSize * 1.8f, unitPaint);

        // 7. 底部渐变分隔线
        drawBottomDivider(canvas, w, h, currentColor);
    }

    /** 在弧形端点画一个小圆点 */
    private void drawArcEndpoint(Canvas canvas, float angle, int color, float arcStroke) {
        float cx = arcRect.centerX();
        float cy = arcRect.centerY();
        float radius = arcRect.width() / 2f;

        double rad = Math.toRadians(angle);
        float px = cx + (float) (radius * Math.cos(rad));
        float py = cy + (float) (radius * Math.sin(rad));

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(color);
        dotPaint.setAlpha(180);
        canvas.drawCircle(px, py, arcStroke * 0.25f, dotPaint);
    }

    /** 绘制刻度标记 */
    private void drawTickMarks(Canvas canvas, float arcStroke) {
        float cx = arcRect.centerX();
        float cy = arcRect.centerY();
        float outerRadius = arcRect.width() / 2f + arcStroke * 0.7f;
        float tickLen = arcStroke * 0.35f;

        for (int i = 0; i <= 8; i++) {
            float fraction = i / 8f;
            double angle = Math.toRadians(START_ANGLE + fraction * SWEEP_ANGLE);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            float x1 = cx + outerRadius * cos;
            float y1 = cy + outerRadius * sin;
            float x2 = cx + (outerRadius + tickLen) * cos;
            float y2 = cy + (outerRadius + tickLen) * sin;

            // 主要刻度线（0%, 25%, 50%, 75%, 100%）稍亮
            tickPaint.setColor(i % 2 == 0 ? 0xFF444444 : 0xFF2A2A2A);
            tickPaint.setStrokeWidth(i % 2 == 0 ? 2f : 1.2f);
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }
    }

    /** 底部渐变分隔线 */
    private void drawBottomDivider(Canvas canvas, int w, int h, int color) {
        float y = h - 2f;
        float lineH = 2f;

        // 使用主色到透明的渐变
        int transparentColor = color & 0x00FFFFFF;
        LinearGradient gradient = new LinearGradient(
            0, y, w, y,
            new int[]{transparentColor, color, color, transparentColor},
            new float[]{0f, 0.15f, 0.85f, 1f},
            Shader.TileMode.CLAMP
        );
        dividerPaint.setShader(gradient);
        dividerPaint.setAlpha(60);
        canvas.drawRect(0, y, w, y + lineH, dividerPaint);
        dividerPaint.setShader(null);
    }

    // ===== 工具 =====

    /** 颜色线性插值 */
    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t);
        int rb = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (rr << 16) | (rg << 8) | rb;
    }
}
