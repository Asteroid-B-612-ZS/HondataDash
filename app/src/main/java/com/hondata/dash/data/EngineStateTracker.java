package com.hondata.dash.data;

/**
 * 引擎状态检测器 V2 — ECU 语义模型。
 *
 * 输出: EngineSemanticState (MainState + SubState + Modifier + confidence)
 *
 * MainState 优先级: DFCO > WOT > WARMUP > IDLE > NORMAL
 *
 * WOT: ECU 策略层 — ClosedLoop OFF + TargetLambda < 0.95 + MAP > 120 + RPM > 1500
 * DFCO: 四重锁定 — TP<2 + Speed>15 + Inj<0.5ms + RPM>1400
 * WARMUP: ECT 滞回 — 进入 <65°C, 退出 >72°C + ClosedLoop ON 持续5s
 *
 * SubState: WOT(SPOOL/PEAK/HOLD) + DFCO(ENTER/HOLD)
 * Modifier: TIP_IN/TIP_OUT/BOOST_SURGE/RPM_DIP (正交叠加)
 * Confidence: 加权计算 + 低通滤波 (~200ms 惯性)
 */
public class EngineStateTracker {

    private final EngineSemanticState state = new EngineSemanticState();

    // === MainState 滞回 ===
    private EngineSemanticState.MainState currentMain = EngineSemanticState.MainState.NORMAL;
    private EngineSemanticState.MainState candidateMain = EngineSemanticState.MainState.NORMAL;
    private long candidateMainSince = 0;
    private long mainStateSince = 0;  // 当前 MainState 持续时间

    // === WARMUP ECT 滞回 ===
    private boolean warmupActive = false;
    private long warmupExitCandidateSince = 0;

    // === 基线值 (变化率计算) ===
    private float lastTp = 0;
    private float lastRpm = 0;
    private float lastMap = 0;
    private long lastTime = 0;
    private boolean initialized = false;

    // === 置信度低通滤波 ===
    private float smoothConfidence = 1.0f;

    // === 滞回时间 (ms) ===
    private static final long HYSTERESIS_DFCO_ENTER    = 100;
    private static final long HYSTERESIS_DFCO_EXIT     = 50;
    private static final long HYSTERESIS_WOT_ENTER     = 30;
    private static final long HYSTERESIS_WOT_EXIT      = 80;
    private static final long HYSTERESIS_WARMUP_ENTER  = 500;   // 冷启动确认 0.5s
    private static final long HYSTERESIS_WARMUP_EXIT   = 5000;  // 暖机完成确认 5s
    private static final long HYSTERESIS_IDLE_ENTER    = 200;
    private static final long HYSTERESIS_DEFAULT       = 50;

    // === DFCO 子状态 ===
    private static final long DFCO_ENTER_MS = 200;

    // === WOT 子状态 ===
    private static final float SPOOL_MAP_RATE = 100f;    // kPa/s — 建压判定
    private static final long PEAK_DURATION_MS = 2000;    // PEAK → HOLD 分界

    // === TRANSIENT → Modifier 阈值 ===
    private static final float TP_RATE_THRESHOLD  = 50f;    // %/s
    private static final float RPM_RATE_THRESHOLD = 1200f;  // rpm/s
    private static final float MAP_RATE_THRESHOLD = 300f;   // kPa/s

    // === V2: WOT ECU 语义阈值 ===
    private static final float LAMBDA_WOT_MAX = 0.95f;
    private static final int   MAP_WOT_MIN    = 120;     // kPa
    private static final int   RPM_WOT_MIN    = 1500;
    private static final int   RPM_DFCO_MIN   = 1400;

    // === WARMUP ECT 滞回阈值 ===
    private static final float ECT_WARMUP_ENTER = 65f;   // °C — 低于此进入暖机
    // V2.6.8 (M4) / V2.6.9 (P2-6): 退出条件只看 ECT, 不再要求 CL=ON
    // 说明: 旧条件要求 "ECT>72 且 CL=ON"。CL=ON 时旧条件更易满足 (并非"永真导致不退出")
    // 改为只看 ECT 的原因: 让暖机退出只依赖物理温度, 与闭环状态解耦, 语义更清晰
    private static final float ECT_WARMUP_EXIT  = 72f;   // °C — 高于此退出暖机

    // === 置信度低通滤波系数 ===
    private static final float CONFIDENCE_ALPHA = 0.1f;  // ~200ms 惯性 @50Hz

    // ============================================================
    // 核心更新方法 — 每帧调用
    // ============================================================

