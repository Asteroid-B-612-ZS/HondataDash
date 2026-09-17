package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * Honda-style 5+5 symmetric capsule rev indicator, lit from the outside pair
 * towards the centre. Five stages with hysteresis; only the central red pair flashes at 5 Hz
 * once the shift point is reached. Exiting the flash uses a wider hysteresis so
 * threshold jitter never strobes.
 */
public class ShiftLightView extends View {
    static final float DEFAULT_SHIFT_RPM = 5500f;
    static final float[] STAGE_RATIOS = {.73f, .83f, .91f, .97f, 1f};
    static final long FLASH_MS = 100;
    private float rpm, shiftRpm = DEFAULT_SHIFT_RPM;
    private boolean flashOn = true, flashing, monitoringActive;
    private int currentStage; // 0..5, hysteresis-debounced
    private int lastDrawnStage = -1;
    private boolean lastDrawnRedPhase = true;
    private final ShiftLightRenderer renderer = new ShiftLightRenderer();
    private final Handler handler = new Handler(Looper.getMainLooper());

    static float stageThreshold(int stage, float shiftRpm) {
        return Math.round(shiftRpm * STAGE_RATIOS[stage]);
    }

    /** Stage entry de-bounce: ~1.5% of the shift point, at least 60 rpm. */
    private float stageHysteresis() {
        return Math.max(60f, shiftRpm * 0.015f);
    }

    /** Flash exit needs a clearly lower rpm so 5500↔5490 rpm cannot strobe. */
    private float flashExitHysteresis() {
        return Math.max(120f, shiftRpm * 0.025f);
    }

    private final Runnable flashTick = new Runnable() {
        @Override public void run() {
            if (!monitoringActive || !flashing) return;
            flashOn = !flashOn;
            invalidate();
            handler.postDelayed(this, FLASH_MS);
        }
    };

    public ShiftLightView(Context c) { super(c); }
    public ShiftLightView(Context c, AttributeSet a) { super(c, a); }
    public ShiftLightView(Context c, AttributeSet a, int s) { super(c, a, s); }

    /** Stateful rise/fall update; a per-frame pure recomputation would flicker. */
    private int updateStage() {
        // Flash latch: once the shift point is reached the full bank is meaningful
        // — hold stage five until the flash itself exits via its wider hysteresis.
        if (flashing) {
            currentStage = 5;
            return currentStage;
        }
        while (currentStage < 5 && rpm >= stageThreshold(currentStage, shiftRpm)) currentStage++;
        while (currentStage > 0 && rpm < stageThreshold(currentStage - 1, shiftRpm) - stageHysteresis()) currentStage--;
        return currentStage;
    }

    /** Display configuration only: never changes an ECU limiter or calibration. */
    public void setShiftRpm(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 1000f) return;
        shiftRpm = value;
        setRpm(rpm);
    }

    public void setRpm(float value) {
        rpm = Float.isNaN(value) || Float.isInfinite(value) ? 0f : Math.max(0f, value);
        if (!monitoringActive) {
            clearRuntimeState();
            return;
        }
        boolean nextFlash = rpm >= shiftRpm;
        if (flashing && !nextFlash) nextFlash = rpm >= shiftRpm - flashExitHysteresis();
        if (nextFlash != flashing) {
            flashing = nextFlash;
            flashOn = true;
            handler.removeCallbacks(flashTick);
            if (flashing) handler.postDelayed(flashTick, FLASH_MS);
        }
        int stage = updateStage();
        boolean redPhase = !flashing || flashOn;
        if (stage != lastDrawnStage || redPhase != lastDrawnRedPhase) {
            lastDrawnStage = stage;
            lastDrawnRedPhase = redPhase;
            invalidate();
        }
    }

    /** RPM invalid/stale must extinguish immediately; do not wait for DATA LOST. */
    private void clearRuntimeState() {
        currentStage = 0;
        flashing = false;
        flashOn = true;
        handler.removeCallbacks(flashTick);
        lastDrawnStage = -1;
    }

    public void setMonitoringActive(boolean active) {
        if (active == monitoringActive) return;
        monitoringActive = active;
        if (!active) { rpm = 0; clearRuntimeState(); }
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(flashTick);
        flashing = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        renderer.draw(c, getPaddingLeft(), getPaddingTop(),
                getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom(),
                getResources().getDisplayMetrics().density,
                monitoringActive ? currentStage : 0, !flashing || flashOn);
    }
}
