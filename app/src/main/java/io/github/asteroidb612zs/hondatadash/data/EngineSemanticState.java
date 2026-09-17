package io.github.asteroidb612zs.hondatadash.data;

/**
 * V2 semantic state.
 *
 * Orthogonal dimensions:
 *  - MainState describes the ECU/load strategy.
 *  - ThermalContext describes engine thermal readiness independently of load strategy.
 *  - Modifier describes driver/load transients.
 *  - ShiftPhase describes clutch/gear shift intent and confirmation.
 *  - CombustionState describes whether combustion-derived PIDs are meaningful.
 *
 * Important invariant produced by EngineStateTracker:
 *   shiftPhase != NONE  <=>  modifier == SHIFT
 *
 * ThermalContext is intentionally independent of MainState so a cold engine may be
 * WOT + COLD/WARMING instead of being forced to choose between WOT and WARMUP.
 */
public class EngineSemanticState {

    public enum MainState {
        DFCO,
        WOT,
        WARMUP,
        IDLE,
        NORMAL
    }

    public enum ThermalContext {
        UNKNOWN,
        COLD,
        WARMING,
        READY
    }

    public enum SubState {
        SPOOL,
        PEAK,
        HOLD,
        DFCO_ENTER,
        DFCO_HOLD,
        NONE
    }

    public enum Modifier {
        TIP_IN,
        TIP_OUT,
        BOOST_SURGE,
        SHIFT,
        COAST,
        NONE
    }

    /** SHIFT_ARMED is intentionally display-protective, not a confirmed event. */
    public enum ShiftPhase {
        NONE,
        SHIFT_ARMED,
        SHIFT_CONFIRMED
    }

    /** Combustion validity is independent of MainState and ShiftPhase. */
    public enum CombustionState {
        FIRING_VALID,
        SHIFT_FUEL_CUT,
        DFCO_FUEL_CUT,
        OTHER_FUEL_CUT,
        RECOVERY
    }

    public MainState main = MainState.NORMAL;
    public ThermalContext thermal = ThermalContext.UNKNOWN;
    public SubState sub = SubState.NONE;
    public Modifier modifier = Modifier.NONE;
    public ShiftPhase shiftPhase = ShiftPhase.NONE;
    public CombustionState combustion = CombustionState.FIRING_VALID;
    public float confidence = 1.0f;

    public boolean isDfco()    { return main == MainState.DFCO; }
    public boolean isWot()     { return main == MainState.WOT; }
    /** Legacy presentation/readiness helper: thermal warmup, not MainState identity. */
    public boolean isWarmup()  { return thermal != ThermalContext.READY; }
    public boolean isWarmupMainState() { return main == MainState.WARMUP; }
    public boolean isThermallyReady() { return thermal == ThermalContext.READY; }
    public boolean isIdle()    { return main == MainState.IDLE; }
    public boolean isNormal()  { return main == MainState.NORMAL; }
    public boolean hasModifier() { return modifier != Modifier.NONE; }
    public boolean isCoast() { return modifier == Modifier.COAST; }

    public boolean isShiftArmed() { return shiftPhase == ShiftPhase.SHIFT_ARMED; }
    public boolean isShiftConfirmed() { return shiftPhase == ShiftPhase.SHIFT_CONFIRMED; }
    public boolean isShiftActive() { return shiftPhase != ShiftPhase.NONE; }
    public boolean isShift() { return isShiftActive(); }

    public boolean isShiftFuelCut() { return combustion == CombustionState.SHIFT_FUEL_CUT; }
    public boolean isDfcoFuelCut() { return combustion == CombustionState.DFCO_FUEL_CUT; }
    public boolean isOtherFuelCut() { return combustion == CombustionState.OTHER_FUEL_CUT; }
    public boolean isCombustionRecovery() { return combustion == CombustionState.RECOVERY; }
    public boolean isFuelCut() { return isShiftFuelCut() || isDfcoFuelCut() || isOtherFuelCut(); }
    public boolean isCombustionTransient() {
        return isFuelCut() || isCombustionRecovery() || isShiftActive();
    }

    /** WOT responsiveness remains confidence-driven; admission, not heavier EMA, handles invalid frames. */
    public float afAlpha() {
        if (main == MainState.WOT) {
            return 0.3f + 0.4f * confidence;
        }
        return 0.3f;
    }

    /** MAP/boost remains live during shift and release transients. */
    public float boostRelease() {
        if (isShift()) return 0.55f;
        if (modifier == Modifier.TIP_OUT) return 0.40f;
        if (modifier == Modifier.BOOST_SURGE) return 0.30f;
        if (isDfco()) return 0.28f;
        if (isCoast()) return 0.22f;
        return 0.15f;
    }

    public float textAlpha(boolean isSensitiveCard) {
        if (!isSensitiveCard) return 1.0f;
        return 0.45f + 0.55f * confidence;
    }
}
