package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Tiny code-drawn OEM-style telemetry pictograms for the footer.
 * API 17 compatible; no bitmap/vector drawable resources are required.
 */
public final class MiniIconView extends View {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String mode = "cyl";

    public MiniIconView(Context c) { super(c); init(null); }
    public MiniIconView(Context c, AttributeSet a) { super(c, a); init(a); }
    public MiniIconView(Context c, AttributeSet a, int s) { super(c, a, s); init(a); }

    private void init(AttributeSet attrs) {
        if (attrs != null) {
            String value = attrs.getAttributeValue(ANDROID_NS, "tag");
            if (value != null && value.length() > 0) mode = value;
        }
        paint.setColor(DashboardPalette.PRIMARY);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(Math.max(1f, 1.15f * d));
        paint.setColor(DashboardPalette.PRIMARY);
        paint.setStyle(Paint.Style.STROKE);

        if ("fuel".equals(mode)) drawFuel(canvas, w, h);
        else if ("turbo".equals(mode)) drawTurbo(canvas, w, h);
        else if ("throttle".equals(mode)) drawThrottle(canvas, w, h);
        else drawCylinder(canvas, w, h);
    }

    private void drawCylinder(Canvas c, float w, float h) {
        float l = w * .25f, r = w * .75f, t = h * .27f, b = h * .82f;
        Path p = new Path();
        p.moveTo(l, t); p.lineTo(r, t); p.lineTo(r * .98f, b); p.lineTo(l * 1.02f, b); p.close();
        c.drawPath(p, paint);
        c.drawLine(w * .18f, h * .18f, w * .82f, h * .18f, paint);
        c.drawLine(w * .34f, h * .10f, w * .66f, h * .10f, paint);
        c.drawLine(w * .35f, h * .90f, w * .65f, h * .90f, paint);
    }

    private void drawFuel(Canvas c, float w, float h) {
        Path p = new Path();
        p.moveTo(w * .17f, h * .18f); p.lineTo(w * .61f, h * .18f);
        p.lineTo(w * .61f, h * .82f); p.lineTo(w * .17f, h * .82f); p.close();
        c.drawPath(p, paint);
        c.drawLine(w * .25f, h * .31f, w * .53f, h * .31f, paint);
        Path hose = new Path();
        hose.moveTo(w * .61f, h * .31f); hose.cubicTo(w * .88f, h * .28f, w * .86f, h * .60f, w * .77f, h * .63f);
        hose.lineTo(w * .77f, h * .78f);
        c.drawPath(hose, paint);
        c.drawLine(w * .10f, h * .86f, w * .68f, h * .86f, paint);
    }

    private void drawTurbo(Canvas c, float w, float h) {
        // Compact snail-shell suggestion using only Path primitives.
        Path p = new Path();
        p.moveTo(w * .78f, h * .50f);
        p.cubicTo(w * .78f, h * .20f, w * .54f, h * .12f, w * .34f, h * .22f);
        p.cubicTo(w * .10f, h * .35f, w * .16f, h * .67f, w * .37f, h * .76f);
        p.cubicTo(w * .55f, h * .84f, w * .71f, h * .71f, w * .66f, h * .55f);
        p.cubicTo(w * .62f, h * .42f, w * .48f, h * .39f, w * .40f, h * .47f);
        p.cubicTo(w * .33f, h * .54f, w * .39f, h * .65f, w * .49f, h * .63f);
        c.drawPath(p, paint);
        c.drawLine(w * .76f, h * .50f, w * .93f, h * .50f, paint);
    }

    private void drawThrottle(Canvas c, float w, float h) {
        Path ring = new Path();
        ring.moveTo(w * .50f, h * .12f);
        ring.cubicTo(w * .79f, h * .12f, w * .88f, h * .34f, w * .88f, h * .50f);
        ring.cubicTo(w * .88f, h * .75f, w * .70f, h * .88f, w * .50f, h * .88f);
        ring.cubicTo(w * .22f, h * .88f, w * .12f, h * .66f, w * .12f, h * .50f);
        ring.cubicTo(w * .12f, h * .25f, w * .30f, h * .12f, w * .50f, h * .12f);
        ring.close();
        c.drawPath(ring, paint);
        c.drawLine(w * .28f, h * .72f, w * .72f, h * .28f, paint);
        c.drawLine(w * .72f, h * .28f, w * .82f, h * .22f, paint);
    }
}
