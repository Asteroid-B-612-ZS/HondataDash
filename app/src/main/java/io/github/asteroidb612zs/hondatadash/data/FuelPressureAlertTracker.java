package io.github.asteroidb612zs.hondatadash.data;

/**
 * V2.0.1-internal.1 persistent fuel-pressure alert gate.
 *
 * Key semantic refinement over V2.0:
 * an unobservable interval (SHIFT or non-FIRING_VALID combustion) pauses low-pressure
 * evidence instead of clearing it. UNKNOWN/UNOBSERVABLE is not interpreted as HEALTHY.
 * Only an observable normal-pressure sample clears accumulated low-pressure evidence.
 */
public final class FuelPressureAlertTracker {
    private static final long LOW_PERSIST_MS = 300L;
    private static final long LOW_DEMAND_PERSIST_MS = 800L;
    private static final double HIGH_DEMAND_TARGET_KPA = 8000.0;

    /** Accumulated observable time for which pressure was continuously low. */
    private long lowObservedMs = 0L;
    /** Last timestamp at which a low-pressure sample was observable. Zero means paused/reacquire. */
    private long lastObservableLowMs = 0L;
    private boolean lowEvidenceActive = false;

    public boolean update(boolean engineRunningStable, EngineSemanticState state,
            double actualKpa, double targetKpa, long now) {
        if (!engineRunningStable || Double.isNaN(actualKpa) || Double.isInfinite(actualKpa)) {
            reset();
            return false;
        }

        // Semantic transients are temporarily unobservable for fault persistence.
        // Pause evidence; do not reinterpret the interval as a healthy pressure sample.
        if (state != null && (state.isShiftActive()
                || state.combustion != EngineSemanticState.CombustionState.FIRING_VALID)) {
            lastObservableLowMs = 0L;
            return false;
        }

        boolean low;
        if (!Double.isNaN(targetKpa) && !Double.isInfinite(targetKpa) && targetKpa > 0.0) {
            low = actualKpa < targetKpa * 0.90;
        } else {
            low = actualKpa < 200.0;
        }

        // An observable healthy sample is explicit evidence that the low-pressure
        // episode ended, so only this path clears previously accumulated evidence.
        if (!low) {
            reset();
            return false;
        }

        if (!lowEvidenceActive) {
            lowEvidenceActive = true;
            lowObservedMs = 0L;
            lastObservableLowMs = now;
        } else {
            // A zero timestamp means we are resuming after an unobservable interval.
            // The hidden interval itself must not count toward fault persistence.
            if (lastObservableLowMs > 0L && now > lastObservableLowMs) {
                lowObservedMs += now - lastObservableLowMs;
            }
            lastObservableLowMs = now;
        }

        // Keep high-demand protection fast, but require longer sustained deficit at
        // ordinary rail-pressure demand so idle/near-stall control transients do not
        // impersonate a fuel-system fault.
        long persistMs = (!Double.isNaN(targetKpa) && !Double.isInfinite(targetKpa)
                && targetKpa >= HIGH_DEMAND_TARGET_KPA) ? LOW_PERSIST_MS : LOW_DEMAND_PERSIST_MS;
        return lowObservedMs >= persistMs;
    }

    public void reset() {
        lowObservedMs = 0L;
        lastObservableLowMs = 0L;
        lowEvidenceActive = false;
    }
}
