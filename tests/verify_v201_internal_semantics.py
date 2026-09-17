#!/usr/bin/env python3
"""Focused regression contract for V2.0.1-internal.1 semantic refinements."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash/data'
MAIN=(ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash/MainActivity.java').read_text()
STATE=(DATA/'EngineSemanticState.java').read_text()
TRACKER=(DATA/'EngineStateTracker.java').read_text()
ADM=(DATA/'CombustionDisplayAdmission.java').read_text()
FP=(DATA/'FuelPressureAlertTracker.java').read_text()
BUILD=(ROOT/'app/build.gradle').read_text()
assert 'versionCode 45' in BUILD and 'versionName "2.0.1-internal.1"' in BUILD
assert 'applicationId "io.github.asteroidb612zs.hondatadash.internal"' in BUILD
for token in ('enum ThermalContext','UNKNOWN','COLD','WARMING','READY'): assert token in STATE
assert 'public boolean isThermallyReady()' in STATE
assert 'public boolean isThermalWarmup()' in STATE
assert 'public boolean isWarmup()' not in STATE
assert 'isWarmupMainState' not in STATE
assert 'state.isWarmup()' not in MAIN
assert 'state.isThermalWarmup()' in MAIN and 'state.isThermallyReady()' in MAIN
assert 'thermalContext = updateThermalContext(ect, now);' in TRACKER
assert 'THERMAL_READY_CONFIRM_MS = 5000L' in TRACKER
assert 'hot connect/reconnect is trusted immediately' in TRACKER
assert TRACKER.index('thermalContext = updateThermalContext(ect, now);') < TRACKER.index('// 3) MainState.')
assert 'RPM_DIP' not in STATE and 'return isShiftActive();' in STATE
strim=ADM.split('private void updateStrim(',1)[1].split('private static float finite',1)[0]
assert 'Modifier.SHIFT' not in strim
for token in ('14.26 h historical replay','Never hide a real condition indefinitely',
              'S.TRIM is informative but highly dynamic','no per-frame allocation on API 17 head units'):
    assert token in ADM
assert 'EVIDENCE_MAX_PAUSE_MS = 8000L' in FP and 'evidencePausedSinceMs' in FP
body=FP.split('public boolean update(',1)[1].split('public void reset()',1)[0]
assert 'now - evidencePausedSinceMs >= EVIDENCE_MAX_PAUSE_MS' in body
def replay(samples,threshold=800,ttl=8000):
    observed=last=paused=0; active=False; alerts=[]
    for now,observable,low in samples:
        if not observable:
            if active:
                if not paused: paused=now
                if now-paused>=ttl: observed=last=paused=0; active=False
            last=0; alerts.append(False); continue
        paused=0
        if not low: observed=last=0; active=False; alerts.append(False); continue
        if not active: active=True; observed=0; last=now
        else:
            if last and now>last: observed+=now-last
            last=now
        alerts.append(observed>=threshold)
    return alerts,observed
s=[(t,True,True) for t in range(100,701,100)]+[(800,False,True),(1200,False,True),(1700,False,True)]+[(1800,True,True),(1900,True,True),(2000,True,True)]
a,o=replay(s); assert o==800 and a[-1]
s=[(t,True,True) for t in range(100,701,100)]+[(800+i*1000,False,True) for i in range(10)]+[(10900,True,True),(11000,True,True),(11100,True,True)]
a,o=replay(s); assert not any(a) and o==200
s=[(t,True,True) for t in range(100,701,100)]+[(800,False,True),(1200,False,True),(1300,True,False),(1400,True,True),(1500,True,True)]
a,o=replay(s); assert not any(a) and o==100
print('PASS: V2.0.1-internal.1 explicit thermal semantics, calibration provenance, bounded fuel-pressure evidence')
