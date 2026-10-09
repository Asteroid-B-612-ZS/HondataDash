package io.github.asteroidb612zs.hondatadash;

import io.github.asteroidb612zs.hondatadash.data.EngineSemanticState;
import io.github.asteroidb612zs.hondatadash.data.HondataProtocol;
import io.github.asteroidb612zs.hondatadash.data.SensorData;

/** Low-speed/low-demand display evidence, NOT a clutch detector or engine state. */
final class LowLoadDisplayContext {
    private long candidateSince = -1L, lastFrame = -1L;
    private boolean active, calmIgn;

    void update(EngineSemanticState state, SensorData data, long now) {
        if (lastFrame >= 0 && (now < lastFrame || now - lastFrame > 500L)) reset();
        lastFrame = now;
        double rpm = data.getDouble(HondataProtocol.CID_RPM);
        double speed = data.getDouble(HondataProtocol.CID_Speed);
        double pedal = data.getDouble(HondataProtocol.CID_TPS);
        double plate = data.getDouble(HondataProtocol.CID_ThrottlePlate);
        double map = data.getDouble(HondataProtocol.CID_MAP);
        double baro = data.getDouble(HondataProtocol.CID_PA);
        double inj = data.getDouble(HondataProtocol.CID_Inj);
        // Explicit finite ranges fail closed for missing/invalid BT42 channels.
        boolean eligible = state != null && state.isThermallyReady() && !state.isWot()
                && !state.isShiftActive() && state.modifier == EngineSemanticState.Modifier.NONE
                && state.combustion == EngineSemanticState.CombustionState.FIRING_VALID
                && rpm >= 600 && rpm < (active ? 1800 : 1501)
                && speed >= 0 && speed < (active ? 15 : 10.01)
                && pedal >= 0 && pedal < (active ? 10 : 5.01)
                && plate >= 0 && plate <= 10
                && baro >= 50 && baro <= 110 && map >= 10
                && map - baro <= (active ? 10 : 5)
                && inj > 0.30 && inj <= 50;
        if (!eligible) { candidateSince = -1L; active = false; calmIgn = false; return; }
        if (candidateSince < 0) candidateSince = now;
        if (now - candidateSince >= 500L) active = true;
        double retard = data.getDouble(HondataProtocol.CID_KnockRetard);
        // Never suppress the old angle colour if contemporaneous retard is present/unknown.
        calmIgn = active && retard == 0.0;
    }

    boolean calmIgn() { return calmIgn; }
    boolean isActive() { return active; }
    long afAttackMs(int severity, long ordinaryAttack) {
        if (!active || severity <= 0) return ordinaryAttack;
        return severity >= 2 ? 3000L : 2000L;
    }
    void reset() { candidateSince = -1L; lastFrame = -1L; active = false; calmIgn = false; }
}
