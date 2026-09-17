package io.github.asteroidb612zs.hondatadash.data;

/**
 * V2 display-admission gate for combustion-sensitive values.
 *
 * Raw telemetry remains untouched. A/F, IGN and S.TRIM are admitted to the
 * visible/history layer only when the current engine semantics and each PID's
 * own recovery behaviour make the value useful to the driver. MAP/RPM never
 * pass through this gate and remain live.
 */
public final class CombustionDisplayAdmission {

    public static final class Snapshot {
        public boolean holdAf;
        public boolean holdIgn;
        public boolean holdStrim;
        public boolean releasedAf;
        public boolean releasedIgn;
        public boolean releasedStrim;
        /** True only for the short display-only pre-fuel-cut TIP_OUT guard. */
        public boolean frontGuard;

        private void set(boolean holdAf, boolean holdIgn, boolean holdStrim,
                boolean releasedAf, boolean releasedIgn, boolean releasedStrim,
                boolean frontGuard) {
            this.holdAf = holdAf;
            this.holdIgn = holdIgn;
            this.holdStrim = holdStrim;
            this.releasedAf = releasedAf;
            this.releasedIgn = releasedIgn;
            this.releasedStrim = releasedStrim;
            this.frontGuard = frontGuard;
        }

        public boolean holdsCard(int cardIndex) {
            if (cardIndex == 5) return holdAf;
            if (cardIndex == 6) return holdIgn;
            if (cardIndex == 7) return holdStrim;
            return false;
        }

        public boolean releasedCard(int cardIndex) {
            if (cardIndex == 5) return releasedAf;
            if (cardIndex == 6) return releasedIgn;
            if (cardIndex == 7) return releasedStrim;
            return false;
        }
    }

    private static final long IGN_STABLE_MS = 180L;
    private static final long AF_STABLE_MS = 200L;
    private static final long STRIM_STABLE_MS = 400L;

    // 14.26 h historical replay: physical lambda recovery to |lambda-target|<=.03
    // stable for 200 ms is ~765 ms median and roughly 1.3-1.45 s near P90.
    // The cap therefore protects ordinary sensor propagation but guarantees that
    // a genuinely persistent post-cut lean/rich condition cannot be hidden forever.
    private static final long AF_MAX_RECOVERY_HOLD_MS = 1400L;
    private static final long STRIM_MAX_RECOVERY_HOLD_MS = 1600L;
    private static final float AF_RECOVER_ERR_MAX = 0.06f;

    private static final float TIP_OUT_TP_MAX = 5f;
    private static final float TIP_OUT_MIN_SPEED = 5f;
    private static final float TIP_OUT_MIN_RPM = 900f;

    // Reused on every frame; no per-frame allocation on API 17 head units.
    private final Snapshot snapshot = new Snapshot();
    private boolean holdAf = false;
    private boolean holdIgn = false;
    private boolean holdStrim = false;
    private long afRecoveryStarted = 0L;
    private long strimRecoveryStarted = 0L;
    private long afReadySince = 0L;
    private long ignReadySince = 0L;
    private long strimReadySince = 0L;
    private boolean guardActive = false;

    /** Used after stale/reconnect: an old cached number must not be declared live. */
    public void requireReacquire(long now) {
        holdAf = true;
        holdIgn = true;
        holdStrim = true;
        afRecoveryStarted = now;
        strimRecoveryStarted = now;
        afReadySince = 0L;
        ignReadySince = 0L;
        strimReadySince = 0L;
        guardActive = false;
    }

    public void reset() {
        holdAf = false;
        holdIgn = false;
        holdStrim = false;
        afRecoveryStarted = 0L;
        strimRecoveryStarted = 0L;
        afReadySince = 0L;
        ignReadySince = 0L;
        strimReadySince = 0L;
        guardActive = false;
    }

    public Snapshot update(EngineSemanticState state, SensorData data, long now) {
        boolean wasAf = holdAf;
        boolean wasIgn = holdIgn;
        boolean wasStrim = holdStrim;

        boolean hardGuard = state != null && (state.isShiftActive() || state.isFuelCut());
        boolean frontGuard = !hardGuard && isTipOutFrontGuard(state, data);
        boolean anyGuard = hardGuard || frontGuard;

        if (anyGuard) {
            holdAf = true;
            holdIgn = true;
            holdStrim = true;
            // Post-event recovery clocks begin only after the semantic/front guard
            // ends. A long clutch hold or long overrun can never consume the cap.
            afRecoveryStarted = 0L;
            strimRecoveryStarted = 0L;
            afReadySince = 0L;
            ignReadySince = 0L;
            strimReadySince = 0L;
            guardActive = true;
        } else {
            if (guardActive) {
                afRecoveryStarted = now;
                strimRecoveryStarted = now;
                guardActive = false;
            }
            updateIgn(data, now);
            updateAf(data, now);
            updateStrim(state, data, now);
        }

        snapshot.set(holdAf, holdIgn, holdStrim,
                wasAf && !holdAf, wasIgn && !holdIgn, wasStrim && !holdStrim,
                frontGuard);
        return snapshot;
    }

