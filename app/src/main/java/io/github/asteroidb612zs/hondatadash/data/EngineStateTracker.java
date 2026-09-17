package io.github.asteroidb612zs.hondatadash.data;

import android.os.SystemClock;

/**
 * RC7 engine semantic tracker.
 *
 * Key change from RC5: shift intent/confirmation is evaluated before DFCO and
 * combustion validity is represented independently. A clutch/gear shift fuel
 * cut therefore cannot be promoted to MainState.DFCO.
 */
public class EngineStateTracker {

    private final EngineSemanticState state = new EngineSemanticState();

    private EngineSemanticState.MainState currentMain = EngineSemanticState.MainState.NORMAL;
    private EngineSemanticState.MainState candidateMain = EngineSemanticState.MainState.NORMAL;
    private long candidateMainSince = 0L;
    private long mainStateSince = 0L;

    private boolean warmupActive = false;
    private long warmupExitCandidateSince = 0L;

    private float lastTp = 0f;
    private float lastRpm = 0f;
    private float lastMap = 0f;
    private float lastGear = Float.NaN;
    private float lastClutch = Float.NaN;
    private long lastTime = 0L;
    private boolean initialized = false;

    // RC6 shift phase state.
    private EngineSemanticState.ShiftPhase currentShiftPhase = EngineSemanticState.ShiftPhase.NONE;
    private long shiftArmedSince = 0L;
    private long shiftConfirmedUntil = 0L;
    private long shiftConfirmedSince = 0L;
    private boolean shiftGearChangeObserved = false;
    private boolean suppressRearmUntilClutchRelease = false;

    // RC6 combustion recovery state.
    private EngineSemanticState.CombustionState previousCombustion = EngineSemanticState.CombustionState.FIRING_VALID;
    private long combustionRecoveryUntil = 0L;
    private EngineSemanticState.ShiftPhase previousShiftPhase = EngineSemanticState.ShiftPhase.NONE;

    private float smoothConfidence = 1.0f;

    private static final long HYSTERESIS_DFCO_ENTER    = 100L;
    private static final long HYSTERESIS_DFCO_EXIT     = 50L;
    private static final long HYSTERESIS_WOT_ENTER     = 30L;
    private static final long HYSTERESIS_WOT_EXIT      = 80L;
    private static final long HYSTERESIS_WARMUP_ENTER  = 500L;
    private static final long HYSTERESIS_WARMUP_EXIT   = 5000L;
    private static final long HYSTERESIS_IDLE_ENTER    = 200L;
    private static final long HYSTERESIS_DEFAULT       = 50L;

    private static final long DFCO_ENTER_MS = 200L;
    private static final float SPOOL_MAP_RATE = 100f;
    private static final long PEAK_DURATION_MS = 2000L;

    private static final float TP_RATE_THRESHOLD  = 50f;
    private static final float RPM_RATE_THRESHOLD = 1200f;
    private static final float MAP_RATE_THRESHOLD = 300f;

    // RC6 SHIFT_ARMED front gate. 4% is above clutch noise but ~65-75 ms earlier
    // than RC5's 18% threshold in the supplied log.
    private static final float SHIFT_ARM_CLUTCH_ON = 4f;
    private static final float SHIFT_ARM_CLUTCH_RESET = 2f;
    private static final float SHIFT_CLUTCH_CONFIRM = 18f;
    private static final float SHIFT_CLUTCH_STRONG = 35f;
    private static final long SHIFT_ARM_TIMEOUT_MS = 600L;
    private static final long SHIFT_CONFIRM_BRIDGE_MS = 420L;
    private static final long SHIFT_CLUTCH_EXTEND_MS = 220L;
    // Historical long logs show 99% of observed gear changes within ~2.13 s and all
    // within 2.48 s of clutch rise. A 3 s semantic ceiling prevents a held clutch
    // from masquerading as an endless SHIFT while preserving genuinely slow shifts.
    private static final long SHIFT_MAX_CONFIRMED_MS = 3000L;
    private static final float SHIFT_CONFIRM_RPM_RATE = 1200f;
    private static final float COAST_TP_MAX = 3f;

