package com.hondata.dash.data;

/**
 * V2 引擎语义状态 — 三维状态模型。
 *
 * MainState:  ECU 策略层 (互斥, 有优先级)
 * SubState:   工况细节 (依附于 MainState)
 * Modifier:   动态修饰 (正交于 MainState, 任意主状态均可叠加)
 * confidence: 状态可信度 (0.0~1.0, 低通滤波平滑)
 *
 * 优先级: DFCO > WOT > WARMUP > IDLE > NORMAL
 */
public class EngineSemanticState {

    // === ECU 策略层 (互斥) ===
    public enum MainState {
        DFCO,    // 减速断油 — ECU 完全切断喷油
        WOT,     // 全负荷开环 — ECU 高负荷 Open Loop 富油策略
        WARMUP,  // 暖机 — 冷启动 Open Loop + 催化剂加热
        IDLE,    // 怠速 — 低转速 + 节气门关闭 + 车辆静止
        NORMAL   // 正常巡航
    }

    // === 工况细节 (依附于 MainState) ===
    public enum SubState {
        SPOOL,       // WOT: 涡轮建压中 (dMAP/dt > 100)
        PEAK,        // WOT: 峰值扭矩窗口 (MAP 趋稳, < 2s)
        HOLD,        // WOT: 稳态全负荷 (持续 > 2s)
        DFCO_ENTER,  // DFCO: 断油过渡期 (前 200ms)
        DFCO_HOLD,   // DFCO: 稳定断油滑行
        NONE         // 无子状态
    }

    // === 动态修饰 (正交, 可叠加于任意 MainState) ===
    public enum Modifier {
        TIP_IN,      // dTP/dt > +50 %/s — 快踩油
        TIP_OUT,     // dTP/dt < -50 %/s — 快松油
        BOOST_SURGE, // |dMAP/dt| > 300 kPa/s — 增压突变
        RPM_DIP,     // dRPM/dt < -1200 rpm/s — 转速暴跌 (换挡)
        NONE         // 无修饰
    }

    public MainState main = MainState.NORMAL;
    public SubState sub = SubState.NONE;
    public Modifier modifier = Modifier.NONE;
    public float confidence = 1.0f;

    // === 便捷方法 ===

    public boolean isDfco()    { return main == MainState.DFCO; }
    public boolean isWot()     { return main == MainState.WOT; }
    public boolean isWarmup()  { return main == MainState.WARMUP; }
    public boolean isIdle()    { return main == MainState.IDLE; }
    public boolean isNormal()  { return main == MainState.NORMAL; }
    public boolean hasModifier() { return modifier != Modifier.NONE; }

    /** 滤波 alpha 插值: NORMAL 0.3 ~ WOT 0.7, 由 confidence 连续调制 */
    public float afAlpha() {
        if (main == MainState.WOT) {
            return 0.3f + 0.4f * confidence;
        }
        return 0.3f;
    }

    /** Boost 释放率: DFCO 0.02 / Modifier 0.05 / NORMAL 0.15 */
    public float boostRelease() {
        if (isDfco()) return 0.02f;
        if (hasModifier()) return 0.05f;
        return 0.15f;
    }

    /** 文字透明度: 由 confidence 连续调制 (0.45 ~ 1.0) */
    public float textAlpha(boolean isSensitiveCard) {
        if (!isSensitiveCard) return 1.0f;
        // A/F / S.TRIM: confidence 越低越暗
        return 0.45f + 0.55f * confidence;
    }
}
