package io.github.asteroidb612zs.hondatadash.data;

/**
 * RC7 bounded ephemeral diagnostic memory.
 *
 * Purpose: retain only a few seconds of the data/semantic chain that produced
 * the current instrument decision. It is RAM-only, fixed-size, allocation-free
 * on the frame path, performs no file I/O and is cleared when the engine session
 * ends. This is intentionally not a replacement for a Hondata LOG recorder.
 */
public final class EphemeralDiagnosticMemory {
    public static final int CAPACITY = 256;
    public static final long MIN_SAMPLE_MS = 20L; // max 50 Hz -> about 5.1 s retained

    public static final class SessionStats {
        public long acceptedSamples;
        public int shiftArmedEntries;
        public int shiftConfirmedEntries;
        public int dfcoFuelCutEntries;
        public int otherFuelCutEntries;
        public int afHoldEntries;
        public int ignHoldEntries;
        public int strimHoldEntries;
        public int frontGuardSamples;
        public long sessionStartedMs;
        public long lastSampleMs;
    }

    private final long[] t = new long[CAPACITY];
    private final float[] rpm = new float[CAPACITY];
    private final float[] speed = new float[CAPACITY];
    private final float[] gear = new float[CAPACITY];
    private final float[] clutch = new float[CAPACITY];
    private final float[] throttle = new float[CAPACITY];
    private final float[] inj = new float[CAPACITY];
    private final float[] lambda = new float[CAPACITY];
    private final float[] target = new float[CAPACITY];
    private final float[] ign = new float[CAPACITY];
    private final float[] strim = new float[CAPACITY];
    private final byte[] phase = new byte[CAPACITY];
    private final byte[] combustion = new byte[CAPACITY];
    private final byte[] flags = new byte[CAPACITY];

    private final SessionStats stats = new SessionStats();
    private int head;
    private int count;
    private long lastAcceptedMs;
    private EngineSemanticState.ShiftPhase prevPhase = EngineSemanticState.ShiftPhase.NONE;
    private EngineSemanticState.CombustionState prevCombustion = EngineSemanticState.CombustionState.FIRING_VALID;
    private boolean prevHoldAf, prevHoldIgn, prevHoldStrim;

    public void record(EngineSemanticState state, SensorData data,
            CombustionDisplayAdmission.Snapshot admission, long now) {
        if (state == null || data == null || admission == null) return;
        if (lastAcceptedMs > 0L && now - lastAcceptedMs < MIN_SAMPLE_MS) return;
        lastAcceptedMs = now;
        if (stats.sessionStartedMs == 0L) stats.sessionStartedMs = now;
        stats.lastSampleMs = now;
        stats.acceptedSamples++;

        if (prevPhase != EngineSemanticState.ShiftPhase.SHIFT_ARMED
                && state.shiftPhase == EngineSemanticState.ShiftPhase.SHIFT_ARMED) stats.shiftArmedEntries++;
        if (prevPhase != EngineSemanticState.ShiftPhase.SHIFT_CONFIRMED
                && state.shiftPhase == EngineSemanticState.ShiftPhase.SHIFT_CONFIRMED) stats.shiftConfirmedEntries++;
        if (prevCombustion != EngineSemanticState.CombustionState.DFCO_FUEL_CUT
                && state.combustion == EngineSemanticState.CombustionState.DFCO_FUEL_CUT) stats.dfcoFuelCutEntries++;
        if (prevCombustion != EngineSemanticState.CombustionState.OTHER_FUEL_CUT
                && state.combustion == EngineSemanticState.CombustionState.OTHER_FUEL_CUT) stats.otherFuelCutEntries++;
        if (!prevHoldAf && admission.holdAf) stats.afHoldEntries++;
        if (!prevHoldIgn && admission.holdIgn) stats.ignHoldEntries++;
        if (!prevHoldStrim && admission.holdStrim) stats.strimHoldEntries++;
        if (admission.frontGuard) stats.frontGuardSamples++;

        int p = head;
        t[p] = now;
        rpm[p] = finite(data.getDouble(HondataProtocol.CID_RPM));
        speed[p] = finite(data.getDouble(HondataProtocol.CID_Speed));
        gear[p] = finite(data.getDouble(HondataProtocol.CID_Gear));
        clutch[p] = finite(data.getDouble(HondataProtocol.CID_ClutchPos));
        throttle[p] = finite(data.getDouble(HondataProtocol.CID_ThrottlePlate));
        inj[p] = finite(data.getDouble(HondataProtocol.CID_Inj));
        lambda[p] = finite(data.getDouble(HondataProtocol.CID_Lambda));
        target[p] = finite(data.getDouble(HondataProtocol.CID_TargetLambda));
        ign[p] = finite(data.getDouble(HondataProtocol.CID_Ign));
        strim[p] = finite(data.getDouble(HondataProtocol.CID_STrim));
        phase[p] = (byte) state.shiftPhase.ordinal();
        combustion[p] = (byte) state.combustion.ordinal();
        int f = (admission.holdAf ? 1 : 0) | (admission.holdIgn ? 2 : 0)
                | (admission.holdStrim ? 4 : 0) | (admission.frontGuard ? 8 : 0);
        flags[p] = (byte) f;
        head = (p + 1) % CAPACITY;
        if (count < CAPACITY) count++;

        prevPhase = state.shiftPhase;
        prevCombustion = state.combustion;
        prevHoldAf = admission.holdAf;
        prevHoldIgn = admission.holdIgn;
        prevHoldStrim = admission.holdStrim;
    }

    public int retainedSamples() { return count; }
    public SessionStats stats() { return stats; }

    /** Complete purge: called at engine-session end, long reconnect reset and destruction. */
    public void clear() {
        head = 0;
        count = 0;
        lastAcceptedMs = 0L;
        prevPhase = EngineSemanticState.ShiftPhase.NONE;
        prevCombustion = EngineSemanticState.CombustionState.FIRING_VALID;
        prevHoldAf = prevHoldIgn = prevHoldStrim = false;
        stats.acceptedSamples = 0L;
        stats.shiftArmedEntries = 0;
        stats.shiftConfirmedEntries = 0;
        stats.dfcoFuelCutEntries = 0;
        stats.otherFuelCutEntries = 0;
        stats.afHoldEntries = 0;
        stats.ignHoldEntries = 0;
        stats.strimHoldEntries = 0;
        stats.frontGuardSamples = 0;
        stats.sessionStartedMs = 0L;
        stats.lastSampleMs = 0L;
        // Primitive arrays are deliberately not zero-filled: count=0 makes every
        // previous cell unreachable and avoids an O(CAPACITY) stop-time sweep.
    }

    private static float finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? Float.NaN : (float) v;
    }
}