    private boolean isTipOutFrontGuard(EngineSemanticState state, SensorData data) {
        if (state == null || state.modifier != EngineSemanticState.Modifier.TIP_OUT) return false;
        float tp = finite(data.getDouble(HondataProtocol.CID_ThrottlePlate));
        float speed = finite(data.getDouble(HondataProtocol.CID_Speed));
        float rpm = finite(data.getDouble(HondataProtocol.CID_RPM));
        return !Float.isNaN(tp) && !Float.isNaN(speed) && !Float.isNaN(rpm)
                && tp <= TIP_OUT_TP_MAX && speed >= TIP_OUT_MIN_SPEED && rpm >= TIP_OUT_MIN_RPM;
    }

    private void updateIgn(SensorData data, long now) {
        if (!holdIgn) return;
        float inj = finite(data.getDouble(HondataProtocol.CID_Inj));
        boolean ready = !Float.isNaN(inj) && inj > 0.20f;
        if (ready) {
            if (ignReadySince == 0L) ignReadySince = now;
            if (now - ignReadySince >= IGN_STABLE_MS) holdIgn = false;
        } else {
            ignReadySince = 0L;
        }
    }

    private void updateAf(SensorData data, long now) {
        if (!holdAf) return;
        float inj = finite(data.getDouble(HondataProtocol.CID_Inj));
        float lambda = finite(data.getDouble(HondataProtocol.CID_Lambda));
        float target = finite(data.getDouble(HondataProtocol.CID_TargetLambda));
        boolean injectorActive = !Float.isNaN(inj) && inj > 0.20f;
        boolean targetValid = !Float.isNaN(target) && target > 0.55f && target < 1.30f;
        boolean measuredValid = !Float.isNaN(lambda) && lambda > 0.50f && lambda < 1.60f;
        boolean targetRelativeStable = measuredValid && targetValid
                && Math.abs(lambda - target) <= AF_RECOVER_ERR_MAX;
        boolean ready = injectorActive && targetRelativeStable;
        if (afRecoveryStarted == 0L) afRecoveryStarted = now;

        if (ready) {
            if (afReadySince == 0L) afReadySince = now;
            if (now - afReadySince >= AF_STABLE_MS) holdAf = false;
        } else {
            afReadySince = 0L;
        }

        // Never hide a real condition indefinitely. Once injection and ECU target
        // are valid, persistent target-relative error is exposed after this bounded
        // allowance and may then trigger the alert layer.
        if (holdAf && afRecoveryStarted > 0L
                && now - afRecoveryStarted >= AF_MAX_RECOVERY_HOLD_MS
                && injectorActive && targetValid) {
            holdAf = false;
        }
    }

    private void updateStrim(EngineSemanticState state, SensorData data, long now) {
        if (!holdStrim) return;
        float closedLoop = finite(data.getDouble(HondataProtocol.CID_ClosedLoop));
        float target = finite(data.getDouble(HondataProtocol.CID_TargetLambda));
        float inj = finite(data.getDouble(HondataProtocol.CID_Inj));

        // SHIFT is deliberately absent here: any active shift is already consumed by
        // hardGuard before this method can run. Keeping SHIFT here created a dead path
        // and duplicated shift semantics across layers.
        boolean dynamic = state != null && (state.modifier == EngineSemanticState.Modifier.TIP_OUT
                || state.modifier == EngineSemanticState.Modifier.BOOST_SURGE);
        boolean basicReady = !Float.isNaN(closedLoop) && closedLoop > 0.5f
                && !Float.isNaN(target) && target > 0.90f && target < 1.10f
                && !Float.isNaN(inj) && inj > 0.30f;
        boolean ready = basicReady && !dynamic;
        if (strimRecoveryStarted == 0L) strimRecoveryStarted = now;

        if (ready) {
            if (strimReadySince == 0L) strimReadySince = now;
            if (now - strimReadySince >= STRIM_STABLE_MS) holdStrim = false;
        } else {
            strimReadySince = 0L;
        }

        // S.TRIM is informative but highly dynamic. A bounded cap avoids an
        // indefinitely grey card if the driver keeps accelerating after a shift.
        if (holdStrim && strimRecoveryStarted > 0L
                && now - strimRecoveryStarted >= STRIM_MAX_RECOVERY_HOLD_MS
                && basicReady) {
            holdStrim = false;
        }
    }

    private static float finite(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return Float.NaN;
        return (float) v;
    }
}