    private static final float LAMBDA_WOT_MAX = 0.95f;
    private static final int MAP_WOT_MIN = 120;
    private static final int RPM_WOT_MIN = 1500;
    private static final int RPM_DFCO_MIN = 1400;

    private static final float ECT_WARMUP_ENTER = 65f;
    private static final float ECT_WARMUP_EXIT = 72f;
    private static final float CONFIDENCE_ALPHA = 0.1f;

    private static final float FUEL_CUT_INJ_MAX = 0.05f;
    private static final long COMBUSTION_RECOVERY_MS = 850L;
    private static final long SHIFT_ONLY_RECOVERY_MS = 500L;

    public EngineSemanticState update(SensorData data) {
        float rpm          = (float) data.getDouble(HondataProtocol.CID_RPM);
        float tp           = (float) data.getDouble(HondataProtocol.CID_ThrottlePlate);
        float inj          = (float) data.getDouble(HondataProtocol.CID_Inj);
        float speed        = (float) data.getDouble(HondataProtocol.CID_Speed);
        float mapVal       = (float) data.getDouble(HondataProtocol.CID_MAP);
        float closedLoop   = (float) data.getDouble(HondataProtocol.CID_ClosedLoop);
        float targetLambda = (float) data.getDouble(HondataProtocol.CID_TargetLambda);
        float measuredLambda = (float) data.getDouble(HondataProtocol.CID_Lambda);
        float ect          = (float) data.getDouble(HondataProtocol.CID_ECT);

        double gearRaw = data.getDouble(HondataProtocol.CID_Gear);
        double clutchRaw = data.getDouble(HondataProtocol.CID_ClutchPos);
        float gear = (!Double.isNaN(gearRaw) && gearRaw >= 1 && gearRaw <= 8) ? (float) gearRaw : Float.NaN;
        float clutch = (!Double.isNaN(clutchRaw) && clutchRaw >= 0 && clutchRaw <= 100) ? (float) clutchRaw : Float.NaN;

        long now = SystemClock.elapsedRealtime();

        if (!initialized) {
            lastTime = now;
            lastTp = tp;
            lastRpm = rpm;
            lastMap = mapVal;
            lastGear = gear;
            lastClutch = clutch;
            initialized = true;
            warmupActive = (ect < ECT_WARMUP_ENTER);
            resetStateOutputs();
            return state;
        }

        float dt = (now - lastTime) / 1000f;
        float tpRate = dt > 0.001f ? (tp - lastTp) / dt : 0f;
        float rpmRate = dt > 0.001f ? (rpm - lastRpm) / dt : 0f;
        float mapRate = dt > 0.001f ? (mapVal - lastMap) / dt : 0f;

        // 1) Shift phase must be known before fuel-cut classification.
        currentShiftPhase = updateShiftPhase(speed, rpm, tp, inj, gear, clutch, rpmRate, tpRate, now);
        state.shiftPhase = currentShiftPhase;

        // 2) Combustion validity (orthogonal to MainState).
        EngineSemanticState.CombustionState combustion = classifyCombustion(
                speed, rpm, tp, inj, targetLambda, measuredLambda, closedLoop, currentShiftPhase, now);
        state.combustion = combustion;

        // 3) MainState. DFCO is admitted only for DFCO_FUEL_CUT, never SHIFT_FUEL_CUT.
        EngineSemanticState.MainState desired;
        if (combustion == EngineSemanticState.CombustionState.DFCO_FUEL_CUT) {
            desired = EngineSemanticState.MainState.DFCO;
        } else if (currentMain == EngineSemanticState.MainState.WOT
                && currentShiftPhase != EngineSemanticState.ShiftPhase.NONE
                && (combustion == EngineSemanticState.CombustionState.SHIFT_FUEL_CUT
                    || combustion == EngineSemanticState.CombustionState.RECOVERY)) {
            // Preserve ECU strategy context across a WOT shift rather than WOT->DFCO->NORMAL.
            desired = EngineSemanticState.MainState.WOT;
        } else if (closedLoop < 0.5f && targetLambda < LAMBDA_WOT_MAX
                && rpm > RPM_WOT_MIN && mapVal > MAP_WOT_MIN) {
            desired = EngineSemanticState.MainState.WOT;
        } else if (detectWarmup(ect, closedLoop, mapVal, tp, rpm, speed, now)) {
            desired = EngineSemanticState.MainState.WARMUP;
        } else if (rpm < 1000 && tp < 2 && speed < 3) {
            desired = EngineSemanticState.MainState.IDLE;
        } else {
            desired = EngineSemanticState.MainState.NORMAL;
        }

        // A shift owns fuel-cut semantics immediately. Do not leave a one-frame DFCO
        // residue behind the ordinary exit hysteresis when the clutch/gear evidence says
        // this is SHIFT_FUEL_CUT. (The supplied 50 Hz replay exposed exactly this edge.)
        if (currentShiftPhase != EngineSemanticState.ShiftPhase.NONE
                && combustion != EngineSemanticState.CombustionState.DFCO_FUEL_CUT
                && currentMain == EngineSemanticState.MainState.DFCO) {
            currentMain = desired;
            candidateMain = desired;
            candidateMainSince = now;
            mainStateSince = now;
        } else {
            if (desired != candidateMain) {
                candidateMain = desired;
                candidateMainSince = now;
            }
            long sustainMs = now - candidateMainSince;
            long threshold = getMainThreshold(currentMain, candidateMain);
            if (sustainMs >= threshold) {
                if (currentMain != candidateMain) mainStateSince = now;
                currentMain = candidateMain;
            }
        }
        state.main = currentMain;
        state.sub = detectSubState(dt, mapVal, now);

        // 4) Modifier. SHIFT wins over rate-derived modifiers, but does not alter MAP/RPM truth.
        if (currentShiftPhase != EngineSemanticState.ShiftPhase.NONE) {
            state.modifier = EngineSemanticState.Modifier.SHIFT;
        } else if (dt > 0.001f) {
            state.modifier = detectNonShiftModifier(tpRate, rpmRate, mapRate, tp, speed, inj, rpm);
        } else {
            state.modifier = EngineSemanticState.Modifier.NONE;
        }

        // 5) Confidence.
        boolean criticalPidMissing = Double.isNaN(data.getDouble(HondataProtocol.CID_RPM))
                || Double.isNaN(data.getDouble(HondataProtocol.CID_ThrottlePlate))
                || Double.isNaN(data.getDouble(HondataProtocol.CID_MAP))
                || Double.isNaN(data.getDouble(HondataProtocol.CID_Speed))
                || Double.isNaN(data.getDouble(HondataProtocol.CID_Inj));
        float instant = criticalPidMissing ? 0f : computeConfidence(currentMain, data);
        smoothConfidence += CONFIDENCE_ALPHA * (instant - smoothConfidence);
        state.confidence = smoothConfidence;

        lastTime = now;
        lastTp = tp;
        lastRpm = rpm;
        lastMap = mapVal;
        if (!Float.isNaN(gear)) lastGear = gear;
        if (!Float.isNaN(clutch)) lastClutch = clutch;
        previousShiftPhase = currentShiftPhase;
        previousCombustion = combustion;
        return state;
    }

