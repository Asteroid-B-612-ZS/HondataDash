package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 赛车风格刻度进度条 — V3 Dynamics Archetype Engine。
 *
 * 四类动力学原型, 每类使用完全不同的数学结构:
 *   STATIC:    锁定态, 无能量系统 (Ethanol)
 *   THERMAL:   牛顿冷却定律, 非线性散热 (ECT/IAT/L.TRIM)
 *   MECHANICAL: Spring-Damper, 过冲+回弹+余压 (Boost/IGN)
 *   TRANSIENT: Oscillation Envelope, 振荡包络 (A/F/S.TRIM)
 *
 * 情绪渲染原则: "感受到，但不打扰" — 渐变跟随动力学状态
 */
public class ScaleBarView extends View {

    // ========== 动力学原型类型 ==========
    public static final int ARCH_STATIC = 0;
    public static final int ARCH_THERMAL = 1;
    public static final int ARCH_MECHANICAL = 2;
    public static final int ARCH_TRANSIENT = 3;

    private int archetype = ARCH_STATIC;

    // ========== 基础参数 ==========
    private float minVal = 0, maxVal = 100;
    private float curVal = Float.NaN;
    private float anchorVal = 0;
    private float tickReference = Float.NaN;
    private int liveColor;
    private float[] ticks = new float[0];
    private String[] tickLabels = new String[0];
    private boolean showLabels = true;
    private boolean monitoringActive = true;
    private final List<Zone> zones = new ArrayList<>();
    private float expandStart = Float.NaN, expandEnd = Float.NaN;
    private float expandFactor = 1f;

    // ========== ARCH_STATIC: 锁定态, 无状态 ==========
    // 无额外字段

    // ========== ARCH_THERMAL: 牛顿冷却 (双向) ==========
    // heatPos/heatNeg: anchor 上方/下方独立热量
    private float heatPos = 0;              // anchor 上方热量
    private float heatNeg = 0;              // anchor 下方热量
    private float thermalGain = 0.3f;       // 吸热增益
    private float coolingRate = 0.5f;       // 散热速率 (每秒)
    private float thermalMemory = 0.3f;     // 记忆衰减率 (Drift Memory)

    // ========== ARCH_MECHANICAL: Spring-Damper + Peak Hold ==========
    // position/velocity 组成二阶系统, 自然产生 overshoot/rebound/residual
    private float mechPosition = Float.NaN; // 当前显示位置
    private float mechVelocity = 0;         // 运动速度
    private float stiffness = 8.0f;         // 弹簧刚度
    private float mechDamping = 0.7f;       // 阻尼系数 (0=无阻尼, 1=临界阻尼)
    // V2: 峰值保持 (双向, 独立于弹簧残影)
    private float peakPos = 0;              // anchor 上方峰值距离
    private float peakNeg = 0;              // anchor 下方峰值距离
    private float peakRetention = 0.7f;     // 峰值保留率 (每秒, 越高保持越久)

    // ========== ARCH_TRANSIENT: Oscillation Envelope ==========
    // 双侧独立包络: 高侧/低侧各自追踪和衰减
    private float envHigh = 0;              // anchor 上方包络半径
    private float envLow = 0;               // anchor 下方包络半径
    private float oscDecay = 0.3f;          // 包络衰减 (每秒保留率)
    // envelopeHigh/Low 由 oscEnergy 驱动, 不是记录峰值

    // ========== 情绪渲染: 渐变跟随 ==========
    // 情绪强度平滑渐变, 不突变
    public static final int EMOTION_NONE = 0;
    public static final int EMOTION_BUILDING = 1;     // 建压/积累
    public static final int EMOTION_STABLE = 2;       // 稳态
    public static final int EMOTION_RELEASING = 3;    // 泄放/降温
    public static final int EMOTION_DANGER = 4;       // 危险
    public static final int EMOTION_WARNING = 5;      // 警告
    public static final int EMOTION_PROTECTION = 6;   // ECU保护

    private int emotion = EMOTION_NONE;
    private float emotionIntensity = 0;       // 目标强度
    private float emotionCurrent = 0;         // 当前渲染强度 (平滑渐变)
    private float emotionSpeed = 3.0f;        // 渐变速度 (越大越快)
    // 亮度/饱和度由 emotionCurrent 控制, 微弱不突兀

    // ========== Paints ==========
    /** 未点亮槽色: 均匀灰蓝, 比半透明黑更可见。 */
    private static final int TRACK_REST = 0xFF253641;
    private static final float TARGET_BAND_DEADBAND = 0.20f;
    private static final float TARGET_BAND_FULL = 1.20f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint referencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect labelInk = new Rect();
    // Measured only when geometry/configuration changes, never on telemetry frames.
    private float[] labelLeft = new float[0], labelWidth = new float[0], labelOrigin = new float[0];
    private boolean[] labelVisible = new boolean[0];
    private float labelBaseline, nominalLabelSize;
    private int labelLayoutWidth = -1, labelLayoutHeight = -1;
    private float labelLayoutLeft, labelLayoutRight;

    private float density;
    private long lastFrameTime = 0;
    private float lastInputVal = Float.NaN;

    public static class Zone {
        public final float start, end;
        public final int color, endColor;
        public Zone(float s, float e, int c) { this(s, e, c, c); }
        public Zone(float s, float e, int c0, int c1) {
            start = s; end = e; color = c0; endColor = c1;
        }
    }

