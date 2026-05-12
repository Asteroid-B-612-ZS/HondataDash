package com.hondata.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 四轮动态图标 - 顶视图示意图。
 * 显示 FL / FR / RL / RR 四个车轮，打滑时高亮红色。
 */
public class WheelView extends View {

    private float[] slip = {0, 0, 0, 0}; // FL, FR, RL, RR
    private float threshold = 2.0f; // 打滑阈值 %

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float density;

    public WheelView(Context ctx) { super(ctx); init(ctx); }
    public WheelView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(ctx); }
    public WheelView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init(ctx);
    }

    private void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;

        bodyPaint.setColor(0xFF1A1A1A);
        bodyPaint.setStyle(Paint.Style.FILL);

        wheelPaint.setColor(0xFF333333);
        wheelPaint.setStyle(Paint.Style.FILL);

        slipPaint.setColor(0xFFFF4444);
        slipPaint.setStyle(Paint.Style.FILL);

        glowPaint.setColor(0xFFFF4444);
        glowPaint.setStyle(Paint.Style.FILL);

        axlePaint.setColor(0xFF222222);
        axlePaint.setStyle(Paint.Style.STROKE);
        axlePaint.setStrokeWidth(density);

        labelPaint.setColor(0xFF555555);
        labelPaint.setTextSize(7 * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        valueLabelPaint.setColor(0xFF777777);
        valueLabelPaint.setTextSize(6 * density);
        valueLabelPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * 设置四轮打滑值 (%)
     */
    public void setSlip(float fl, float fr, float rl, float rr) {
        slip[0] = fl; slip[1] = fr; slip[2] = rl; slip[3] = rr;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();

        float padX = 10 * density;
        float padY = 6 * density;

        float innerW = w - 2 * padX;
        float innerH = h - 2 * padY;

        // 车轮尺寸
        float wheelR = Math.min(innerW / 6, innerH / 5);
        wheelR = Math.max(wheelR, 4 * density);

        // 四轮位置
        float leftX = padX + wheelR;
        float rightX = w - padX - wheelR;
        float topY = padY + wheelR + 6 * density; // 留出标签空间
        float bottomY = h - padY - wheelR;

        float[][] wheels = {
            {leftX, topY},     // FL
            {rightX, topY},    // FR
            {leftX, bottomY},  // RL
            {rightX, bottomY}  // RR
        };

        // 车身轮廓 (连接线)
        float bodyL = leftX - wheelR * 0.5f;
        float bodyR = rightX + wheelR * 0.5f;
        float bodyT = (topY + bottomY) * 0.38f;
        float bodyB = (topY + bottomY) * 0.62f;

        // 画车身
        canvas.drawRoundRect(new RectF(bodyL, bodyT, bodyR, bodyB),
            3 * density, 3 * density, bodyPaint);

        // 画车轴
        canvas.drawLine(leftX, topY, leftX, bottomY, axlePaint);   // 左侧
        canvas.drawLine(rightX, topY, rightX, bottomY, axlePaint); // 右侧
        canvas.drawLine(leftX, topY, rightX, topY, axlePaint);     // 前轴
        canvas.drawLine(leftX, bottomY, rightX, bottomY, axlePaint); // 后轴

        // 画四个车轮
        String[] labels = {"FL", "FR", "RL", "RR"};

        for (int i = 0; i < 4; i++) {
            float wx = wheels[i][0];
            float wy = wheels[i][1];
            boolean isSlipping = slip[i] > threshold;

            if (isSlipping) {
                // 光晕效果
                glowPaint.setAlpha(40);
                canvas.drawCircle(wx, wy, wheelR + 4 * density, glowPaint);
                glowPaint.setAlpha(20);
                canvas.drawCircle(wx, wy, wheelR + 8 * density, glowPaint);
                glowPaint.setAlpha(255);
            }

            // 车轮
            Paint p = isSlipping ? slipPaint : wheelPaint;
            canvas.drawCircle(wx, wy, wheelR, p);

            // 标签
            canvas.drawText(labels[i], wx, wy - wheelR - 2 * density, labelPaint);

            // 打滑数值
            if (slip[i] > 0.1f) {
                String val = String.format("%.0f%%", slip[i]);
                valueLabelPaint.setColor(isSlipping ? 0xFFFF6666 : 0xFF666666);
                canvas.drawText(val, wx, wy + wheelR + 8 * density, valueLabelPaint);
            }
        }

        // 方向标识
        labelPaint.setColor(0xFF333333);
        labelPaint.setTextSize(6 * density);
        canvas.drawText("FRONT", (leftX + rightX) / 2, bodyT - 1 * density, labelPaint);
    }
}
