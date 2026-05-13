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
 * 彩虹转速灯条 - 根据转速从左到右逐渐亮起。
 * 5500 RPM 后全亮并闪烁。
 */
public class ShiftLightView extends View {

    private static final int LED_COUNT = 15;
    private static final float RPM_MIN = 1000;
    private static final float RPM_FLASH = 5500;
    private static final long FLASH_MS = 120;

    private static final int[] LED_COLORS = {
        0xFF3FB950, 0xFF3FB950, 0xFF3FB950, 0xFF3FB950,  // green
        0xFF7BC840, 0xFF7BC840,                             // lime
        0xFFD29922, 0xFFD29922,                             // yellow
        0xFFFF8800, 0xFFFF8800,                             // orange
        0xFFFF4422, 0xFFFF4422,                             // red-orange
        0xFFFF0000, 0xFFFF0000, 0xFFFF0000                  // red
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

        float gap = 2 * density;
        float ledW = (w - (LED_COUNT - 1) * gap) / LED_COUNT;
        float radius = Math.min(ledW, h) / 2;

        int litCount;
        if (rpm >= RPM_FLASH) {
            litCount = LED_COUNT;
        } else if (rpm <= RPM_MIN) {
            litCount = 0;
        } else {
            litCount = Math.round((rpm - RPM_MIN) / (RPM_FLASH - RPM_MIN) * LED_COUNT);
            litCount = Math.max(0, Math.min(LED_COUNT, litCount));
        }

        for (int i = 0; i < LED_COUNT; i++) {
            float x = pL + i * (ledW + gap);
            boolean isLit = i < litCount;

            rect.set(x, pT, x + ledW, pT + h);

            if (isLit) {
                int color = LED_COLORS[i];
                boolean showBright = !(rpm >= RPM_FLASH && !flashOn);

                if (showBright) {
                    // glow
                    float gp = 2 * density;
                    glowRect.set(x - gp, pT - gp, x + ledW + gp, pT + h + gp);
                    glowPaint.setColor(color);
                    glowPaint.setAlpha(60);
                    canvas.drawRoundRect(glowRect, radius + gp, radius + gp, glowPaint);

                    // LED
                    ledPaint.setColor(color);
                    ledPaint.setAlpha(255);
                } else {
                    // flash off: dim
                    ledPaint.setColor(color);
                    ledPaint.setAlpha(40);
                }
            } else {
                ledPaint.setColor(0xFF444444);
                ledPaint.setAlpha(255);
            }

            canvas.drawRoundRect(rect, radius, radius, ledPaint);
        }
    }
}