    private void resetStateOutputs() {
        state.main = EngineSemanticState.MainState.NORMAL;
        state.sub = EngineSemanticState.SubState.NONE;
        state.modifier = EngineSemanticState.Modifier.NONE;
        state.shiftPhase = EngineSemanticState.ShiftPhase.NONE;
        state.combustion = EngineSemanticState.CombustionState.FIRING_VALID;
        state.confidence = 1.0f;
    }

    public EngineSemanticState getState() { return state; }

    public void reset() {
        initialized = false;
        currentMain = EngineSemanticState.MainState.NORMAL;
        candidateMain = EngineSemanticState.MainState.NORMAL;
        candidateMainSince = 0L;
        mainStateSince = 0L;
        warmupActive = false;
        warmupExitCandidateSince = 0L;
        lastTime = 0L;
        lastTp = 0f;
        lastRpm = 0f;
        lastMap = 0f;
        lastGear = Float.NaN;
        lastClutch = Float.NaN;
        currentShiftPhase = EngineSemanticState.ShiftPhase.NONE;
        previousShiftPhase = EngineSemanticState.ShiftPhase.NONE;
        shiftArmedSince = 0L;
        shiftConfirmedUntil = 0L;
        shiftConfirmedSince = 0L;
        shiftGearChangeObserved = false;
        suppressRearmUntilClutchRelease = false;
        previousCombustion = EngineSemanticState.CombustionState.FIRING_VALID;
        combustionRecoveryUntil = 0L;
        smoothConfidence = 1.0f;
        resetStateOutputs();
    }

