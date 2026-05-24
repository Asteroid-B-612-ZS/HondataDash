package com.hondata.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
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
    private float[] ticks = new float[0];
    private String[] tickLabels = new String[0];
    private boolean showLabels = true;
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
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float density;
    private long lastFrameTime = 0;
    private float lastInputVal = Float.NaN;

    public static class Zone {
        public final float start, end;
        public final int color;
        public Zone(float s, float e, int c) { start = s; end = e; color = c; }
    }

    public ScaleBarView(Context ctx) { super(ctx); init(ctx); }
    public ScaleBarView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(ctx); }
    public ScaleBarView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init(ctx);
    }

    private void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;

        bgPaint.setColor(0xFF1A1A1A);
        bgPaint.setStyle(Paint.Style.FILL);

        fillPaint.setStyle(Paint.Style.FILL);

        tickPaint.setColor(0xFF666666);
        tickPaint.setStrokeWidth(Math.max(1, density * 0.8f));
        tickPaint.setStyle(Paint.Style.STROKE);

        labelPaint.setColor(0xFFBBBBBB);
        labelPaint.setTextSize(11 * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        indicatorPaint.setColor(0xFFFFFFFF);
        indicatorPaint.setStrokeWidth(1.5f * density);
        indicatorPaint.setStyle(Paint.Style.STROKE);

        zoneBgPaint.setStyle(Paint.Style.FILL);

        zoneLinePaint.setStyle(Paint.Style.STROKE);
        zoneLinePaint.setStrokeWidth(density * 0.5f);

        borderPaint.setColor(0xFF333333);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1 * density);

        peakFillPaint.setStyle(Paint.Style.FILL);

        peakMarkerPaint.setStyle(Paint.Style.STROKE);
        peakMarkerPaint.setStrokeWidth(2 * density);

        glowPaint.setStyle(Paint.Style.FILL);
    }

    // ========== 公开接口 ==========

    public void setRange(float min, float max) { minVal = min; maxVal = max; invalidate(); }
    public void setTicks(float[] v, String[] l) { ticks = v; tickLabels = l; invalidate(); }
    public void addZone(float s, float e, int c) { zones.add(new Zone(s, e, c)); invalidate(); }
    public void setAnchor(float a) { anchorVal = a; }
    public void setShowLabels(boolean show) { showLabels = show; invalidate(); }
    public void setExpand(float start, float end, float factor) {
        expandStart = start; expandEnd = end; expandFactor = factor; invalidate();
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
        if (Float.isNaN(v)) {
            curVal = v;
            invalidate();
            return;
        }
        curVal = v;
        long now = System.currentTimeMillis();
        float dt = lastFrameTime > 0 ? Math.min((now - lastFrameTime) / 1000f, 0.1f) : 0.02f;
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

    private int zoneColor(float v) {
        for (Zone z : zones) {
            if (v >= z.start && v <= z.end) return z.color;
        }
        return 0xFF00D8FF;
    }

    // ========== 绘制 ==========

    @Override
    protected void onDraw(Canvas canvas) {
        float pL = getPaddingLeft(), pR = getPaddingRight();
        float pT = getPaddingTop(), pB = getPaddingBottom();
        float barW = getWidth() - pL - pR;
        float barLeft = pL;

        float barH = 18 * density;
        float barBottom = getHeight() - pB;
        float barTop = barBottom - barH;
        float barRight = barLeft + barW;

        float tickLen = 5 * density;
        float tickBottom = barTop - density;
        float tickTop = tickBottom - tickLen;
        float labelY = tickTop - density;

        // 1. 刻度数字和刻度线
        float lastLabelRight = Float.MIN_VALUE;
        float minLabelGap = 6 * density;
        for (int i = 0; i < ticks.length; i++) {
            float x = valToX(ticks[i], barLeft, barW);

            if (showLabels && tickLabels != null && i < tickLabels.length
                    && tickLabels[i] != null && !tickLabels[i].isEmpty()) {
                float tw = labelPaint.measureText(tickLabels[i]);
                float labelLeft = x - tw / 2;
                float labelRight = x + tw / 2;
                boolean skipLabel = false;

                Paint.Align saved = labelPaint.getTextAlign();
                if (labelLeft < pL) {
                    labelPaint.setTextAlign(Paint.Align.LEFT);
                    labelLeft = pL;
                    labelRight = pL + tw;
                } else if (labelRight > getWidth() - pR) {
                    labelPaint.setTextAlign(Paint.Align.RIGHT);
                    labelRight = getWidth() - pR;
                    labelLeft = labelRight - tw;
                }

                if (labelLeft < lastLabelRight + minLabelGap) skipLabel = true;

                if (!skipLabel) {
                    canvas.drawText(tickLabels[i], x, labelY, labelPaint);
                    lastLabelRight = labelRight;
                }
                labelPaint.setTextAlign(saved);
            }

            float tickX = valToX(ticks[i], barLeft, barW);
            canvas.drawLine(tickX, tickTop, tickX, tickBottom, tickPaint);
        }

        // 2. 数据条背景
        canvas.drawRect(barLeft, barTop, barRight, barBottom, bgPaint);
        canvas.drawRect(barLeft, barTop, barRight, barBottom, borderPaint);

        // 3. 颜色区间背景
        for (Zone z : zones) {
            float x1 = valToX(Math.max(z.start, minVal), barLeft, barW);
            float x2 = valToX(Math.min(z.end, maxVal), barLeft, barW);
            zoneBgPaint.setColor(z.color);
            zoneBgPaint.setAlpha(75);
            canvas.drawRect(x1, barTop, x2, barBottom, zoneBgPaint);
        }
        zoneBgPaint.setAlpha(255);

        for (int i = 0; i < zones.size() - 1; i++) {
            float x = valToX(zones.get(i).end, barLeft, barW);
            zoneLinePaint.setColor(zones.get(i + 1).color);
            zoneLinePaint.setAlpha(130);
            canvas.drawLine(x, barTop, x, barBottom, zoneLinePaint);
        }
        zoneLinePaint.setAlpha(255);

        // 4. 按原型绘制填充条 + 峰值/残影/包络
        if (!Float.isNaN(curVal)) {
            float clamped = Math.max(minVal, Math.min(maxVal, curVal));
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

    // ========== ARCH_STATIC: 纯显示, 无残影 ==========

    private void drawStaticBar(Canvas canvas, float ax, float clamped,
            float barLeft, float barRight, float barTop, float barBottom, float barW) {
        float vx = valToX(clamped, barLeft, barW);
        float fillLeft = Math.min(ax, vx);
        float fillRight = Math.max(ax, vx);

        drawFillBar(canvas, fillLeft, fillRight, barTop, barBottom, clamped);
        drawIndicator(canvas, vx, barTop, barBottom);

        // 无峰值, 无残影, 灰色填充未覆盖区
        if (fillRight < barRight) {
            peakFillPaint.setColor(0xFF2A2A2A);
            canvas.drawRect(fillRight, barTop, barRight, barBottom, peakFillPaint);
        }
    }

    // ========== ARCH_THERMAL: Drift Memory Peak (牛顿冷却) ==========

    private void drawThermalBar(Canvas canvas, float ax, float clamped,
            float barLeft, float barRight, float barTop, float barBottom, float barW) {
        float vx = valToX(clamped, barLeft, barW);
        float fillLeft = Math.min(ax, vx);
        float fillRight = Math.max(ax, vx);

        drawFillBar(canvas, fillLeft, fillRight, barTop, barBottom, clamped);
        drawIndicator(canvas, vx, barTop, barBottom);

        // 正方向记忆峰值 (anchor上方)
        if (heatPos > 0.01f) {
            float memoryVal = anchorVal + heatPos;
            float memClamped = Math.max(minVal, Math.min(maxVal, memoryVal));
            float mx = valToX(memClamped, barLeft, barW);

            if (mx > fillRight + 0.5f && mx <= barRight) {
                float memAlpha = Math.min(1f, heatPos * 0.4f);
                int memColor = zoneColor(memClamped);
                peakFillPaint.setColor(Color.argb((int)(memAlpha * 160),
                    Color.red(memColor), Color.green(memColor), Color.blue(memColor)));
                canvas.drawRect(fillRight, barTop, mx, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(memAlpha * 120),
                    Color.red(memColor) / 2, Color.green(memColor) / 2, Color.blue(memColor) / 2));
                canvas.drawLine(mx, barTop, mx, barBottom, peakMarkerPaint);
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
                peakFillPaint.setColor(Color.argb((int)(memAlpha * 160),
                    Color.red(memColor), Color.green(memColor), Color.blue(memColor)));
                canvas.drawRect(mx, barTop, fillLeft, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(memAlpha * 120),
                    Color.red(memColor) / 2, Color.green(memColor) / 2, Color.blue(memColor) / 2));
                canvas.drawLine(mx, barTop, mx, barBottom, peakMarkerPaint);
                fillLeft = mx;
            }
        }

        // 灰色未覆盖 (两侧)
        if (fillLeft > barLeft) {
            peakFillPaint.setColor(0xFF2A2A2A);
            canvas.drawRect(barLeft, barTop, fillLeft, barBottom, peakFillPaint);
        }
        if (fillRight < barRight) {
            peakFillPaint.setColor(0xFF2A2A2A);
            canvas.drawRect(fillRight, barTop, barRight, barBottom, peakFillPaint);
        }

        // 情绪: 微弱温暖感 (热量高时填充区微亮)
        drawEmotionGlow(canvas, fillLeft, fillRight, barLeft, barRight, barTop, barBottom);
    }

    // ========== ARCH_MECHANICAL: Spring-Damper 物理残影 ==========

    private void drawMechanicalBar(Canvas canvas, float ax, float clamped,
            float barLeft, float barRight, float barTop, float barBottom, float barW) {

        // position 就是显示值 (带 overshoot/rebound)
        float posClamped = Float.isNaN(mechPosition) ? clamped
            : Math.max(minVal, Math.min(maxVal, mechPosition));
        float vx = valToX(posClamped, barLeft, barW);
        float fillLeft = Math.min(ax, vx);
        float fillRight = Math.max(ax, vx);

        drawFillBar(canvas, fillLeft, fillRight, barTop, barBottom, clamped);
        drawIndicator(canvas, vx, barTop, barBottom);

        float resAlpha = Math.min(1f, 0.6f + Math.abs(mechVelocity) * 0.4f);

        // 正方向残影: position > curVal (右侧)
        float residual = Float.isNaN(mechPosition) ? 0 : mechPosition - clamped;
        if (residual > 0.005f) {
            float posXC = valToX(Math.max(minVal, Math.min(maxVal, mechPosition)), barLeft, barW);
            if (posXC > fillRight + 0.5f && posXC <= barRight) {
                int resColor = zoneColor(Math.max(minVal, Math.min(maxVal, mechPosition)));
                peakFillPaint.setColor(Color.argb((int)(resAlpha * 160),
                    Color.red(resColor), Color.green(resColor), Color.blue(resColor)));
                canvas.drawRect(fillRight, barTop, posXC, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(resAlpha * 120),
                    Color.red(resColor) / 2, Color.green(resColor) / 2, Color.blue(resColor) / 2));
                canvas.drawLine(posXC, barTop, posXC, barBottom, peakMarkerPaint);
                fillRight = posXC;
            }
        }

        // 负方向残影: position < curVal (左侧) — IGN 负值回弹时
        if (residual < -0.005f) {
            float posXC = valToX(Math.max(minVal, Math.min(maxVal, mechPosition)), barLeft, barW);
            if (posXC < fillLeft - 0.5f && posXC >= barLeft) {
                int resColor = zoneColor(Math.max(minVal, Math.min(maxVal, mechPosition)));
                peakFillPaint.setColor(Color.argb((int)(resAlpha * 160),
                    Color.red(resColor), Color.green(resColor), Color.blue(resColor)));
                canvas.drawRect(posXC, barTop, fillLeft, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(resAlpha * 120),
                    Color.red(resColor) / 2, Color.green(resColor) / 2, Color.blue(resColor) / 2));
                canvas.drawLine(posXC, barTop, posXC, barBottom, peakMarkerPaint);
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
                peakFillPaint.setColor(Color.argb((int)(peakAlpha * 180),
                    Color.red(peakColor), Color.green(peakColor), Color.blue(peakColor)));
                canvas.drawRect(fillRight, barTop, px, barBottom, peakFillPaint);

                peakMarkerPaint.setStrokeWidth(3 * density);
                peakMarkerPaint.setColor(Color.argb((int)(peakAlpha * 220),
                    Color.red(peakColor), Color.green(peakColor), Color.blue(peakColor)));
                canvas.drawLine(px, barTop, px, barBottom, peakMarkerPaint);
                peakMarkerPaint.setStrokeWidth(2 * density);
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
                peakFillPaint.setColor(Color.argb((int)(peakAlpha * 180),
                    Color.red(peakColor), Color.green(peakColor), Color.blue(peakColor)));
                canvas.drawRect(px, barTop, fillLeft, barBottom, peakFillPaint);

                peakMarkerPaint.setStrokeWidth(3 * density);
                peakMarkerPaint.setColor(Color.argb((int)(peakAlpha * 220),
                    Color.red(peakColor), Color.green(peakColor), Color.blue(peakColor)));
                canvas.drawLine(px, barTop, px, barBottom, peakMarkerPaint);
                peakMarkerPaint.setStrokeWidth(2 * density);
                fillLeft = px;
            }
        }

        // 灰色未覆盖区域 (两侧)
        if (fillLeft > barLeft) {
            peakFillPaint.setColor(0xFF2A2A2A);
            canvas.drawRect(barLeft, barTop, fillLeft, barBottom, peakFillPaint);
        }
        if (fillRight < barRight) {
            peakFillPaint.setColor(0xFF2A2A2A);
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

        drawFillBar(canvas, fillLeft, fillRight, barTop, barBottom, clamped);
        drawIndicator(canvas, vx, barTop, barBottom);

        // 双侧独立包络: envHigh 和 envLow 各自追踪和衰减
        // 右侧包络 (anchor 上方, 高侧)
        if (envHigh > 0.01f) {
            float envHighVal = anchorVal + envHigh;
            float envHighClamped = Math.max(minVal, Math.min(maxVal, envHighVal));
            float envHx = valToX(envHighClamped, barLeft, barW);

            if (envHx > fillRight + 0.5f) {
                float envAlpha = Math.min(0.7f, envHigh * 0.5f);
                int envColor = zoneColor(envHighClamped);
                peakFillPaint.setColor(Color.argb((int)(envAlpha * 180),
                    Color.red(envColor), Color.green(envColor), Color.blue(envColor)));
                canvas.drawRect(fillRight, barTop, envHx, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(envAlpha * 140),
                    Color.red(envColor) / 2, Color.green(envColor) / 2, Color.blue(envColor) / 2));
                canvas.drawLine(envHx, barTop, envHx, barBottom, peakMarkerPaint);
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
                peakFillPaint.setColor(Color.argb((int)(envAlpha * 180),
                    Color.red(envColor), Color.green(envColor), Color.blue(envColor)));
                canvas.drawRect(envLx, barTop, fillLeft, barBottom, peakFillPaint);

                peakMarkerPaint.setColor(Color.argb((int)(envAlpha * 140),
                    Color.red(envColor) / 2, Color.green(envColor) / 2, Color.blue(envColor) / 2));
                canvas.drawLine(envLx, barTop, envLx, barBottom, peakMarkerPaint);
                fillLeft = envLx;
            }
        }

        // 灰色未覆盖
        if (fillLeft > barLeft) {
            peakFillPaint.setColor(0xFF2A2A2A);
            canvas.drawRect(barLeft, barTop, fillLeft, barBottom, peakFillPaint);
        }
        if (fillRight < barRight) {
            peakFillPaint.setColor(0xFF2A2A2A);
            canvas.drawRect(fillRight, barTop, barRight, barBottom, peakFillPaint);
        }

        // 情绪: 振荡活跃度 (oscEnergy 高时微亮)
        drawEmotionGlow(canvas, fillLeft, fillRight, barLeft, barRight, barTop, barBottom);
    }

    // ========== 通用绘制工具 ==========

    private void drawFillBar(Canvas canvas, float left, float right,
            float top, float bottom, float val) {
        int baseColor = zoneColor(val);
        // 情绪色彩混合 (微弱, 不突兀)
        baseColor = applyEmotionColor(baseColor);

        int lighter = Color.argb(235,
            Math.min(255, Color.red(baseColor) + 70),
            Math.min(255, Color.green(baseColor) + 70),
            Math.min(255, Color.blue(baseColor) + 70));
        LinearGradient g = new LinearGradient(0, top, 0, bottom, lighter, baseColor, Shader.TileMode.CLAMP);
        fillPaint.setShader(g);
        canvas.drawRect(left, top, right, bottom, fillPaint);
        fillPaint.setShader(null);
    }

    private void drawIndicator(Canvas canvas, float x, float top, float bottom) {
        int color = getEmotionIndicatorColor();
        indicatorPaint.setColor(color);
        canvas.drawLine(x, top, x, bottom, indicatorPaint);
        indicatorPaint.setColor(0xFFFFFFFF);
    }

    // ========== 情绪渲染: 渐变跟随 ==========

    private int applyEmotionColor(int baseColor) {
        float t = emotionCurrent;
        if (t < 0.01f) return baseColor;

        switch (emotion) {
            case EMOTION_BUILDING:
                // 微暖: 向橙色偏移
                return blendColor(baseColor, 0xFFFF8800, t * 0.15f);
            case EMOTION_DANGER:
                // 微红: 不闪烁, 只是色调偏移
                return blendColor(baseColor, 0xFFFF3333, t * 0.25f);
            case EMOTION_PROTECTION:
                // 微橙: ECU保护信号
                return blendColor(baseColor, 0xFFFF8800, t * 0.15f);
            case EMOTION_RELEASING:
                // 微冷: 向蓝色偏移, 降低饱和度
                return blendColor(baseColor, 0xFF4488CC, t * 0.1f);
            case EMOTION_WARNING:
                // 微黄
                return blendColor(baseColor, 0xFFD29922, t * 0.15f);
            default:
                return baseColor;
        }
    }

    private int getEmotionIndicatorColor() {
        float t = emotionCurrent;
        if (t < 0.01f) return 0xFFFFFFFF;

        switch (emotion) {
            case EMOTION_BUILDING:
                return blendColor(0xFFFFFFFF, 0xFFFFAA00, t * 0.4f);
            case EMOTION_DANGER:
                return blendColor(0xFFFFFFFF, 0xFFFF3333, t * 0.5f);
            case EMOTION_PROTECTION:
                return blendColor(0xFFFFFFFF, 0xFFFF8800, t * 0.4f);
            case EMOTION_RELEASING:
                return blendColor(0xFFFFFFFF, 0xFF88BBFF, t * 0.3f);
            case EMOTION_WARNING:
                return blendColor(0xFFFFFFFF, 0xFFD29922, t * 0.35f);
            default:
                return 0xFFFFFFFF;
        }
    }

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
                r = 255; g = 40; b = 40;
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

    private int blendColor(int c1, int c2, float ratio) {
        float r = Color.red(c1) + (Color.red(c2) - Color.red(c1)) * ratio;
        float g = Color.green(c1) + (Color.green(c2) - Color.green(c1)) * ratio;
        float b = Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * ratio;
        return Color.argb(255, (int) r, (int) g, (int) b);
    }
}
