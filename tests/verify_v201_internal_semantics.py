#!/usr/bin/env python3
"""Focused regression contract for V2.0.1-internal.1 semantic refinements."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash/data'
STATE = (DATA/'EngineSemanticState.java').read_text()
TRACKER = (DATA/'EngineStateTracker.java').read_text()
ADM = (DATA/'CombustionDisplayAdmission.java').read_text()
FP = (DATA/'FuelPressureAlertTracker.java').read_text()
BUILD = (ROOT/'app/build.gradle').read_text()

# Version / scope.
assert 'versionCode 45' in BUILD
assert 'versionName "2.0.1-internal.1"' in BUILD
assert 'applicationId "io.github.asteroidb612zs.hondatadash.internal"' in BUILD

# Thermal context is orthogonal to MainState, so cold WOT is representable.
for token in ('enum ThermalContext', 'UNKNOWN', 'COLD', 'WARMING', 'READY'):
    assert token in STATE, token
assert 'public ThermalContext thermal' in STATE
assert 'public boolean isThermallyReady()' in STATE
assert 'public boolean isWarmupMainState()' in STATE
assert 'public boolean isWarmup()  { return thermal != ThermalContext.READY; }' in STATE
assert 'thermalContext = updateThermalContext(ect, now);' in TRACKER
assert 'state.thermal = thermalContext;' in TRACKER
assert 'THERMAL_READY_CONFIRM_MS = 5000L' in TRACKER
# Thermal update must happen before MainState WOT classification.
assert TRACKER.index('thermalContext = updateThermalContext(ect, now);') < TRACKER.index('// 3) MainState.')

# Semantic cleanup: RPM_DIP orphan removed; SHIFT identity owned by ShiftPhase.
assert 'RPM_DIP' not in STATE
assert 'return isShiftActive();' in STATE
strim_body = ADM.split('private void updateStrim(',1)[1].split('private static float finite',1)[0]
assert 'Modifier.SHIFT' not in strim_body

# Fuel-pressure semantics: unobservable pauses evidence rather than clearing it.
update_body = FP.split('public boolean update(',1)[1].split('public void reset()',1)[0]
assert 'lowObservedMs' in FP and 'lastObservableLowMs' in FP and 'lowEvidenceActive' in FP
unobs = update_body.split('if (state != null',1)[1].split('boolean low;',1)[0]
assert 'reset();' not in unobs
assert 'lastObservableLowMs = 0L;' in unobs
assert 'if (!low)' in update_body and 'reset();' in update_body.split('if (!low)',1)[1]

# Independent synthetic model of the intended observed-time persistence policy.
def replay(samples, threshold):
    observed = 0
    last_low_t = 0
    active = False
    alerts = []
    for now, observable, low in samples:
        if not observable:
            last_low_t = 0
            alerts.append(False)
            continue
        if not low:
            observed = 0
            last_low_t = 0
            active = False
            alerts.append(False)
            continue
        if not active:
            active = True
            observed = 0
            last_low_t = now
        else:
            if last_low_t and now > last_low_t:
                observed += now - last_low_t
            last_low_t = now
        alerts.append(observed >= threshold)
    return alerts, observed

# 600 ms valid low + 1 s SHIFT + 200 ms valid low => alert at 800 ms observed low.
samples=[]
for t in range(100, 701, 100): samples.append((t, True, True))
samples += [(800, False, True), (1200, False, True), (1700, False, True)]
samples += [(1800, True, True), (1900, True, True), (2000, True, True)]
alerts, observed = replay(samples, 800)
assert observed == 800, observed
assert alerts[-1] is True

# If the first observable post-shift sample is healthy, prior evidence must clear.
samples=[]
for t in range(100, 701, 100): samples.append((t, True, True))
samples += [(800, False, True), (1200, False, True)]
samples += [(1300, True, False), (1400, True, True), (1500, True, True)]
alerts, observed = replay(samples, 800)
assert not any(alerts)
assert observed == 100

print('PASS: V2.0.1-internal.1 thermal orthogonality, semantic cleanup, and fuel-pressure pause semantics')
