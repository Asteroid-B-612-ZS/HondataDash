package com.hondata.dash.data;

/**
 * 引擎状态检测器 — 带滞回防抖动 + 多条件瞬态判定 + 优先级系统。
 *
 * 优先级: DFCO > TRANSIENT > WOT > IDLE > NORMAL
 *
 * TRANSIENT 三重判定 (MT 车必备):
 *   |dTP/dt|   > 50 %/s   (快速松油)
 *   |dRPM/dt|  > 1200 rpm/s (换挡 RPM 暴跌)
 *   |dMAP/dt|  > 300 kPa/s (增压崩溃)
 *   任一触发即判定为瞬态
 *
 * 滞回: 状态切换需持续满足条件一定时间，防止抖动
 */
public class EngineStateTracker {

    public enum State { IDLE, NORMAL, WOT, DFCO, TRANSIENT }

    private State current = State.NORMAL;
    private State candidate = State.NORMAL;
    private long candidateSince = 0;

    private float lastTp = 0;
    private float lastRpm = 0;
    private float lastMap = 0;
    private long lastTime = 0;
    private boolean initialized = false;

    // 滞回时间 (ms) — V1.4 全面缩短，基于实车数据验证
    private static final long HYSTERESIS_DFCO_ENTER  = 100;   // 松油信号明确(TP<2+Inj<0.5+Speed>15 三重条件)
    private static final long HYSTERESIS_DFCO_EXIT   = 50;    // TP恢复>2%无歧义，50ms仅防抖
    private static final long HYSTERESIS_WOT_ENTER   = 30;    // TP>80%完全无歧义
    private static final long HYSTERESIS_WOT_EXIT    = 80;    // 适度缩短
    private static final long HYSTERESIS_IDLE_ENTER  = 200;   // 稍微缩短
    private static final long HYSTERESIS_TRANSIENT   = 40;    // 快速捕捉瞬态
    private static final long HYSTERESIS_DEFAULT     = 50;    // 缩短通用滞回

    // TRANSIENT 触发阈值 (变化率/秒，与采样率无关)
    private static final float TP_RATE_THRESHOLD  = 50f;    // %/s
    private static final float RPM_RATE_THRESHOLD = 1200f;  // rpm/s
    private static final float MAP_RATE_THRESHOLD = 300f;   // kPa/s

    public State update(SensorData data) {
        float rpm    = (float) data.getDouble(0x100);
        float tp     = (float) data.getDouble(0x122);
        float inj    = (float) data.getDouble(0x130);
        float speed  = (float) data.getDouble(0x101);
        float mapVal = (float) data.getDouble(0x110);

        long now = System.currentTimeMillis();

        // 首帧: 仅记录基线值，不判定状态
        if (!initialized) {
            lastTime = now;
            lastTp = tp;
            lastRpm = rpm;
            lastMap = mapVal;
            initialized = true;
            return State.NORMAL;
        }

        float dt = (now - lastTime) / 1000f;

        // 优先级判定: DFCO > TRANSIENT > WOT > IDLE > NORMAL
        State desired;

        // 1. DFCO: 节气门关闭 + 车速>15 + 喷油断开
        if (tp < 2 && speed > 15 && inj < 0.5) {
            desired = State.DFCO;
        }
        // 2. TRANSIENT: TP/RPM/MAP 任一变化率超阈值
        else if (dt > 0.001f && (
            Math.abs(tp - lastTp) / dt > TP_RATE_THRESHOLD ||
            Math.abs(rpm - lastRpm) / dt > RPM_RATE_THRESHOLD ||
            Math.abs(mapVal - lastMap) / dt > MAP_RATE_THRESHOLD
        )) {
            desired = State.TRANSIENT;
        }
        // 3. WOT: 节气门大开
        else if (tp > 80) {
            desired = State.WOT;
        }
        // 4. IDLE: 低转速 + 节气门关闭 + 车速<3
        else if (rpm < 1000 && tp < 2 && speed < 3) {
            desired = State.IDLE;
        }
        // 5. NORMAL
        else {
            desired = State.NORMAL;
        }

        // 滞回: 期望状态变化时重置计时器
        if (desired != candidate) {
            candidate = desired;
            candidateSince = now;
        }

        // 满足滞回时间才切换
        long sustainMs = now - candidateSince;
        long threshold = getThreshold(current, candidate);
        if (sustainMs >= threshold) {
            current = candidate;
        }

        lastTime = now;
        lastTp = tp;
        lastRpm = rpm;
        lastMap = mapVal;
        return current;
    }

    public State getState() { return current; }

    private long getThreshold(State from, State to) {
        if (to == State.DFCO)      return HYSTERESIS_DFCO_ENTER;
        if (from == State.DFCO)    return HYSTERESIS_DFCO_EXIT;
        if (to == State.WOT)       return HYSTERESIS_WOT_ENTER;
        if (from == State.WOT)     return HYSTERESIS_WOT_EXIT;
        if (to == State.IDLE)      return HYSTERESIS_IDLE_ENTER;
        if (to == State.TRANSIENT) return HYSTERESIS_TRANSIENT;
        return HYSTERESIS_DEFAULT;
    }
}