    private EngineSemanticState.ShiftPhase updateShiftPhase(float speed, float rpm, float tp, float inj,
            float gear, float clutch, float rpmRate, float tpRate, long now) {
        boolean gearAvailable = !Float.isNaN(gear);
        boolean clutchAvailable = !Float.isNaN(clutch);
        boolean roadShiftContext = speed > 5f && rpm > 900f;
        boolean gearChanged = gearAvailable && !Float.isNaN(lastGear) && Math.abs(gear - lastGear) >= 0.5f;
        boolean clutchAboveArm = clutchAvailable && clutch >= SHIFT_ARM_CLUTCH_ON;
        boolean clutchConfirmed = clutchAvailable && clutch >= SHIFT_CLUTCH_CONFIRM;
        boolean strongClutch = clutchAvailable && clutch >= SHIFT_CLUTCH_STRONG;
        boolean clutchRisingEdge = clutchAvailable
                && (Float.isNaN(lastClutch) || lastClutch < SHIFT_ARM_CLUTCH_ON)
                && clutch >= SHIFT_ARM_CLUTCH_ON;
        boolean fuelCut = !Float.isNaN(inj) && inj <= FUEL_CUT_INJ_MAX;

        if (clutchAvailable && clutch <= SHIFT_ARM_CLUTCH_RESET) {
            suppressRearmUntilClutchRelease = false;
        }

        if (currentShiftPhase == EngineSemanticState.ShiftPhase.NONE
                && roadShiftContext && clutchRisingEdge && !suppressRearmUntilClutchRelease) {
            currentShiftPhase = EngineSemanticState.ShiftPhase.SHIFT_ARMED;
            shiftArmedSince = now;
            shiftConfirmedSince = 0L;
            shiftConfirmedUntil = 0L;
            shiftGearChangeObserved = false;
        }

        // Confirmation supports both upshifts and rev-matched downshifts: rpmRate sign is not required.
        boolean trajectoryEvidence = strongClutch && Math.abs(rpmRate) >= SHIFT_CONFIRM_RPM_RATE;
        boolean fuelCutEvidence = clutchConfirmed && fuelCut;
        boolean gearEvidence = gearChanged && (clutchAboveArm || currentShiftPhase != EngineSemanticState.ShiftPhase.NONE);
        boolean legacyFallback = roadShiftContext && (!gearAvailable || !clutchAvailable)
                && rpmRate < -RPM_RATE_THRESHOLD && tpRate < -20f;

        boolean confirmEvidence = roadShiftContext
                && (gearEvidence || fuelCutEvidence || trajectoryEvidence || legacyFallback);
        if (confirmEvidence && currentShiftPhase != EngineSemanticState.ShiftPhase.SHIFT_CONFIRMED) {
            currentShiftPhase = EngineSemanticState.ShiftPhase.SHIFT_CONFIRMED;
            shiftConfirmedSince = now;
            shiftConfirmedUntil = now + SHIFT_CONFIRM_BRIDGE_MS;
            shiftGearChangeObserved = gearChanged;
            shiftArmedSince = 0L;
        }

        if (currentShiftPhase == EngineSemanticState.ShiftPhase.SHIFT_CONFIRMED) {
            if (gearChanged) {
                shiftGearChangeObserved = true;
                shiftConfirmedUntil = Math.max(shiftConfirmedUntil, now + SHIFT_CONFIRM_BRIDGE_MS);
            }

            // Before the gear transition is observed, a physically engaged clutch may
            // legitimately need more than the initial bridge. Once Gear has changed,
            // clutch hold alone no longer means the transmission is still shifting.
            if (!shiftGearChangeObserved && clutchConfirmed
                    && now - shiftConfirmedSince < SHIFT_MAX_CONFIRMED_MS) {
                shiftConfirmedUntil = Math.max(shiftConfirmedUntil, now + SHIFT_CLUTCH_EXTEND_MS);
            }

            boolean bridgeComplete = now >= shiftConfirmedUntil;
            boolean clutchReleased = !clutchAvailable || clutch < SHIFT_ARM_CLUTCH_ON;
            boolean semanticTimeout = shiftConfirmedSince > 0L
                    && now - shiftConfirmedSince >= SHIFT_MAX_CONFIRMED_MS;
            boolean completedWithGear = shiftGearChangeObserved && bridgeComplete;

            if ((bridgeComplete && clutchReleased) || completedWithGear || semanticTimeout || !roadShiftContext) {
                if (clutchAvailable && clutch > SHIFT_ARM_CLUTCH_RESET) {
                    suppressRearmUntilClutchRelease = true;
                }
                currentShiftPhase = EngineSemanticState.ShiftPhase.NONE;
                shiftConfirmedSince = 0L;
                shiftConfirmedUntil = 0L;
                shiftGearChangeObserved = false;
            }
        } else if (currentShiftPhase == EngineSemanticState.ShiftPhase.SHIFT_ARMED) {
            boolean armReleased = clutchAvailable && clutch <= SHIFT_ARM_CLUTCH_RESET;
            boolean armTimedOut = now - shiftArmedSince >= SHIFT_ARM_TIMEOUT_MS;
            if (armReleased || armTimedOut || !roadShiftContext) {
                currentShiftPhase = EngineSemanticState.ShiftPhase.NONE;
                if (armTimedOut && clutchAvailable && clutch > SHIFT_ARM_CLUTCH_RESET) {
                    suppressRearmUntilClutchRelease = true;
                }
                shiftArmedSince = 0L;
            }
        }

        return currentShiftPhase;
    }

