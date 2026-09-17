package io.github.asteroidb612zs.hondatadash.data;

/**
 * V2.0.1-internal.1 persistent fuel-pressure alert gate.
 *
 * UNKNOWN/UNOBSERVABLE is not interpreted as HEALTHY: a short SHIFT/fuel-cut/recovery
 * interval pauses low-pressure evidence instead of clearing it. However, evidence is
 * also not allowed to bridge an arbitrarily long unobservable gap, because two low
 * samples separated by minutes no longer establish one continuous fault episode.
 */
public final class FuelPressureAlertTracker {
    private static final long LOW_PERSIST_MS = 300L;
    private static final long LOW_DEMAND_PERSIST_MS = 800L;
    private static final double HIGH_DEMAND_TARGET_KPA = 8000.0;

    /**
     * Core14 calibration provenance (2,566,979 normalized frames / 14.260954 h):
     * 24 low-evidence pause episodes, P50 0.98 s, P90 2.18 s, observed max 8.56 s.
     * V2 SHIFT can remain active up to ~3.02 s and combustion recovery is <=0.85 s.
     * An 8 s TTL is therefore >2x a complete normal shift/recovery chain, preserves
     * all ordinary short pauses, but expires the single stale >8 s Core14 bridge.
     * Candidate replay: 5 s expired 2 episodes; 8 s expired 1; 10 s expired 0;
     * all candidates retained zero Core14 false alerts.
     */
    private static final long EVIDENCE_MAX_PAUSE_MS = 8000L;

    private long lowObservedMs = 0L;
    private long lastObservableLowMs = 0L;
    private long evidencePausedSinceMs = 0L;
    private boolean lowEvidenceActive = false;

    public boolean update(boolean engineRunningStable, EngineSemanticState state,
            double actualKpa, double targetKpa, long now) {
        if (!engineRunningStable || Double.isNaN(actualKpa) || Double.isInfinite(actualKpa)) {
            reset();
            return false;
        }
        if (state != null && (state.isShiftActive()
                || state.combustion != EngineSemanticState.CombustionState.FIRING_VALID)) {
            if (lowEvidenceActive) {
                if (evidencePausedSinceMs == 0L) evidencePausedSinceMs = now;
                if (now - evidencePausedSinceMs >= EVIDENCE_MAX_PAUSE_MS) {
                    // Expiry means "old evidence is stale", not "pressure was healthy".
                    reset();
                    return false;
                }
            }
            lastObservableLowMs = 0L;
            return false;
        }

        evidencePausedSinceMs = 0L;
        boolean low;
        if (!Double.isNaN(targetKpa) && !Double.isInfinite(targetKpa) && targetKpa > 0.0) {
            low = actualKpa < targetKpa * 0.90;
        } else {
            low = actualKpa < 200.0;
        }
        if (!low) {
            reset();
            return false;
        }
        if (!lowEvidenceActive) {
            lowEvidenceActive = true;
            lowObservedMs = 0L;
            lastObservableLowMs = now;
        } else {
            if (lastObservableLowMs > 0L && now > lastObservableLowMs) {
                lowObservedMs += now - lastObservableLowMs;
            }
            lastObservableLowMs = now;
        }
        long persistMs = (!Double.isNaN(targetKpa) && !Double.isInfinite(targetKpa)
                && targetKpa >= HIGH_DEMAND_TARGET_KPA) ? LOW_PERSIST_MS : LOW_DEMAND_PERSIST_MS;
        return lowObservedMs >= persistMs;
    }

    public void reset() {
        lowObservedMs = 0L;
        lastObservableLowMs = 0L;
        evidencePausedSinceMs = 0L;
        lowEvidenceActive = false;
    }
}