    public EngineSemanticState update(SensorData data) {
        float rpm          = (float) data.getDouble(0x0100);
        float tp           = (float) data.getDouble(0x0122);
        float inj          = (float) data.getDouble(0x0130);
        float speed        = (float) data.getDouble(0x0101);
        float mapVal       = (float) data.getDouble(0x0110);
        float closedLoop   = (float) data.getDouble(0x0340);
        float targetLambda = (float) data.getDouble(0x0322);
        float ect          = (float) data.getDouble(0x0160);

        long now = System.currentTimeMillis();

        // 首帧: 仅记录基线值
        if (!initialized) {
            lastTime = now;
            lastTp = tp;
            lastRpm = rpm;
            lastMap = mapVal;
            initialized = true;
            warmupActive = (ect < ECT_WARMUP_ENTER);
            return state;
        }

        float dt = (now - lastTime) / 1000f;

        // ----------------------------------------------------------
        // 1. MainState 判定 (优先级: DFCO > WOT > WARMUP > IDLE > NORMAL)
        // ----------------------------------------------------------
        EngineSemanticState.MainState desired;

        // DFCO: 四重锁定
        if (tp < 2 && speed > 15 && inj < 0.5 && rpm > RPM_DFCO_MIN) {
            desired = EngineSemanticState.MainState.DFCO;
        }
        // WOT: ECU 策略层 + 负荷层
        else if (closedLoop < 0.5f && targetLambda < LAMBDA_WOT_MAX
                && rpm > RPM_WOT_MIN && mapVal > MAP_WOT_MIN) {
            desired = EngineSemanticState.MainState.WOT;
        }
        // WARMUP: ECT 滞回 (独立于 candidate/sustain 系统)
        else if (detectWarmup(ect, closedLoop, mapVal, tp, rpm, speed, now)) {
            desired = EngineSemanticState.MainState.WARMUP;
        }
        // IDLE: 三重锁定
        else if (rpm < 1000 && tp < 2 && speed < 3) {
            desired = EngineSemanticState.MainState.IDLE;
        }
        // NORMAL
        else {
            desired = EngineSemanticState.MainState.NORMAL;
        }

        // MainState 滞回 (candidate/sustain)
        if (desired != candidateMain) {
            candidateMain = desired;
            candidateMainSince = now;
        }
        long sustainMs = now - candidateMainSince;
        long threshold = getMainThreshold(currentMain, candidateMain);
        if (sustainMs >= threshold) {
            if (currentMain != candidateMain) {
                mainStateSince = now;
            }
            currentMain = candidateMain;
        }
        state.main = currentMain;

        // ----------------------------------------------------------
        // 2. SubState 判定
        // ----------------------------------------------------------
        state.sub = detectSubState(dt, mapVal, now);

        // ----------------------------------------------------------
        // 3. Modifier 判定 (正交于 MainState)
        // ----------------------------------------------------------
        if (dt > 0.001f) {
            state.modifier = detectModifier(dt, tp - lastTp, rpm - lastRpm, mapVal - lastMap);
        } else {
            state.modifier = EngineSemanticState.Modifier.NONE;
        }

        // ----------------------------------------------------------
        // 4. Confidence: 加权计算 + 低通滤波
        // ----------------------------------------------------------
        // V2.6.9 (P1-7): 关键 PID 缺失 (NaN) 时, confidence 强制衰减到 0
        // 否则空数据会落入 NORMAL 且 computeConfidence(NORMAL)=1.0, 语义错误
        boolean criticalPidMissing = Double.isNaN(data.getDouble(0x0100))   // RPM
                || Double.isNaN(data.getDouble(0x0122))                      // TP
                || Double.isNaN(data.getDouble(0x0110))                      // MAP
                || Double.isNaN(data.getDouble(0x0101))                      // Speed
                || Double.isNaN(data.getDouble(0x0130));                     // Inj
        float instant;
        if (criticalPidMissing) {
            instant = 0f;  // 关键数据缺失 → 不可信
        } else {
            instant = computeConfidence(currentMain, data);
        }
        smoothConfidence += CONFIDENCE_ALPHA * (instant - smoothConfidence);
        state.confidence = smoothConfidence;

        // 更新基线值
        lastTime = now;
        lastTp = tp;
        lastRpm = rpm;
        lastMap = mapVal;
        return state;
    }

    public EngineSemanticState getState() { return state; }