    private EngineSemanticState.CombustionState classifyCombustion(float speed, float rpm, float tp,
            float inj, float targetLambda, float measuredLambda, float closedLoop,
            EngineSemanticState.ShiftPhase shiftPhase, long now) {
        boolean fuelCut = !Float.isNaN(inj) && inj <= FUEL_CUT_INJ_MAX;
        boolean lowThrottle = !Float.isNaN(tp) && tp < 3f;
        boolean dfcoRoadContext = speed > 15f && rpm > RPM_DFCO_MIN;

        EngineSemanticState.CombustionState direct;
        if (fuelCut && shiftPhase != EngineSemanticState.ShiftPhase.NONE) {
            direct = EngineSemanticState.CombustionState.SHIFT_FUEL_CUT;
        } else if (fuelCut && lowThrottle && dfcoRoadContext) {
            // Once injection is actually off in a conventional overrun context,
            // A/F/IGN/trim are combustion-invalid immediately. Do not wait for the
            // wideband or target lambda to drift toward free air.
            direct = EngineSemanticState.CombustionState.DFCO_FUEL_CUT;
        } else if (fuelCut) {
            // Neutral coast, limiter/torque intervention and other non-firing
            // conditions are invalid for combustion-derived displays even when
            // they are not semantically DFCO. This prevents real free-air lambda
            // from being misrepresented as a current combustion AFR.
            direct = EngineSemanticState.CombustionState.OTHER_FUEL_CUT;
        } else {
            direct = EngineSemanticState.CombustionState.FIRING_VALID;
        }

        boolean previousWasFuelCut = previousCombustion == EngineSemanticState.CombustionState.SHIFT_FUEL_CUT
                || previousCombustion == EngineSemanticState.CombustionState.DFCO_FUEL_CUT
                || previousCombustion == EngineSemanticState.CombustionState.OTHER_FUEL_CUT;
        boolean leavingFuelCut = previousWasFuelCut
                && direct == EngineSemanticState.CombustionState.FIRING_VALID;
        if (leavingFuelCut) {
            combustionRecoveryUntil = Math.max(combustionRecoveryUntil, now + COMBUSTION_RECOVERY_MS);
        }
        boolean leavingConfirmedShift = previousShiftPhase == EngineSemanticState.ShiftPhase.SHIFT_CONFIRMED
                && shiftPhase == EngineSemanticState.ShiftPhase.NONE;
        if (leavingConfirmedShift) {
            combustionRecoveryUntil = Math.max(combustionRecoveryUntil, now + SHIFT_ONLY_RECOVERY_MS);
        }

        if (direct != EngineSemanticState.CombustionState.FIRING_VALID) return direct;
        if (now < combustionRecoveryUntil) return EngineSemanticState.CombustionState.RECOVERY;
        return EngineSemanticState.CombustionState.FIRING_VALID;
    }

