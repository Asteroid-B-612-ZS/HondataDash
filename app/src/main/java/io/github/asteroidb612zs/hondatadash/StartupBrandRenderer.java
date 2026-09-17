package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/** Reusable vector-only brand stage; the signature is intentionally case-sensitive. */
final class StartupBrandRenderer {
    private final HondaMark mark = new HondaMark();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface bold, regular;
    StartupBrandRenderer(Context context) {
        bold = DashboardTypeface.get(context, false);
        regular = DashboardTypeface.getScale(context);
    }
    private int alpha(int color, float amount) {
        return (Math.round(255 * Math.max(0, Math.min(1, amount))) << 24) | (color & 0xFFFFFF);
    }
    private void text(Canvas c, String text, float x, float baseline, float size, int color, boolean heavy) {
        paint.setTypeface(heavy ? bold : regular); paint.setTextSize(size); paint.setColor(color);
        c.drawText(text, x - paint.measureText(text) / 2f, baseline, paint);
    }
    void draw(Canvas c, int width, int height, long elapsed) {
        float unit = Math.min(width / 800f, height / 480f);
        int save = c.save(); c.translate(width / 2f, height / 2f); c.scale(unit, unit);
        float brand = StartupSequence.brand(elapsed);
        float slash = StartupSequence.ease(elapsed, 100, 320);
        paint.setColor(alpha(DashboardPalette.HONDA_RED, brand * (1f - StartupSequence.ease(elapsed, 950, 350))));
        paint.setStrokeWidth(1.5f);
        c.drawLine(-92, 101, -92 + 195 * slash, 101 - 260 * slash, paint);
        float logo = StartupSequence.ease(elapsed, 350, 380), opticalScale = .96f + .04f * logo;
        int opt = c.save(); c.scale(opticalScale, opticalScale);
        mark.draw(c, -47, -98, 94, 81, alpha(DashboardPalette.PRIMARY, logo * brand));
        c.restoreToCount(opt);
        text(c, "HONDA", 0, 23, 36, alpha(DashboardPalette.PRIMARY, StartupSequence.ease(elapsed, 550, 350) * brand), true);
        text(c, "P E R F O R M A N C E   T E L E M E T R Y", 0, 53, 12,
                alpha(DashboardPalette.SECONDARY, StartupSequence.ease(elapsed, 750, 320) * brand), false);
        float signature = StartupSequence.ease(elapsed, 900, 320), baseline = 90 + 4 * (1f - signature);
        paint.setTextSize(15); paint.setTypeface(regular);
        float prefix = paint.measureText("Designed by ");
        paint.setTypeface(bold); float name = paint.measureText("ZhouQiZhi");
        float left = -(prefix + name) / 2f;
        paint.setTypeface(regular); paint.setColor(alpha(0xFF8D999E, signature * brand));
        c.drawText("Designed by ", left, baseline, paint);
        paint.setTypeface(bold); paint.setColor(alpha(0xFFD6DDDF, signature * brand));
        c.drawText("ZhouQiZhi", left + prefix, baseline, paint);
        c.restoreToCount(save);
    }
}