    /** 重置状态追踪器 — 蓝牙断线/重连时调用 */
    public void reset() {
        initialized = false;
        currentMain = EngineSemanticState.MainState.NORMAL;
        candidateMain = EngineSemanticState.MainState.NORMAL;
        // V2.6.9 (P1-8): 补全所有计时和历史字段, 真正完成"重置"契约
        candidateMainSince = 0L;
        mainStateSince = 0L;
        warmupActive = false;
        warmupExitCandidateSince = 0L;
        lastTime = 0L;
        lastTp = 0f;
        lastRpm = 0f;
        lastMap = 0f;
        smoothConfidence = 1.0f;
        state.main = EngineSemanticState.MainState.NORMAL;
        state.sub = EngineSemanticState.SubState.NONE;
        state.modifier = EngineSemanticState.Modifier.NONE;
        state.confidence = 1.0f;
    }

    // ============================================================
    // WARMUP ECT 滞回检测
    // ============================================================

    /**
     * WARMUP 独立滞回系统:
     *   进入: ECT < 65 + ClosedLoop OFF + MAP < 100 + TP < 5 + RPM 800~1800
     *   退出: ECT > 72 + ClosedLoop ON, 持续 5s
     *   ECT 7°C 间隙 (65~72) 防止温度在阈值附近抖动
     */
    /**
     * WARMUP ECT 滞回检测
     * V2.6.8 (M4): 退出条件改为 ECT>72 即可, 不再要求 CL=ON
     */
    private boolean detectWarmup(float ect, float closedLoop, float mapVal,
                                  float tp, float rpm, float speed, long now) {
        // 退出判定: ECT > 72 (无需 CL=ON)
        boolean exitCondition = (ect > ECT_WARMUP_EXIT);

        if (warmupActive) {
            if (exitCondition) {
                if (warmupExitCandidateSince == 0) {
                    warmupExitCandidateSince = now;
                }
                if (now - warmupExitCandidateSince >= HYSTERESIS_WARMUP_EXIT) {
                    warmupActive = false;
                    warmupExitCandidateSince = 0;
                    return false;
                }
            } else {
                warmupExitCandidateSince = 0; // 不满足则重置退出计时
            }
            return true; // WARMUP 持续中
        } else {
            // 进入判定: 冷车 + 无增压 + 未踩油 + 怠速区间 + 车速低
            // (CL 仍作为进入门槛, L15B7 在冷启时通常先开环再闭环)
            if (ect < ECT_WARMUP_ENTER && closedLoop < 0.5f
                    && mapVal < 100 && tp < 5
                    && rpm > 800 && rpm < 1800 && speed < 5) {
                return true; // WARMUP 进入 (由外层 candidate/sustain 加 500ms 确认)
            }
            return false;
        }
    }

    // ============================================================
    // SubState 检测
    // ============================================================

    private EngineSemanticState.SubState detectSubState(float dt, float mapVal, long now) {
        long duration = now - mainStateSince;

        if (currentMain == EngineSemanticState.MainState.WOT) {
            // SPOOL: MAP 仍在快速上升
            if (dt > 0.001f && (mapVal - lastMap) / dt > SPOOL_MAP_RATE) {
                return EngineSemanticState.SubState.SPOOL;
            }
            // PEAK: MAP 趋稳 + 持续 < 2s (峰值扭矩的动态窗口)
            if (duration < PEAK_DURATION_MS) {
                return EngineSemanticState.SubState.PEAK;
            }
            // HOLD: 稳态全负荷
            return EngineSemanticState.SubState.HOLD;
        }

        if (currentMain == EngineSemanticState.MainState.DFCO) {
            if (duration < DFCO_ENTER_MS) {
                return EngineSemanticState.SubState.DFCO_ENTER;
            }
            return EngineSemanticState.SubState.DFCO_HOLD;
        }

        return EngineSemanticState.SubState.NONE;
    }

    // ============================================================
    // Modifier 检测 (正交叠加)
    // ============================================================

    private EngineSemanticState.Modifier detectModifier(float dt, float dTp, float dRpm, float dMap) {
        float tpRate = dTp / dt;
        float rpmRate = dRpm / dt;
        float mapRate = dMap / dt;

        // 优先级: RPM_DIP > BOOST_SURGE > TIP_OUT > TIP_IN
        if (rpmRate < -RPM_RATE_THRESHOLD) {
            return EngineSemanticState.Modifier.RPM_DIP;
        }
        if (Math.abs(mapRate) > MAP_RATE_THRESHOLD) {
            return EngineSemanticState.Modifier.BOOST_SURGE;
        }
        if (tpRate < -TP_RATE_THRESHOLD) {
            return EngineSemanticState.Modifier.TIP_OUT;
        }
        if (tpRate > TP_RATE_THRESHOLD) {
            return EngineSemanticState.Modifier.TIP_IN;
        }
        return EngineSemanticState.Modifier.NONE;
    }

