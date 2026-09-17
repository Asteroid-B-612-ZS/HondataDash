package io.github.asteroidb612zs.hondatadash.data;

/** RC7 persistent fuel-pressure alert gate; keeps the number live while suppressing semantic transients. */
public final class FuelPressureAlertTracker {
    private static final long LOW_PERSIST_MS = 300L;
    private static final long LOW_DEMAND_PERSIST_MS = 800L;
    private static final double HIGH_DEMAND_TARGET_KPA = 8000.0;
    private long lowSince = 0L;

    public boolean update(boolean engineRunningStable, EngineSemanticState state,
            double actualKpa, double targetKpa, long now) {
        if (!engineRunningStable || Double.isNaN(actualKpa) || Double.isInfinite(actualKpa)) {
            reset();
            return false;
        }
        if (state != null && (state.isShiftActive()
                || state.combustion != EngineSemanticState.CombustionState.FIRING_VALID)) {
            reset();
            return false;
        }

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
        if (lowSince == 0L) lowSince = now;
        // Long-log replay found the residual candidates only around 500-826 rpm
        // with ~4 MPa targets. Keep high-demand protection fast, but require a
        // longer sustained deficit at ordinary rail-pressure demand so a brief
        // idle/near-stall control transient cannot impersonate a fuel-system fault.
        long persistMs = (!Double.isNaN(targetKpa) && !Double.isInfinite(targetKpa)
                && targetKpa >= HIGH_DEMAND_TARGET_KPA) ? LOW_PERSIST_MS : LOW_DEMAND_PERSIST_MS;
        return now - lowSince >= persistMs;
    }

    public void reset() { lowSince = 0L; }
}
