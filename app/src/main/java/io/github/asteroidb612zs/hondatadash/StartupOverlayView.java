package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Disposable presentation curtain. The real dashboard stays attached, receives
 * live data and owns all its state throughout. No live value/alpha is overwritten.
 */
public final class StartupOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ShiftLightRenderer lamps = new ShiftLightRenderer();
    private StartupBrandRenderer brand;
    private ViewGroup dashboard;
    private long started;
    private boolean running;
    private int cachedWidth, cachedHeight, bottomY;
    private final List<Item> items = new ArrayList<Item>();
    private static final int BACKGROUND = 0, LABEL = 1, SCALE = 2, VALUE = 3, BRAND = 4, LAMPS = 5;
    private static final int[] CONTENT_LAYERS = {LABEL, SCALE, BRAND};
    private static final class Item {
        View view; int x, y, group, kind;
        Item(View view, int x, int y, int group, int kind) {
            this.view = view; this.x = x; this.y = y; this.group = group; this.kind = kind;
        }
    }
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long elapsed = SystemClock.elapsedRealtime() - started;
            if (elapsed >= StartupSequence.DURATION_MS) { finish(); return; }
            setAlpha(1f - StartupSequence.reveal(elapsed));
            invalidate(); postOnAnimation(this);
        }
    };

    public StartupOverlayView(Context c) { super(c); init(); }
    public StartupOverlayView(Context c, AttributeSet a) { super(c, a); init(); }
    public StartupOverlayView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }
    private void init() {
        brand = new StartupBrandRenderer(getContext());
        setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { finish(); } });
    }

    public void start(ViewGroup content) {
        if (running) return;
        dashboard = content; started = SystemClock.elapsedRealtime(); running = true;
        items.clear(); setAlpha(1); setVisibility(VISIBLE); postOnAnimation(frame);
    }

    public void finish() {
        running = false; removeCallbacks(frame); setVisibility(GONE); setAlpha(1);
        items.clear(); dashboard = null;
    }

    @Override protected void onDetachedFromWindow() { finish(); super.onDetachedFromWindow(); }

    private boolean reading(int id) {
        return id == R.id.valueInt || id == R.id.maxValue || id == R.id.minValue
                || id == R.id.knockValue || id == R.id.knockRetValue || id == R.id.bottomTrimValue
                || id == R.id.bottomAfmValue || id == R.id.bottomBatValue || id == R.id.bottomFpValue
                || id == R.id.bottomWgValue || id == R.id.bottomTpValue;
    }

    private void collect(View view, int x, int y) {
        if (view.getVisibility() != VISIBLE) return;
        x += view.getLeft(); y += view.getTop();
        int id = view.getId(), group = y >= bottomY ? 2 : id == R.id.header ? 0 : 1;
        if (view.getBackground() != null && id != R.id.statusDot)
            items.add(new Item(view, x, y, group, BACKGROUND));
        if (view instanceof HondaBrandView) items.add(new Item(view, x, y, 0, BRAND));
        else if (view instanceof ShiftLightView) items.add(new Item(view, x, y, 0, LAMPS));
        else if (view instanceof ScaleBarView) items.add(new Item(view, x, y, 1, SCALE));
        else if (view instanceof FittedTextView && id != R.id.statusText && id != R.id.sourceName) {
            if (!reading(id)) items.add(new Item(view, x, y, group, LABEL));
            else if (id != R.id.maxValue && id != R.id.minValue)
                items.add(new Item(view, x, y, group, VALUE));
        }
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) collect(parent.getChildAt(i), x, y);
        }
    }

    private void color(int color, float alpha) {
        paint.setColor(color); paint.setAlpha(Math.round(255 * Math.max(0, Math.min(1, alpha))));
    }

    @Override protected void onDraw(Canvas c) {
        if (!running || dashboard == null) return;
        long elapsed = SystemClock.elapsedRealtime() - started;
        float wake = StartupSequence.ease(elapsed, 0, 450);
        paint.setColor(0xFF000000 | Math.round(3 * wake) << 16 | Math.round(6 * wake) << 8 | Math.round(9 * wake));
        c.drawRect(0, 0, getWidth(), getHeight(), paint);
        if (items.isEmpty() || cachedWidth != getWidth() || cachedHeight != getHeight()) {
            cachedWidth = getWidth(); cachedHeight = getHeight(); items.clear();
            bottomY = dashboard.getTop() + dashboard.findViewById(R.id.bottomRow).getTop();
            collect(dashboard, 0, 0);
        }
        float density = getResources().getDisplayMetrics().density, edge = Math.max(1, Math.round(density));
        for (Item item : items) {
            float w = item.view.getWidth(), h = item.view.getHeight();
            float alpha = StartupSequence.shell(elapsed, item.group);
            if (item.kind == BACKGROUND) {
                color(item.view.getId() == R.id.header ? DashboardPalette.BACKGROUND : DashboardPalette.CARD, alpha);
                c.drawRect(item.x, item.y, item.x + w, item.y + h, paint);
                color(w <= 2 * density ? DashboardPalette.LINE_INNER : DashboardPalette.LINE, alpha);
                c.drawRect(item.x + Math.max(0, w - edge), item.y, item.x + w, item.y + h, paint);
                if (w > 2 * density) c.drawRect(item.x, item.y + h - edge, item.x + w, item.y + h, paint);
            } else if (item.kind == VALUE) {
                color(DashboardPalette.SECONDARY, StartupSequence.placeholders(elapsed) * .7f);
                TextView value = (TextView) item.view;
                paint.setTypeface(value.getTypeface()); paint.setTextSize(Math.min(value.getTextSize(), h * .65f));
                Paint.FontMetrics fm = paint.getFontMetrics();
                c.drawText("--", item.x + (w - paint.measureText("--")) / 2f,
                        item.y + h / 2f - (fm.ascent + fm.descent) / 2f, paint);
            } else if (item.kind == LAMPS && alpha > .01f) {
                lamps.draw(c, item.x, item.y, w, h, density, StartupSequence.lampStage(elapsed), true);
            }
        }
        for (int kind : CONTENT_LAYERS) {
            float alpha = kind == LABEL ? StartupSequence.labels(elapsed)
                    : kind == SCALE ? StartupSequence.scales(elapsed) : StartupSequence.shell(elapsed, 0);
            if (alpha <= .001f) continue;
            int layer = c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(alpha * 255), Canvas.ALL_SAVE_FLAG);
            for (Item item : items) if (item.kind == kind) {
                int save = c.save(); c.translate(item.x, item.y);
                if (kind == LABEL) ((FittedTextView) item.view).onDraw(c);
                else if (kind == SCALE) ((ScaleBarView) item.view).drawScaleOnly(c);
                else ((HondaBrandView) item.view).onDraw(c);
                c.restoreToCount(save);
            }
            c.restoreToCount(layer);
        }
        View status = dashboard.findViewById(R.id.connectionStatus);
        if (dashboard instanceof DashboardGridLayout) {
            int save = c.save(); c.translate(dashboard.getLeft(), dashboard.getTop());
            ((DashboardGridLayout) dashboard).drawFrames(c,
                    StartupSequence.shell(elapsed, 1), StartupSequence.shell(elapsed, 2));
            c.restoreToCount(save);
        }
        View header = dashboard.findViewById(R.id.header);
        color(DashboardPalette.SECONDARY, StartupSequence.checkStatus(elapsed));
        paint.setTypeface(DashboardTypeface.getScale(getContext()));
        paint.setTextSize(Math.min(12 * density, status.getWidth() / 8.5f));
        float right = dashboard.getLeft() + header.getLeft() + status.getRight() - 8 * density;
        c.drawText("SYSTEM CHECK", right - paint.measureText("SYSTEM CHECK"),
                dashboard.getTop() + header.getTop() + header.getHeight() / 2f + 4 * density, paint);
        brand.draw(c, getWidth(), getHeight(), elapsed);
    }
}