    public ScaleBarView(Context ctx) { super(ctx); init(ctx); }
    public ScaleBarView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(ctx); }
    public ScaleBarView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init(ctx);
    }

    private void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;

        bgPaint.setColor(0xFF15222D);
        bgPaint.setStyle(Paint.Style.FILL);

        fillPaint.setStyle(Paint.Style.FILL);

        tickPaint.setColor(0xFF7F929F);
        tickPaint.setStrokeWidth(Math.max(1f, density));
        tickPaint.setStyle(Paint.Style.STROKE);

        labelPaint.setColor(0xFFC0CCD5);
        // The fixed scale slot budgets 13sp at density=1. Larger accessibility
        // settings cannot enlarge it into the ticks or sacrifice signed endpoints.
        nominalLabelSize = 13 * Math.min(ctx.getResources().getDisplayMetrics().scaledDensity, density);
        labelPaint.setTextSize(nominalLabelSize);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        labelPaint.setTypeface(DashboardTypeface.getScale(ctx));

        indicatorPaint.setColor(0xFFFFFFFF);
        indicatorPaint.setStrokeWidth(Math.max(3f, Math.round(3 * density)));
        indicatorPaint.setStyle(Paint.Style.STROKE);

        peakFillPaint.setStyle(Paint.Style.FILL);

        peakMarkerPaint.setStyle(Paint.Style.STROKE);
        peakMarkerPaint.setStrokeWidth(Math.max(1f, Math.round(density)));

        glowPaint.setStyle(Paint.Style.FILL);
        // V2.9.2 detail pass: two-stage crisp halo around the small scale track.
        // No BlurMaskFilter/shadow is used: this stays deterministic and cheap on API 17.
        edgeGlowPaint.setColor(0x24638499);
        edgeGlowPaint.setStyle(Paint.Style.FILL);
        edgePaint.setColor(0xFF6D7F8D);
        edgePaint.setStyle(Paint.Style.FILL);
        targetPaint.setColor(DashboardPalette.SCALE_TARGET);
        targetPaint.setStyle(Paint.Style.FILL);
        referencePaint.setColor(0xC0AABAC5);
    }

    // ========== 公开接口 ==========

    public void setRange(float min, float max) {
        if (Float.isNaN(min) || Float.isNaN(max) || Float.isInfinite(min)
                || Float.isInfinite(max) || max <= min) throw new IllegalArgumentException("Invalid scale range");
        minVal = min; maxVal = max; zoneRevision++; labelLayoutWidth = -1; invalidate();
    }
    public void setTicks(float[] v, String[] l) {
        if (v == null || l == null || v.length != l.length) throw new IllegalArgumentException("Tick/label mismatch");
        ticks = v.clone(); tickLabels = l.clone();
        labelLeft = new float[v.length]; labelWidth = new float[v.length]; labelOrigin = new float[v.length];
        labelVisible = new boolean[v.length]; labelLayoutWidth = -1; invalidate();
    }
    public void addZone(float s, float e, int c) { zones.add(new Zone(s, e, c)); zoneRevision++; invalidate(); }
    /** Continuous operating-region ramp. Configuration-only; no per-frame allocation. */
    public void addGradientZone(float s, float e, int startColor, int endColor) {
        zones.add(new Zone(s, e, startColor, endColor)); zoneRevision++; invalidate();
    }

    public void setAnchor(float a) { anchorVal = a; labelLayoutWidth = -1; invalidate(); }

    /** Optional labelled reference independent of the fill/history origin. */
    public void setTickReference(float value) { tickReference = value; labelLayoutWidth = -1; invalidate(); }

    // ========== V2.0 TARGET/ACTUAL mode (A/F) ==========
    private float targetValue = Float.NaN;
    private boolean targetTracking;
    private int targetBandColor;
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** ECU target marker (e.g. target AFR): cyan marker plus a restrained error band. */
    public void setTargetValue(float value, int bandColor) {
        float next = Float.isNaN(value) || Float.isInfinite(value) ? Float.NaN : value;
        if ((Float.isNaN(next) && Float.isNaN(targetValue))
                || (!Float.isNaN(next) && !Float.isNaN(targetValue)
                && Math.abs(next - targetValue) < .0001f && targetBandColor == bandColor)) return;
        targetValue = next;
        targetBandColor = bandColor;
        invalidate();
    }

    public void clearTargetValue() {
        if (Float.isNaN(targetValue)) return;
        targetValue = Float.NaN;
        invalidate();
    }

    /** Target/Actual gauge: no fill ramp, just target marker + white cursor + error band. */
    public void setTargetTracking(boolean enabled) {
        if (targetTracking == enabled) return;
        targetTracking = enabled;
        if (!enabled) targetValue = Float.NaN;
        invalidate();
    }

    /**
     * Reserved semantic metadata. RC4 bars intentionally use their own muted
     * physical-region palette, so a digit-colour change does not force a redraw.
     */
    public void setLiveColor(int color) {
        liveColor = color;
    }
    public void setShowLabels(boolean show) { showLabels = show; labelLayoutWidth = -1; invalidate(); }
    public void setExpand(float start, float end, float factor) {
        expandStart = start; expandEnd = end; expandFactor = factor; zoneRevision++; labelLayoutWidth = -1; invalidate();
    }

    /** A disconnected/semantic card retains its scale, never a live-looking cached bar. */
    public void setMonitoringActive(boolean active) {
        if (monitoringActive == active) return;
        monitoringActive = active;
        if (!active) setValue(Float.NaN);
        invalidate();
    }

    private void clearDynamics() {
        heatPos = heatNeg = peakPos = peakNeg = envHigh = envLow = 0;
        mechPosition = lastInputVal = Float.NaN;
        mechVelocity = 0;
        lastFrameTime = 0;
        emotion = EMOTION_NONE;
        emotionIntensity = emotionCurrent = 0;
    }

    /** 配置 STATIC 原型 (Ethanol) — 锁定态 */
    public void setStatic() {
        archetype = ARCH_STATIC;
    }

    /**
     * 配置 THERMAL 原型 (ECT/IAT/L.TRIM)
     * 牛顿冷却: 吸热+非线性散热, Drift Memory Peak
     * @param gain 吸热增益
     * @param cooling 散热系数 (越大降温越快)
     * @param memory 记忆衰减率 (峰值向anchor漂移的速度)
     */
    public void setThermal(float gain, float cooling, float memory) {
        archetype = ARCH_THERMAL;
        thermalGain = gain;
        coolingRate = cooling;
        thermalMemory = memory;
    }

    /**
     * 配置 MECHANICAL 原型 (Boost/IGN)
     * Spring-Damper + Peak Hold: 过冲/回弹/残影 + 近期峰值保持
     * @param stiffness 弹簧刚度 (越大追踪越快)
     * @param damping 阻尼 (0~1: 0=无阻尼振荡, 1=临界阻尼无过冲)
     * @param peakRetention 峰值保留率/秒 (0.5快衰减~0.9慢衰减, 越高保持越久)
     */
    public void setMechanical(float stiffness, float damping, float peakRetention) {
        archetype = ARCH_MECHANICAL;
        this.stiffness = stiffness;
        this.mechDamping = damping;
        this.peakRetention = peakRetention;
    }

    /**
     * 配置 TRANSIENT 原型 (A/F/S.TRIM)
     * Oscillation Envelope: 双侧独立包络, 各自追踪和衰减
     * @param _unused_gain 保留参数 (不再使用)
     * @param decay 包络衰减 (每秒保留率)
     */
    public void setTransient(float _unused_gain, float decay) {
        archetype = ARCH_TRANSIENT;
        oscDecay = decay;
    }

    /**
     * 设置情绪状态 (渐变跟随)
     */
    public void setEmotion(int emotion, float intensity) {
        this.emotion = emotion;
        this.emotionIntensity = Math.max(0, Math.min(1, intensity));
    }

    /** 核心入口 */
    public void setValue(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            boolean hadValue = !Float.isNaN(curVal);
            curVal = Float.NaN;
            clearDynamics();
            if (hadValue) invalidate();
            return;
        }
        // All eight production cards use direct/static presentation. Repeated
        // samples need neither the retired dynamics clock nor another redraw.
        // Target marker, monitoring, alpha and geometry have their own setters.
        if (archetype == ARCH_STATIC && emotion == EMOTION_NONE
                && emotionIntensity == 0f && emotionCurrent == 0f) {
            if (curVal == v) return;
            curVal = v;
            invalidate();
            return;
        }
        curVal = v;
        long now = SystemClock.elapsedRealtime();
        float dt = lastFrameTime > 0 ? Math.max(0f, Math.min((now - lastFrameTime) / 1000f, 0.1f)) : 0.02f;
        lastFrameTime = now;

        float delta = Float.isNaN(lastInputVal) ? 0 : (v - lastInputVal);
        lastInputVal = v;

        // 按原型更新动力学
        switch (archetype) {
            case ARCH_THERMAL:
                updateThermal(v, delta, dt);
                break;
            case ARCH_MECHANICAL:
                updateMechanical(v, dt);
                break;
            case ARCH_TRANSIENT:
                updateTransient(v, delta, dt);
                break;
            // ARCH_STATIC: 无状态更新
        }

        // 情绪渐变
        updateEmotion(dt);

        invalidate();
    }

    // ========== ARCH_THERMAL: 牛顿冷却 ==========

    private void updateThermal(float v, float delta, float dt) {
        // 双向独立: 直接追踪偏离 anchor 的最大距离 (不是累积 delta)
        float devPos = Math.max(0, v - anchorVal);
        float devNeg = Math.max(0, anchorVal - v);

        // 只在偏差超过当前记忆时扩展
        if (devPos > heatPos) heatPos = devPos;
        if (devNeg > heatNeg) heatNeg = devNeg;

        // 牛顿散热: 散热量与当前热量成正比 → 高温快散, 低温慢散 (非线性!)
        heatPos -= heatPos * coolingRate * dt;
        heatNeg -= heatNeg * coolingRate * dt;

        if (heatPos < 0) heatPos = 0;
        if (heatNeg < 0) heatNeg = 0;
    }

    // ========== ARCH_MECHANICAL: Spring-Damper ==========

    private void updateMechanical(float target, float dt) {
        if (Float.isNaN(mechPosition)) {
            mechPosition = target;
            mechVelocity = 0;
            peakPos = Math.max(0, target - anchorVal);
            peakNeg = Math.max(0, anchorVal - target);
            return;
        }

        // 二阶 Spring-Damper 系统 (Euler 积分)
        float displacement = target - mechPosition;
        float springForce = stiffness * displacement;
        float criticalDamp = 2.0f * (float) Math.sqrt(stiffness);
        float dampForce = mechDamping * criticalDamp * mechVelocity;
        mechVelocity += (springForce - dampForce) * dt;
        mechPosition += mechVelocity * dt;

        // 防发散
        float range = maxVal - minVal;
        if (Math.abs(mechPosition - target) > range * 0.5f) {
            mechPosition = target;
            mechVelocity = 0;
        }

        // V2: 峰值保持 — 双向追踪, 指数衰减
        float devPos = Math.max(0, target - anchorVal);
        float devNeg = Math.max(0, anchorVal - target);
        if (devPos > peakPos) peakPos = devPos;
        if (devNeg > peakNeg) peakNeg = devNeg;
        float retention = (float) Math.pow(peakRetention, dt);
        peakPos *= retention;
        peakNeg *= retention;
    }

    // ========== ARCH_TRANSIENT: Oscillation Envelope ==========

    private void updateTransient(float v, float delta, float dt) {
        // 计算当前偏差 (相对 anchor)
        float devHigh = Math.max(0, v - anchorVal);   // anchor 上方距离
        float devLow = Math.max(0, anchorVal - v);     // anchor 下方距离

        // 各侧独立: 只有偏差超过当前包络才扩展
        if (devHigh > envHigh) envHigh = devHigh;
        if (devLow > envLow) envLow = devLow;

        // 各侧独立衰减
        float decay = (float) Math.pow(oscDecay, dt);
        envHigh *= decay;
        envLow *= decay;
    }

    // ========== 情绪渐变 ==========

    private void updateEmotion(float dt) {
        // 平滑渐变: current 向 target 靠近
        float diff = emotionIntensity - emotionCurrent;
        emotionCurrent += diff * Math.min(1f, emotionSpeed * dt);
        if (Math.abs(emotionCurrent) < 0.001f) emotionCurrent = 0;
    }

    // ========== 坐标映射 ==========

    private float valToX(float v, float left, float w) {
        if (Float.isNaN(expandStart) || expandFactor <= 1f) {
            float ratio = (v - minVal) / (maxVal - minVal);
            return left + ratio * w;
        }
        float segBefore = expandStart - minVal;
        float segExpand = expandEnd - expandStart;
        float segAfter = maxVal - expandEnd;
        float totalWeight = segBefore + segExpand * expandFactor + segAfter;
        float ratio;
        if (v <= expandStart) {
            ratio = (v - minVal) / totalWeight;
        } else if (v <= expandEnd) {
            ratio = (segBefore + (v - expandStart) * expandFactor) / totalWeight;
        } else {
            ratio = (segBefore + segExpand * expandFactor + (v - expandEnd)) / totalWeight;
        }
        return left + ratio * w;
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int oa = Math.round(aa + (ba - aa) * t);
        int or = Math.round(ar + (br - ar) * t);
        int og = Math.round(ag + (bg - ag) * t);
        int ob = Math.round(ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    private int zoneColor(float v) {
        for (Zone z : zones) {
            if (v >= z.start && v <= z.end) {
                if (z.color == z.endColor || z.end <= z.start) return z.color;
                return lerpColor(z.color, z.endColor, (v - z.start) / (z.end - z.start));
            }
        }
        return DashboardPalette.SCALE_CYAN;
    }

    // ========== 绘制 ==========

    private boolean hasLabel(int i) {
        return tickLabels[i] != null && !tickLabels[i].isEmpty();
    }

    private int labelPriority(int i) {
        if (!Float.isNaN(tickReference) && Math.abs(ticks[i] - tickReference) < 0.0001f) return 0;
        if (Math.abs(ticks[i] - anchorVal) < 0.0001f) return 0;
        if (Math.abs(ticks[i] - minVal) < 0.0001f || Math.abs(ticks[i] - maxVal) < 0.0001f) return 1;
        return 2;
    }

    /** Common baseline from actual glyph ink, not the font's unused ascender padding. */
    private void measureLabels(float left, float right, float top, float bottom) {
        float inkTop = 0, inkBottom = 0;
        for (int i = 0; i < ticks.length; i++) {
            labelVisible[i] = false;
            if (!hasLabel(i)) continue;
            String label = tickLabels[i];
            labelPaint.getTextBounds(label, 0, label.length(), labelInk);
            inkTop = Math.min(inkTop, labelInk.top);
            inkBottom = Math.max(inkBottom, labelInk.bottom);
            float origin = Math.min(0, labelInk.left);
            labelOrigin[i] = -origin;
            labelWidth[i] = Math.max(labelPaint.measureText(label), labelInk.right) - origin;
            float x = valToX(ticks[i], left, right - left);
            // End labels face inward; interior labels stay centered on their true tick.
            labelLeft[i] = Math.abs(ticks[i] - minVal) < .0001f ? left + density
                    : Math.abs(ticks[i] - maxVal) < .0001f ? right - density - labelWidth[i]
                    : x - labelWidth[i] / 2f;
        }
        float inkHeight = inkBottom - inkTop;
        if (inkHeight > bottom - top && labelPaint.getTextSize() > 1f) {
            labelPaint.setTextSize(labelPaint.getTextSize() * (bottom - top) / inkHeight * .99f);
            measureLabels(left, right, top, bottom);
            return;
        }
        labelBaseline = Math.max(top - inkTop, Math.min(bottom - inkBottom,
                Math.round(top + (bottom - top - inkHeight) / 2f - inkTop)));
    }

    private void layoutLabels(float left, float right, float top, float bottom) {
        if (labelLayoutWidth == getWidth() && labelLayoutHeight == getHeight()
                && labelLayoutLeft == left && labelLayoutRight == right) return;
        labelLayoutWidth = getWidth(); labelLayoutHeight = getHeight();
        labelLayoutLeft = left; labelLayoutRight = right;
        labelPaint.setTextSize(nominalLabelSize);
        measureLabels(left, right, top, bottom);
        // At fallback-font edges permit at most 15% uniform reduction; never squeeze
        // the glyphs horizontally. Below that, omit secondary labels by priority.
        float heightFittedSize = labelPaint.getTextSize();
        // Reference-lock label rhythm: tighter 2dp inter-label gap (GPT baseline).
        float gap = Math.max(2, Math.round(2 * density));
        for (int attempt = 0; attempt < 6; attempt++) {
            boolean allFit = true;
            float previousRight = -Float.MAX_VALUE;
            for (int i = 0; i < ticks.length; i++) {
                if (!hasLabel(i)) continue;
                if (labelLeft[i] < previousRight + gap || labelLeft[i] < left
                        || labelLeft[i] + labelWidth[i] > right) allFit = false;
                previousRight = labelLeft[i] + labelWidth[i];
            }
            if (allFit || attempt == 5) break;
            labelPaint.setTextSize(heightFittedSize * (1f - .03f * (attempt + 1)));
            measureLabels(left, right, top, bottom);
        }
        for (int priority = 0; priority <= 2; priority++) {
            for (int i = 0; i < ticks.length; i++) {
                if (!showLabels || !hasLabel(i) || labelPriority(i) != priority
                        || labelLeft[i] < left || labelLeft[i] + labelWidth[i] > right) continue;
                boolean fits = true;
                for (int j = 0; j < ticks.length; j++) {
                    if (labelVisible[j] && labelLeft[i] < labelLeft[j] + labelWidth[j] + gap
                            && labelLeft[j] < labelLeft[i] + labelWidth[i] + gap) { fits = false; break; }
                }
                labelVisible[i] = fits;
            }
        }
        // On symmetric scales a constrained fallback font must not leave a lone
        // +15/-15 (or +20/-20). Keep the zero/endpoints and simplify paired labels.
        if (Math.abs(anchorVal) < .0001f && Math.abs(minVal + maxVal) < .0001f) {
            for (int i = 0; i < ticks.length; i++) {
                if (labelPriority(i) != 2 || !hasLabel(i)) continue;
                for (int j = i + 1; j < ticks.length; j++) {
                    if (hasLabel(j) && Math.abs(ticks[i] + ticks[j]) < .0001f) {
                        boolean both = labelVisible[i] && labelVisible[j];
                        labelVisible[i] = labelVisible[j] = both;
                    }
                }
            }
        }
    }

    /** Integer-bound rectangles avoid blurred half-covered 1px vertical strokes. */
    private void drawHairline(Canvas canvas, float x, float top, float bottom, Paint paint) {
        paint.setStyle(Paint.Style.FILL);
        float stroke = Math.max(1, Math.round(density));
        float px = Math.max(getPaddingLeft(), Math.min(getWidth() - getPaddingRight() - stroke,
                Math.round(x - stroke / 2f)));
        canvas.drawRect(px, Math.round(top), px + stroke, Math.round(bottom), paint);
    }

    private void drawTrackEdge(Canvas canvas, float left, float right, float top, float bottom) {
        /*
         * V2.9.4 optical shell: directional light instead of four equally bright
         * sides.  Real-car LCD tests showed that symmetric multi-stroke borders
         * read as a neon rectangle.  A brighter top/left and quieter bottom/right
         * produces the reference-photo glass/metal depth while remaining fully
         * in-bounds and deterministic on API 17.
         */
        float halo = Math.max(2, Math.round(2 * density));

        edgeGlowPaint.setColor(0x2A8B9CA9); // top catch
        canvas.drawRect(left, top, right, Math.min(bottom, top + halo), edgeGlowPaint);
        edgeGlowPaint.setColor(0x1D6D7F8D); // left catch
        canvas.drawRect(left, top, Math.min(right, left + halo), bottom, edgeGlowPaint);
        edgeGlowPaint.setColor(0x12425564); // right falloff
        canvas.drawRect(Math.max(left, right - halo), top, right, bottom, edgeGlowPaint);
        edgeGlowPaint.setColor(0x0B283A47); // bottom falloff
        canvas.drawRect(left, Math.max(top, bottom - halo), right, bottom, edgeGlowPaint);

        float stroke = Math.max(1, Math.round(density));
        edgePaint.setColor(0xFF8B9CA9); // top metallic highlight
        canvas.drawRect(left, top, right, top + stroke, edgePaint);
        edgePaint.setColor(0xFF6D7F8D); // left
        canvas.drawRect(left, top, left + stroke, bottom, edgePaint);
        edgePaint.setColor(0xFF425564); // right
        canvas.drawRect(right - stroke, top, right, bottom, edgePaint);
        edgePaint.setColor(0xFF283A47); // bottom
        canvas.drawRect(left, bottom - stroke, right, bottom, edgePaint);

        // One-pixel inner reflections make the colour bar feel recessed in a shell.
        float inner = Math.max(1, Math.round(density));
        edgeGlowPaint.setColor(0x20C0CCD5);
        canvas.drawRect(left + stroke, top + stroke,
                Math.max(left + stroke, right - stroke),
                Math.min(bottom - stroke, top + stroke + inner), edgeGlowPaint);
        edgeGlowPaint.setColor(0x38000000);
        canvas.drawRect(left + stroke, Math.max(top + stroke, bottom - stroke - inner),
                Math.max(left + stroke, right - stroke), bottom - stroke, edgeGlowPaint);
    }

    /** Chevron is range overflow, not a new alarm threshold or an auto-rescaled axis. */
    private void drawOverflow(Canvas canvas, float left, float right, float top, float bottom) {
        if (curVal >= minVal && curVal <= maxVal || right - left < 16 * density) return;
        float unit = Math.max(1, Math.round(density));
        float cy = Math.round((top + bottom) / 2f);
        for (int row = -3; row <= 3; row++) {
            float x = curVal > maxVal ? right - (4 + Math.abs(row)) * unit
                    : left + (2 + Math.abs(row)) * unit;
            canvas.drawRect(x, cy + row * unit, x + 2 * unit, cy + (row + 1) * unit, indicatorPaint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawContents(canvas, true);
    }

    /** Read-only startup shell: no fake readings, no changes to thermal history. */
    void drawScaleOnly(Canvas canvas) { drawContents(canvas, false); }

    private void drawContents(Canvas canvas, boolean live) {
        float pL = getPaddingLeft(), pR = getPaddingRight();
        float pT = getPaddingTop(), pB = getPaddingBottom();
        float barW = getWidth() - pL - pR;
        float barLeft = pL;

        if (barW <= 0 || getHeight() - pT - pB < 34 * density) return;
        float barH = Math.round(18 * density);
        float barBottom = Math.round(getHeight() - pB - 2 * density);
        float barTop = barBottom - barH;
        float barRight = barLeft + barW;

        float tickBottom = Math.round(barTop - 2 * density);
        float tickTop = tickBottom - Math.round(6 * density);
        float labelTop = pT + density;
        float labelBottom = Math.max(labelTop + 1, tickTop - density);
        layoutLabels(barLeft, barRight, labelTop, labelBottom);

        // 1. 刻度数字和刻度线
        for (int i = 0; i < ticks.length; i++) {
            if (ticks[i] < minVal || ticks[i] > maxVal) continue;
            float x = valToX(ticks[i], barLeft, barW);
            if (labelVisible[i]) {
                labelPaint.setColor(labelPriority(i) == 0 ? 0xFFC0CCD5 : 0xFFAABAC5);
                canvas.drawText(tickLabels[i], labelLeft[i] + labelOrigin[i], labelBaseline, labelPaint);
            }
            boolean referenceTick = !Float.isNaN(tickReference) && Math.abs(ticks[i] - tickReference) < .0001f;
            tickPaint.setColor(referenceTick ? 0xFFAABAC5 : labelVisible[i] ? 0xFF7F929F : 0xFF4D6271);
            float tickStart = referenceTick ? tickTop - Math.round(density)
                    : labelVisible[i] ? tickTop : tickBottom - Math.round(3 * density);
            drawHairline(canvas, x, tickStart, tickBottom, tickPaint);
        }

        // 2. 数据条背景 — 均匀暗槽; 语义色只出现在填充渐变里
        canvas.drawRect(barLeft, barTop, barRight, barBottom, bgPaint);

        // 3. 填充 / TARGET-ACTUAL 表达
        if (live && monitoringActive && !Float.isNaN(curVal)) {
            float clamped = Math.max(minVal, Math.min(maxVal, curVal));
            if (targetTracking) {
                // TARGET/ACTUAL gauge: faint error band + cyan target line; the
                // white cursor (drawn below) carries the actual value.
                drawTargetGauge(canvas, clamped, barLeft, barW, barTop, barBottom);
            } else {
                float ax = valToX(Math.max(minVal, Math.min(maxVal, anchorVal)), barLeft, barW);
                switch (archetype) {
                    case ARCH_STATIC:
                        drawStaticBar(canvas, ax, clamped, barLeft, barRight, barTop, barBottom, barW);
                        break;
                    case ARCH_THERMAL:
                        drawThermalBar(canvas, ax, clamped, barLeft, barRight, barTop, barBottom, barW);
                        break;
                    case ARCH_MECHANICAL:
                        drawMechanicalBar(canvas, ax, clamped, barLeft, barRight, barTop, barBottom, barW);
                        break;
                    case ARCH_TRANSIENT:
                        drawTransientBar(canvas, ax, clamped, barLeft, barRight, barTop, barBottom, barW);
                        break;
                }
            }
        }
        if (anchorVal > minVal && anchorVal < maxVal) {
            drawHairline(canvas, valToX(anchorVal, barLeft, barW), barTop, barBottom, referencePaint);
        }
        drawTrackEdge(canvas, barLeft, barRight, barTop, barBottom);
        if (live && monitoringActive && !Float.isNaN(curVal)) {
            float current = Math.max(minVal, Math.min(maxVal, curVal));
            drawIndicator(canvas, valToX(current, barLeft, barW), barTop, barBottom);
            drawOverflow(canvas, barLeft, barRight, barTop, barBottom);
        }
    }

    // ========== ARCH_STATIC: 纯显示, 无残影 ==========

    private void drawStaticBar(Canvas canvas, float ax, float clamped,
            float barLeft, float barRight, float barTop, float barBottom, float barW) {
        float vx = valToX(clamped, barLeft, barW);
        float fillLeft = Math.min(ax, vx);
        float fillRight = Math.max(ax, vx);

        drawFillBar(canvas, fillLeft, fillRight, barLeft, barW, barTop, barBottom);

        // No peak/history. Paint BOTH unused sides with the same rest colour so
        // zero-centred gauges stay optically symmetric for positive and negative values.
        peakFillPaint.setColor(TRACK_REST);
        if (fillLeft > barLeft) canvas.drawRect(barLeft, barTop, fillLeft, barBottom, peakFillPaint);
        if (fillRight < barRight) canvas.drawRect(fillRight, barTop, barRight, barBottom, peakFillPaint);
    }

    // ========== ARCH_THERMAL: Drift Memory Peak (牛顿冷却) ==========

    private void drawThermalBar(Canvas canvas, float ax, float clamped,
            float barLeft, float barRight, float barTop, float barBottom, float barW) {
        float vx = valToX(clamped, barLeft, barW);
        float fillLeft = Math.min(ax, vx);
        float fillRight = Math.max(ax, vx);

        drawFillBar(canvas, fillLeft, fillRight, barLeft, barW, barTop, barBottom);

        // 正方向记忆峰值 (anchor上方)
        if (heatPos > 0.01f) {
            float memoryVal = anchorVal + heatPos;
            float memClamped = Math.max(minVal, Math.min(maxVal, memoryVal));
            float mx = valToX(memClamped, barLeft, barW);

            if (mx > fillRight + 0.5f && mx <= barRight) {
                float memAlpha = Math.min(1f, heatPos * 0.4f);
                int memColor = zoneColor(memClamped);
                peakFillPaint.setColor(Color.argb((int)(memAlpha * 112),
                    Color.red(memColor), Color.green(memColor), Color.blue(memColor)));
                canvas.drawRect(fillRight, barTop, mx, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(memAlpha * 120),
                    Color.red(memColor) / 2, Color.green(memColor) / 2, Color.blue(memColor) / 2));
                drawHairline(canvas, mx, barTop, barBottom, peakMarkerPaint);
                fillRight = mx;
            }
        }

        // 负方向记忆峰值 (anchor下方)
        if (heatNeg > 0.01f) {
            float memoryVal = anchorVal - heatNeg;
            float memClamped = Math.max(minVal, Math.min(maxVal, memoryVal));
            float mx = valToX(memClamped, barLeft, barW);

            if (mx < fillLeft - 0.5f && mx >= barLeft) {
                float memAlpha = Math.min(1f, heatNeg * 0.4f);
                int memColor = zoneColor(memClamped);
                peakFillPaint.setColor(Color.argb((int)(memAlpha * 112),
                    Color.red(memColor), Color.green(memColor), Color.blue(memColor)));
                canvas.drawRect(mx, barTop, fillLeft, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(memAlpha * 120),
                    Color.red(memColor) / 2, Color.green(memColor) / 2, Color.blue(memColor) / 2));
                drawHairline(canvas, mx, barTop, barBottom, peakMarkerPaint);
                fillLeft = mx;
            }
        }

        // 灰色未覆盖 (两侧)
        if (fillLeft > barLeft) {
            peakFillPaint.setColor(TRACK_REST);
            canvas.drawRect(barLeft, barTop, fillLeft, barBottom, peakFillPaint);
        }
        if (fillRight < barRight) {
            peakFillPaint.setColor(TRACK_REST);
            canvas.drawRect(fillRight, barTop, barRight, barBottom, peakFillPaint);
        }

        // 情绪: 微弱温暖感 (热量高时填充区微亮)
        drawEmotionGlow(canvas, fillLeft, fillRight, barLeft, barRight, barTop, barBottom);
    }

    // ========== ARCH_MECHANICAL: Spring-Damper 物理残影 ==========

    private void drawMechanicalBar(Canvas canvas, float ax, float clamped,
            float barLeft, float barRight, float barTop, float barBottom, float barW) {

        // The solid fill and bright cursor represent the displayed numeric value.
        // Spring position/velocity remain visible only as subdued residuals.
        float vx = valToX(clamped, barLeft, barW);
        float fillLeft = Math.min(ax, vx);
        float fillRight = Math.max(ax, vx);

        drawFillBar(canvas, fillLeft, fillRight, barLeft, barW, barTop, barBottom);

        float resAlpha = Math.min(1f, 0.6f + Math.abs(mechVelocity) * 0.4f);

        // 正方向残影: position > curVal (右侧)
        float residual = Float.isNaN(mechPosition) ? 0 : mechPosition - clamped;
        if (residual > 0.005f) {
            float posXC = valToX(Math.max(minVal, Math.min(maxVal, mechPosition)), barLeft, barW);
            if (posXC > fillRight + 0.5f && posXC <= barRight) {
                int resColor = zoneColor(Math.max(minVal, Math.min(maxVal, mechPosition)));
                peakFillPaint.setColor(Color.argb((int)(resAlpha * 108),
                    Color.red(resColor), Color.green(resColor), Color.blue(resColor)));
                canvas.drawRect(fillRight, barTop, posXC, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(resAlpha * 120),
                    Color.red(resColor) / 2, Color.green(resColor) / 2, Color.blue(resColor) / 2));
                drawHairline(canvas, posXC, barTop, barBottom, peakMarkerPaint);
                fillRight = posXC;
            }
        }

        // 负方向残影: position < curVal (左侧) — IGN 负值回弹时
        if (residual < -0.005f) {
            float posXC = valToX(Math.max(minVal, Math.min(maxVal, mechPosition)), barLeft, barW);
            if (posXC < fillLeft - 0.5f && posXC >= barLeft) {
                int resColor = zoneColor(Math.max(minVal, Math.min(maxVal, mechPosition)));
                peakFillPaint.setColor(Color.argb((int)(resAlpha * 108),
                    Color.red(resColor), Color.green(resColor), Color.blue(resColor)));
                canvas.drawRect(posXC, barTop, fillLeft, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(resAlpha * 120),
                    Color.red(resColor) / 2, Color.green(resColor) / 2, Color.blue(resColor) / 2));
                drawHairline(canvas, posXC, barTop, barBottom, peakMarkerPaint);
                fillLeft = posXC;
            }
        }

        // V2: 峰值保持 — 正方向 (anchor 上方, Boost 峰值/IGN 正峰值)
        if (peakPos > 0.01f) {
            float peakV = anchorVal + peakPos;
            float peakClamped = Math.max(minVal, Math.min(maxVal, peakV));
            float px = valToX(peakClamped, barLeft, barW);

            if (px > fillRight + 0.5f && px <= barRight) {
                float peakAlpha = Math.min(0.85f, 0.3f + peakPos / (maxVal - minVal) * 2.0f);
                int peakColor = zoneColor(peakClamped);
                peakFillPaint.setColor(Color.argb((int)(peakAlpha * 110),
                    Color.red(peakColor), Color.green(peakColor), Color.blue(peakColor)));
                canvas.drawRect(fillRight, barTop, px, barBottom, peakFillPaint);

                peakMarkerPaint.setStrokeWidth(Math.max(1f, Math.round(density)));
                peakMarkerPaint.setColor(Color.argb((int)(peakAlpha * 220),
                    Color.red(peakColor), Color.green(peakColor), Color.blue(peakColor)));
                drawHairline(canvas, px, barTop, barBottom, peakMarkerPaint);
                peakMarkerPaint.setStrokeWidth(Math.max(1f, Math.round(density)));
                fillRight = px;
            }
        }

        // V2: 峰值保持 — 负方向 (anchor 下方, IGN 负峰值)
        if (peakNeg > 0.01f) {
            float peakV = anchorVal - peakNeg;
            float peakClamped = Math.max(minVal, Math.min(maxVal, peakV));
            float px = valToX(peakClamped, barLeft, barW);

            if (px < fillLeft - 0.5f && px >= barLeft) {
                float peakAlpha = Math.min(0.85f, 0.3f + peakNeg / (maxVal - minVal) * 2.0f);
                int peakColor = zoneColor(peakClamped);
                peakFillPaint.setColor(Color.argb((int)(peakAlpha * 110),
                    Color.red(peakColor), Color.green(peakColor), Color.blue(peakColor)));
                canvas.drawRect(px, barTop, fillLeft, barBottom, peakFillPaint);

                peakMarkerPaint.setStrokeWidth(Math.max(1f, Math.round(density)));
                peakMarkerPaint.setColor(Color.argb((int)(peakAlpha * 220),
                    Color.red(peakColor), Color.green(peakColor), Color.blue(peakColor)));
                drawHairline(canvas, px, barTop, barBottom, peakMarkerPaint);
                peakMarkerPaint.setStrokeWidth(Math.max(1f, Math.round(density)));
                fillLeft = px;
            }
        }

        // 灰色未覆盖区域 (两侧)
        if (fillLeft > barLeft) {
            peakFillPaint.setColor(TRACK_REST);
            canvas.drawRect(barLeft, barTop, fillLeft, barBottom, peakFillPaint);
        }
        if (fillRight < barRight) {
            peakFillPaint.setColor(TRACK_REST);
            canvas.drawRect(fillRight, barTop, barRight, barBottom, peakFillPaint);
        }

        // 情绪: 速度感 (velocity 高时边缘微亮)
        drawEmotionGlow(canvas, fillLeft, fillRight, barLeft, barRight, barTop, barBottom);
    }

    // ========== ARCH_TRANSIENT: Oscillation Envelope (呼吸) ==========

    private void drawTransientBar(Canvas canvas, float ax, float clamped,
            float barLeft, float barRight, float barTop, float barBottom, float barW) {
        float vx = valToX(clamped, barLeft, barW);
        float fillLeft = Math.min(ax, vx);
        float fillRight = Math.max(ax, vx);

        drawFillBar(canvas, fillLeft, fillRight, barLeft, barW, barTop, barBottom);

        // 双侧独立包络: envHigh 和 envLow 各自追踪和衰减
        // 右侧包络 (anchor 上方, 高侧)
        if (envHigh > 0.01f) {
            float envHighVal = anchorVal + envHigh;
            float envHighClamped = Math.max(minVal, Math.min(maxVal, envHighVal));
            float envHx = valToX(envHighClamped, barLeft, barW);

            if (envHx > fillRight + 0.5f) {
                float envAlpha = Math.min(0.7f, envHigh * 0.5f);
                int envColor = zoneColor(envHighClamped);
                peakFillPaint.setColor(Color.argb((int)(envAlpha * 110),
                    Color.red(envColor), Color.green(envColor), Color.blue(envColor)));
                canvas.drawRect(fillRight, barTop, envHx, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(envAlpha * 140),
                    Color.red(envColor) / 2, Color.green(envColor) / 2, Color.blue(envColor) / 2));
                drawHairline(canvas, envHx, barTop, barBottom, peakMarkerPaint);
                fillRight = envHx;
            }
        }

        // 左侧包络 (anchor 下方, 低侧)
        if (envLow > 0.01f) {
            float envLowVal = anchorVal - envLow;
            float envLowClamped = Math.max(minVal, Math.min(maxVal, envLowVal));
            float envLx = valToX(envLowClamped, barLeft, barW);

            if (envLx < fillLeft - 0.5f) {
                float envAlpha = Math.min(0.7f, envLow * 0.5f);
                int envColor = zoneColor(envLowClamped);
                peakFillPaint.setColor(Color.argb((int)(envAlpha * 110),
                    Color.red(envColor), Color.green(envColor), Color.blue(envColor)));
                canvas.drawRect(envLx, barTop, fillLeft, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(envAlpha * 140),
                    Color.red(envColor) / 2, Color.green(envColor) / 2, Color.blue(envColor) / 2));
                drawHairline(canvas, envLx, barTop, barBottom, peakMarkerPaint);
                fillLeft = envLx;
            }
        }

        // 灰色未覆盖
        if (fillLeft > barLeft) {
            peakFillPaint.setColor(TRACK_REST);
            canvas.drawRect(barLeft, barTop, fillLeft, barBottom, peakFillPaint);
        }
        if (fillRight < barRight) {
            peakFillPaint.setColor(TRACK_REST);
            canvas.drawRect(fillRight, barTop, barRight, barBottom, peakFillPaint);
        }

        // 情绪: 振荡活跃度 (oscEnergy 高时微亮)
        drawEmotionGlow(canvas, fillLeft, fillRight, barLeft, barRight, barTop, barBottom);
    }

    // ========== 通用绘制工具 ==========

    /**
     * 直接填充: 填充段从整轨缓存渐变中取色（zone 语义色按轨道位置平滑过渡）。
     * shader 只在 range/zone/expand/尺寸变化时重建, setValue 数据路径零分配。
     */
    private void drawFillBar(Canvas canvas, float left, float right, float barLeft, float barW,
            float top, float bottom) {
        if (right - left < 0.5f) return;
        fillPaint.setShader(trackGradient(barLeft, barW));
        canvas.drawRect(left, top, right, bottom, fillPaint);
        fillPaint.setShader(null);
    }

    /**
     * TARGET/ACTUAL gauge. The target marker is always visible, while the
     * deviation band has a small AFR dead-band so closed-loop normality looks
     * calm instead of constantly glowing. Band alpha grows only with meaningful
     * target error; the white cursor remains the actual value.
     */
    private void drawTargetGauge(Canvas canvas, float clamped, float barLeft, float barW,
            float barTop, float barBottom) {
        float cx = valToX(clamped, barLeft, barW);
        if (Float.isNaN(targetValue)) return;
        float targetClamped = Math.max(minVal, Math.min(maxVal, targetValue));
        float tx = valToX(targetClamped, barLeft, barW);
        float error = Math.abs(clamped - targetClamped);
        float l = Math.min(tx, cx), r = Math.max(tx, cx);
        if (error > TARGET_BAND_DEADBAND && r - l > 1f) {
            float t = Math.min(1f, (error - TARGET_BAND_DEADBAND)
                    / (TARGET_BAND_FULL - TARGET_BAND_DEADBAND));
            int alpha = Math.round(18 + 38 * t);
            edgeGlowPaint.setColor(targetBandColor);
            edgeGlowPaint.setAlpha(alpha);
            canvas.drawRect(l, barTop, r, barBottom, edgeGlowPaint);
            edgeGlowPaint.setAlpha(255);
        }

        float stroke = Math.max(2, Math.round(2 * density));
        float px = Math.max(getPaddingLeft(),
                Math.min(getWidth() - getPaddingRight() - stroke, Math.round(tx - stroke / 2f)));
        canvas.drawRect(px, barTop, px + stroke, barBottom, targetPaint);

        // Small top cap differentiates TARGET from the full-height white ACTUAL cursor.
        float cap = Math.max(4, Math.round(5 * density));
        float capH = Math.max(1, Math.round(density));
        float capLeft = Math.max(getPaddingLeft(), px + stroke / 2f - cap / 2f);
        float capRight = Math.min(getWidth() - getPaddingRight(), capLeft + cap);
        canvas.drawRect(capLeft, barTop, capRight, barTop + capH, targetPaint);
    }

    // ========== 整轨渐变缓存: 数据更新路径不重建 shader ==========
    private LinearGradient trackShader;
    private int trackShaderRevision = -1;
    private float trackShaderLeft = Float.NaN, trackShaderWidth = Float.NaN;
    private int zoneRevision;

    /**
     * Build one cached full-track shader. Adjacent zone colours use a sub-pixel
     * transition instead of blending across the entire next operating band. This
     * keeps ±5/±15, temperature and boost regions optically truthful without a
     * harsh staircase, while still allocating only when configuration changes.
     */
    private LinearGradient trackGradient(float barLeft, float barW) {
        if (trackShader != null && trackShaderRevision == zoneRevision
                && trackShaderLeft == barLeft && trackShaderWidth == barW) return trackShader;

        int capacity = zones.size() * 2 + 2;
        float[] xs = new float[capacity];
        int[] cs = new int[capacity];
        int n = 0;
        xs[n] = barLeft; cs[n] = zoneColor(minVal); n++;
        xs[n] = barLeft + barW; cs[n] = zoneColor(maxVal); n++;
        for (Zone z : zones) {
            float s = Math.max(minVal, Math.min(maxVal, z.start));
            float e = Math.max(minVal, Math.min(maxVal, z.end));
            xs[n] = valToX(s, barLeft, barW); cs[n] = z.color; n++;
            xs[n] = valToX(e, barLeft, barW); cs[n] = z.endColor; n++;
        }
        for (int i = 1; i < n; i++) {
            float x = xs[i]; int c = cs[i];
            int j = i - 1;
            while (j >= 0 && xs[j] > x) { xs[j + 1] = xs[j]; cs[j + 1] = cs[j]; j--; }
            xs[j + 1] = x; cs[j + 1] = c;
        }

        float[] positions = new float[n];
        int[] colors = new int[n];
        int kept = 0;
        float minSep = Math.max(.0001f, Math.min(.004f, .75f / Math.max(1f, barW)));
        for (int i = 0; i < n; i++) {
            float p = Math.max(0f, Math.min(1f, (xs[i] - barLeft) / barW));
            if (kept > 0 && Math.abs(p - positions[kept - 1]) < .00001f
                    && cs[i] == colors[kept - 1]) continue;
            if (kept > 0 && p <= positions[kept - 1])
                p = Math.min(1f, positions[kept - 1] + minSep);
            positions[kept] = p;
            colors[kept] = cs[i];
            kept++;
        }
        // Ensure the shader spans the complete track even when the last colour
        // transition had to be nudged by minSep.
        positions[0] = 0f;
        positions[kept - 1] = 1f;

        float[] finalPositions = new float[kept];
        int[] finalColors = new int[kept];
        System.arraycopy(positions, 0, finalPositions, 0, kept);
        System.arraycopy(colors, 0, finalColors, 0, kept);
        trackShader = new LinearGradient(barLeft, 0, barLeft + barW, 0,
                finalColors, finalPositions, Shader.TileMode.CLAMP);
        trackShaderRevision = zoneRevision;
        trackShaderLeft = barLeft;
        trackShaderWidth = barW;
        return trackShader;
    }

    private void drawIndicator(Canvas canvas, float x, float top, float bottom) {
        indicatorPaint.setColor(0xFFF4F8F8);
        indicatorPaint.setStyle(Paint.Style.FILL);
        float stroke = indicatorPaint.getStrokeWidth();
        float px = Math.max(getPaddingLeft(),
                Math.min(getWidth() - getPaddingRight() - stroke, Math.round(x - stroke / 2f)));

        // Sub-pixel-looking but actually integer-aligned halo: improves cursor
        // separation from saturated track colours without changing live position.
        float halo = Math.max(1, Math.round(density));
        edgeGlowPaint.setColor(0x30FFFFFF);
        canvas.drawRect(Math.max(getPaddingLeft(), px - halo), top,
                Math.min(getWidth() - getPaddingRight(), px + stroke + halo), bottom,
                edgeGlowPaint);
        canvas.drawRect(px, top, px + stroke, bottom, indicatorPaint);
    }

    // ========== 情绪渲染: 渐变跟随 ==========

    /**
     * 情绪发光: 微弱渐变覆盖, 不闪烁不脉冲。
     * "感受到，但不打扰"
     */
    private void drawEmotionGlow(Canvas canvas, float fillLeft, float fillRight,
            float barLeft, float barRight, float barTop, float barBottom) {
        float t = emotionCurrent;
        if (t < 0.01f) return;

        int alpha, r, g, b;
        switch (emotion) {
            case EMOTION_BUILDING:
                alpha = (int)(t * 25);  // 极微弱
                r = 255; g = 170; b = 0;
                break;
            case EMOTION_DANGER:
                alpha = (int)(t * 35);
                r = 255; g = 68; b = 68;
                break;
            case EMOTION_PROTECTION:
                alpha = (int)(t * 25);
                r = 255; g = 136; b = 0;
                break;
            case EMOTION_RELEASING:
                // 亮度缓慢降低, 不是瞬间变灰
                alpha = (int)(t * 20);
                r = 100; g = 180; b = 255;
                break;
            case EMOTION_WARNING:
                alpha = (int)(t * 20);
                r = 210; g = 153; b = 34;
                break;
            default:
                return;
        }

        glowPaint.setColor(Color.argb(alpha, r, g, b));
        canvas.drawRect(fillLeft, barTop, fillRight, barBottom, glowPaint);
    }
}