    // ============================================================
    // Confidence: 加权计算
    // ============================================================

    private float computeConfidence(EngineSemanticState.MainState main, SensorData data) {
        switch (main) {
            case DFCO:   return dfcoConfidence(data);
            case WOT:    return wotConfidence(data);
            case WARMUP: return warmupConfidence(data);
            case IDLE:   return idleConfidence(data);
            default:     return 1.0f; // NORMAL: 默认完全可信
        }
    }

    private float wotConfidence(SensorData data) {
        float cl     = (float) data.getDouble(0x0340);
        float lambda = (float) data.getDouble(0x0322);
        float map    = (float) data.getDouble(0x0110);
        float rpm    = (float) data.getDouble(0x0100);

        float score = 0;
        score += (cl < 0.5f) ? 0.35f : 0;                                    // Open Loop
        score += 0.25f * clamp01((0.95f - lambda) / 0.15f);                  // Lambda 富油程度
        score += 0.25f * clamp01((map - 120f) / 80f);                         // MAP 负荷
        score += (rpm > 1500) ? 0.15f : 0;                                    // RPM
        return score;
    }

    private float dfcoConfidence(SensorData data) {
        float tp    = (float) data.getDouble(0x0122);
        float speed = (float) data.getDouble(0x0101);
        float inj   = (float) data.getDouble(0x0130);
        float rpm   = (float) data.getDouble(0x0100);

        float score = 0;
        score += (tp < 2) ? 0.20f : 0;                                       // 节气门关闭
        score += (speed > 15) ? 0.20f : 0;                                    // 车辆行驶
        score += 0.35f * clamp01((0.5f - inj) / 0.5f);                       // 喷油切断程度
        score += (rpm > 1400) ? 0.15f : 0;                                    // RPM 足够高
        return score;
    }

    private float warmupConfidence(SensorData data) {
        float ect  = (float) data.getDouble(0x0160);
        float cl   = (float) data.getDouble(0x0340);
        float map  = (float) data.getDouble(0x0110);

        float score = 0;
        score += (cl < 0.5f) ? 0.25f : 0;                                    // Open Loop
        score += 0.30f * clamp01((65f - ect) / 35f);                          // ECT 越低越确信
        score += (map < 100) ? 0.15f : 0;                                     // 无增压
        score += 0.15f;                                                        // 基础分 (WARMUP 状态可信)
        return score;
    }

    private float idleConfidence(SensorData data) {
        float rpm   = (float) data.getDouble(0x0100);
        float tp    = (float) data.getDouble(0x0122);
        float speed = (float) data.getDouble(0x0101);

        float score = 0;
        score += 0.30f * clamp01((1000f - rpm) / 300f);                       // RPM 越接近怠速越确信
        score += (tp < 2) ? 0.35f : 0;                                        // 节气门关闭
        score += (speed < 3) ? 0.35f : 0;                                     // 车辆静止
        return score;
    }

    private static float clamp01(float v) {
        // V2.6.8 (L6): NaN 比较永远 false, 显式返回 0 避免污染 score
        if (Float.isNaN(v)) return 0f;
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    // ============================================================
    // MainState 滞回阈值
    // ============================================================

    private long getMainThreshold(EngineSemanticState.MainState from,
                                   EngineSemanticState.MainState to) {
        if (to == EngineSemanticState.MainState.DFCO)    return HYSTERESIS_DFCO_ENTER;
        if (from == EngineSemanticState.MainState.DFCO)  return HYSTERESIS_DFCO_EXIT;
        if (to == EngineSemanticState.MainState.WOT)     return HYSTERESIS_WOT_ENTER;
        if (from == EngineSemanticState.MainState.WOT)   return HYSTERESIS_WOT_EXIT;
        if (to == EngineSemanticState.MainState.WARMUP)  return HYSTERESIS_WARMUP_ENTER;
        if (from == EngineSemanticState.MainState.WARMUP) return HYSTERESIS_WARMUP_EXIT;
        if (to == EngineSemanticState.MainState.IDLE)    return HYSTERESIS_IDLE_ENTER;
        return HYSTERESIS_DEFAULT;
    }
}
