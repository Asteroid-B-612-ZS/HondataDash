package io.github.asteroidb612zs.hondatadash;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Honda/FL5-inspired 5+5 rev-indicator bank.
 *
 * RC4 product pass: each element is a compact horizontal capsule rather than a
 * small round dot. The wider optical footprint is easier to acquire in daylight
 * and at driving distance while keeping the proven five-stage outside->centre
 * state machine unchanged. The two banks are mirrored around the exact header
 * centre and retain a generous centre breathing space.
 */
final class ShiftLightRenderer {
    // RC7 daylight pass: larger optical footprint only; RPM stage semantics are frozen.
    static final float LAMP_WIDTH_DP = 30f;
    static final float LAMP_HEIGHT_DP = 18f;
    static final float CORE_WIDTH_DP = 23f;
    static final float CORE_HEIGHT_DP = 11f;
    static final float NORMAL_GAP_DP = 8f;
    static final float CENTER_GAP_DP = 80f;
    static final float GLOW_X_DP = 4f;
    static final float GLOW_Y_DP = 3f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final LinearGradient[] gradients = new LinearGradient[5];
    private float gradientTop = -1f, gradientBottom = -1f;

    /** stage 0..5; redPhase=false dims only the central red pair while flashing. */
    void draw(Canvas c, float left, float top, float width, float height, float density,
              int stage, boolean redPhase) {
        int lampW = Math.max(12, Math.round(LAMP_WIDTH_DP * density));
        int lampH = Math.max(9, Math.round(LAMP_HEIGHT_DP * density));
        int maxByHeight = Math.max(9, (int) height - Math.max(4, Math.round(8f * density)));
        lampH = Math.min(lampH, maxByHeight);

        int coreW = Math.min(lampW - 6, Math.max(7, Math.round(CORE_WIDTH_DP * density)));
        int coreH = Math.min(lampH - 4, Math.max(5, Math.round(CORE_HEIGHT_DP * density)));
        int gap = Math.max(4, Math.round(NORMAL_GAP_DP * density));
        int centerGap = Math.max(gap * 5, Math.round(CENTER_GAP_DP * density));
        int glowX = Math.max(1, Math.round(GLOW_X_DP * density));
        int glowY = Math.max(1, Math.round(GLOW_Y_DP * density));

        int total = lampW * 10 + gap * 8 + centerGap;
        if (total > width) {
            // Narrow-window fallback: compress whitespace before shrinking the
            // lamps, but preserve an unmistakable two-bank centre separation.
            float scale = Math.max(.45f, width / (float) total);
            gap = Math.max(3, Math.round(gap * scale));
            centerGap = Math.max(gap * 5, Math.round(centerGap * scale));
            total = lampW * 10 + gap * 8 + centerGap;
            if (total > width) {
                int available = Math.max(1, (int) width - gap * 8 - centerGap);
                lampW = Math.max(10, available / 10);
                coreW = Math.min(coreW, Math.max(5, lampW - 6));
                total = lampW * 10 + gap * 8 + centerGap;
            }
        }
        if (lampW < 10 || lampH < 8 || coreW < 5 || coreH < 4 || total <= 0) return;

        float x0 = Math.round(left + (width - total) / 2f);
        float y0 = Math.round(top + (height - lampH) / 2f);
        float coreInsetX = (lampW - coreW) / 2f;
        float coreInsetY = (lampH - coreH) / 2f;
        float coreTop = y0 + coreInsetY;
        float coreBottom = coreTop + coreH;

        if (gradientTop != coreTop || gradientBottom != coreBottom) {
            gradientTop = coreTop;
            gradientBottom = coreBottom;
            for (int pair = 0; pair < 5; pair++) {
                int base = DashboardPalette.RPM_PAIR[pair];
                int light = Color.argb(255,
                        Math.min(255, Color.red(base) + 28),
                        Math.min(255, Color.green(base) + 28),
                        Math.min(255, Color.blue(base) + 28));
                gradients[pair] = new LinearGradient(0, coreTop, 0, coreBottom,
                        light, base, Shader.TileMode.CLAMP);
            }
        }

        for (int i = 0; i < 10; i++) {
            int groupIndex = i < 5 ? i : i - 5;
            float groupOrigin = i < 5
                    ? x0
                    : x0 + 5 * lampW + 4 * gap + centerGap;
            float x = groupOrigin + groupIndex * (lampW + gap);
            int pair = Math.min(i, 9 - i);
            boolean bright = pair < stage;
            if (pair == 4 && !redPhase) bright = false;

            float lampRadius = lampH / 2f;

            // Restrained optical bloom. It is deliberately stronger than RC3
            // because real-car daylight acquisition matters more than simulator
            // subtlety, yet remains local to each physical lamp.
            if (bright) {
                rect.set(Math.max(left, x - glowX), Math.max(top, y0 - glowY),
                        Math.min(left + width, x + lampW + glowX),
                        Math.min(top + height, y0 + lampH + glowY));
                paint.setShader(null);
                paint.setColor(DashboardPalette.RPM_PAIR[pair]);
                paint.setAlpha(pair == 4 ? 104 : 62);
                c.drawRoundRect(rect, (lampH + 2f * glowY) / 2f,
                        (lampH + 2f * glowY) / 2f, paint);
            }

            // Dark moulded bezel. Lit lamps pick up a little reflected light so
            // the whole element appears active instead of only a tiny core dot.
            rect.set(x, y0, x + lampW, y0 + lampH);
            paint.setShader(null);
            paint.setColor(bright ? 0xFF78909A : 0xFF30434D);
            paint.setAlpha(255);
            c.drawRoundRect(rect, lampRadius, lampRadius, paint);

            // Recessed inner seat.
            float seatX = Math.max(1f, Math.round(1.5f * density));
            float seatY = Math.max(1f, Math.round(1.5f * density));
            rect.set(x + seatX, y0 + seatY, x + lampW - seatX, y0 + lampH - seatY);
            paint.setColor(0xFF0A1318);
            c.drawRoundRect(rect, Math.max(1f, (lampH - 2f * seatY) / 2f),
                    Math.max(1f, (lampH - 2f * seatY) / 2f), paint);

            // Wide LED core: easier to read at a glance than the RC3 round point.
            float coreLeft = x + coreInsetX;
            rect.set(coreLeft, coreTop, coreLeft + coreW, coreBottom);
            paint.setColor(DashboardPalette.RPM_PAIR[pair]);
            paint.setAlpha(bright ? 255 : 38);
            paint.setShader(bright ? gradients[pair] : null);
            c.drawRoundRect(rect, coreH / 2f, coreH / 2f, paint);
            paint.setShader(null);
            paint.setAlpha(255);

            // One restrained top reflection keeps the capsule crisp on the
            // low-resolution LCD without becoming a neon/glass effect.
            if (bright && coreW >= 10 && coreH >= 6) {
                float hiH = Math.max(1f, Math.round(density));
                float hiLeft = coreLeft + coreW * .18f;
                float hiRight = coreLeft + coreW * .58f;
                rect.set(hiLeft, coreTop + coreH * .15f, hiRight,
                        coreTop + coreH * .15f + hiH);
                paint.setColor(0x72FFFFFF);
                c.drawRoundRect(rect, hiH / 2f, hiH / 2f, paint);
            }
        }
    }
}