    private boolean detectWarmup(float ect, float closedLoop, float mapVal,
            float tp, float rpm, float speed, long now) {
        boolean exitCondition = (ect > ECT_WARMUP_EXIT);
        if (warmupActive) {
            if (exitCondition) {
                if (warmupExitCandidateSince == 0L) warmupExitCandidateSince = now;
                if (now - warmupExitCandidateSince >= HYSTERESIS_WARMUP_EXIT) {
                    warmupActive = false;
                    warmupExitCandidateSince = 0L;
                    return false;
                }
            } else {
                warmupExitCandidateSince = 0L;
            }
            return true;
        }
        if (ect < ECT_WARMUP_ENTER && closedLoop < 0.5f
                && mapVal < 100 && tp < 5 && rpm > 800 && rpm < 1800 && speed < 5) {
            return true;
        }
        return false;
    }

    private EngineSemanticState.SubState detectSubState(float dt, float mapVal, long now) {
        long duration = now - mainStateSince;
        if (currentMain == EngineSemanticState.MainState.WOT) {
            if (dt > 0.001f && (mapVal - lastMap) / dt > SPOOL_MAP_RATE) {
                return EngineSemanticState.SubState.SPOOL;
            }
            if (duration < PEAK_DURATION_MS) return EngineSemanticState.SubState.PEAK;
            return EngineSemanticState.SubState.HOLD;
        }
        if (currentMain == EngineSemanticState.MainState.DFCO) {
            if (duration < DFCO_ENTER_MS) return EngineSemanticState.SubState.DFCO_ENTER;
            return EngineSemanticState.SubState.DFCO_HOLD;
        }
        return EngineSemanticState.SubState.NONE;
    }

    private EngineSemanticState.Modifier detectNonShiftModifier(float tpRate, float rpmRate, float mapRate,
            float tp, float speed, float inj, float rpm) {
        // Driver intent wins over the MAP derivative. RC6 checked MAP first, which
        // could mask a real TIP_OUT exactly when the display front guard needed it.
        if (tpRate < -TP_RATE_THRESHOLD) return EngineSemanticState.Modifier.TIP_OUT;
        if (tpRate > TP_RATE_THRESHOLD) return EngineSemanticState.Modifier.TIP_IN;
        if (Math.abs(mapRate) > MAP_RATE_THRESHOLD) return EngineSemanticState.Modifier.BOOST_SURGE;
        if (currentMain != EngineSemanticState.MainState.DFCO && speed > 15f
                && rpm > 1000f && tp < COAST_TP_MAX && inj >= 0.5f) {
            return EngineSemanticState.Modifier.COAST;
        }
        return EngineSemanticState.Modifier.NONE;
    }

