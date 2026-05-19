package com.hondata.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * 6-LED 转速灯条 - 三阶段渐进 + 断油闪烁。
 *
 * 阶段0: <4000 全灭（极暗轮廓）
 * 阶段1: 4000-4999 LED1@4000 LED2@4500 绿色
 * 阶段2: 5000-5999 LED3@5000 LED4@5500 黄色
 * 阶段3: 6000-6399 LED5@6000 LED6@6200 红色
 * 阶段4: ≥6400  全亮 10Hz闪烁
 */
public class ShiftLightView extends View {

    private static final int LED_COUNT = 6;

    // 各 LED 点亮的 RPM 阈值
    private static final float[] RPM_THRESHOLDS = {
        4000, 4500, 5000, 5500, 6000, 6200
    };

    // 阶段4: 全部闪烁
    private static final float RPM_FLASH = 6400;
    private static final long FLASH_MS = 50; // 10Hz

    // LED 颜色: 绿 绿 黄 黄 红 红
    private static final int[] LED_COLORS = {
        0xFF3FB950, 0xFF3FB950,
        0xFFD29922, 0xFFD29922,
        0xFFFF2200, 0xFFFF2200
    };

    private float rpm;
    private boolean flashOn = true;
    private boolean flashing = false;
    private float density;

    private final Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF glowRect = new RectF();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable flashTick = new Runnable() {
        @Override
        public void run() {
            flashOn = !flashOn;
            invalidate();
            handler.postDelayed(this, FLASH_MS);
        }
    };

    public ShiftLightView(Context ctx) { super(ctx); init(ctx); }
    public ShiftLightView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(ctx); }
    public ShiftLightView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init(ctx);
    }

    private void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;
    }

    public void setRpm(float r) {
        this.rpm = r;
        boolean shouldFlash = rpm >= RPM_FLASH;
        if (shouldFlash && !flashing) {
            flashing = true;
            flashOn = true;
            handler.removeCallbacks(flashTick);
            handler.post(flashTick);
        } else if (!shouldFlash && flashing) {
            flashing = false;
            handler.removeCallbacks(flashTick);
            flashOn = true;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(flashTick);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pL = getPaddingLeft(), pR = getPaddingRight();
        float pT = getPaddingTop(), pB = getPaddingBottom();
        float w = getWidth() - pL - pR;
        float h = getHeight() - pT - pB;

        // 大间距 + 大灯珠
        float gap = 4 * density;
        float ledW = (w - (LED_COUNT - 1) * gap) / LED_COUNT;
        float ledH = h;
        float radius = Math.min(ledW, ledH) / 2;

        // 计算每颗 LED 是否点亮
        boolean[] lit = new boolean[LED_COUNT];
        for (int i = 0; i < LED_COUNT; i++) {
            lit[i] = rpm >= RPM_THRESHOLDS[i];
        }

        // 阶段4: 全亮（闪烁由 flashOn 控制）
        boolean allLit = rpm >= RPM_FLASH;

        for (int i = 0; i < LED_COUNT; i++) {
            float x = pL + i * (ledW + gap);
            rect.set(x, pT, x + ledW, pT + ledH);

            boolean isLit = allLit || lit[i];

            if (isLit) {
                int color = LED_COLORS[i];

                // 闪烁时: flashOn 控制亮/灭
                boolean showBright;
                if (allLit) {
                    showBright = flashOn;
                } else {
                    showBright = true;
                }

                if (showBright) {
                    // 光晕
                    float gp = 3 * density;
                    glowRect.set(x - gp, pT - gp, x + ledW + gp, pT + ledH + gp);
                    glowPaint.setColor(color);
                    glowPaint.setAlpha(80);
                    canvas.drawRoundRect(glowRect, radius + gp, radius + gp, glowPaint);

                    // LED 亮
                    ledPaint.setColor(color);
                    ledPaint.setAlpha(255);
                } else {
                    // 闪烁灭: 暗
                    ledPaint.setColor(color);
                    ledPaint.setAlpha(30);
                }
            } else {
                // 未点亮: 极暗轮廓
                ledPaint.setColor(0xFF333333);
                ledPaint.setAlpha(255);
            }

            canvas.drawRoundRect(rect, radius, radius, ledPaint);
        }
    }
}