    private float computeConfidence(EngineSemanticState.MainState main, SensorData data) {
        switch (main) {
            case DFCO: return dfcoConfidence(data);
            case WOT: return wotConfidence(data);
            case WARMUP: return warmupConfidence(data);
            case IDLE: return idleConfidence(data);
            default: return 1.0f;
        }
    }

    private float wotConfidence(SensorData data) {
        float cl = (float) data.getDouble(HondataProtocol.CID_ClosedLoop);
        float lambda = (float) data.getDouble(HondataProtocol.CID_TargetLambda);
        float map = (float) data.getDouble(HondataProtocol.CID_MAP);
        float rpm = (float) data.getDouble(HondataProtocol.CID_RPM);
        float score = 0f;
        score += (cl < 0.5f) ? 0.35f : 0f;
        score += 0.25f * clamp01((0.95f - lambda) / 0.15f);
        score += 0.25f * clamp01((map - 120f) / 80f);
        score += (rpm > 1500) ? 0.15f : 0f;
        return score;
    }

    private float dfcoConfidence(SensorData data) {
        float tp = (float) data.getDouble(HondataProtocol.CID_ThrottlePlate);
        float speed = (float) data.getDouble(HondataProtocol.CID_Speed);
        float inj = (float) data.getDouble(HondataProtocol.CID_Inj);
        float rpm = (float) data.getDouble(HondataProtocol.CID_RPM);
        float score = 0f;
        score += (tp < 2) ? 0.20f : 0f;
        score += (speed > 15) ? 0.20f : 0f;
        score += 0.35f * clamp01((0.5f - inj) / 0.5f);
        score += (rpm > 1400) ? 0.15f : 0f;
        return score;
    }

    private float warmupConfidence(SensorData data) {
        float ect = (float) data.getDouble(HondataProtocol.CID_ECT);
        float cl = (float) data.getDouble(HondataProtocol.CID_ClosedLoop);
        float map = (float) data.getDouble(HondataProtocol.CID_MAP);
        float score = 0f;
        score += (cl < 0.5f) ? 0.25f : 0f;
        score += 0.30f * clamp01((65f - ect) / 35f);
        score += (map < 100) ? 0.15f : 0f;
        score += 0.15f;
        return score;
    }

    private float idleConfidence(SensorData data) {
        float rpm = (float) data.getDouble(HondataProtocol.CID_RPM);
        float tp = (float) data.getDouble(HondataProtocol.CID_ThrottlePlate);
        float speed = (float) data.getDouble(HondataProtocol.CID_Speed);
        float score = 0f;
        score += 0.30f * clamp01((1000f - rpm) / 300f);
        score += (tp < 2) ? 0.35f : 0f;
        score += (speed < 3) ? 0.35f : 0f;
        return score;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private long getMainThreshold(EngineSemanticState.MainState from, EngineSemanticState.MainState to) {
        if (to == EngineSemanticState.MainState.DFCO) return HYSTERESIS_DFCO_ENTER;
        if (from == EngineSemanticState.MainState.DFCO) return HYSTERESIS_DFCO_EXIT;
        if (to == EngineSemanticState.MainState.WOT) return HYSTERESIS_WOT_ENTER;
        if (from == EngineSemanticState.MainState.WOT) return HYSTERESIS_WOT_EXIT;
        if (to == EngineSemanticState.MainState.WARMUP) return HYSTERESIS_WARMUP_ENTER;
        if (from == EngineSemanticState.MainState.WARMUP) return HYSTERESIS_WARMUP_EXIT;
        if (to == EngineSemanticState.MainState.IDLE) return HYSTERESIS_IDLE_ENTER;
        return HYSTERESIS_DEFAULT;
    }
}
