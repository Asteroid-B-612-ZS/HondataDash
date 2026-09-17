#!/usr/bin/env python3
"""Static/API17 and hot-path resource checks for RC7.
Full Android resource linking remains a build-environment responsibility.
"""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]
BUILD=(ROOT/'app/build.gradle').read_text()
MAIN=(ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash/MainActivity.java').read_text()
DATA=ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash/data'
FILES=[DATA/n for n in (
 'EngineSemanticState.java','EngineStateTracker.java','CombustionDisplayAdmission.java',
 'FuelPressureAlertTracker.java','TrustedDisplayMemory.java','EphemeralDiagnosticMemory.java')]
TEXT='\n'.join(p.read_text() for p in FILES)
assert re.search(r'\bminSdk\s+17\b',BUILD)
assert re.search(r'\btargetSdk\s+28\b',BUILD)
assert re.search(r'\bversionCode\s+44\b',BUILD)
assert re.search(r'versionName\s+["\']2\.0["\']',BUILD)
for forbidden in ('java.time.','java.util.stream','java.util.function','.stream()','computeIfAbsent(',
                  'List.of(','Map.of(','Set.of(','Optional<','androidx.','kotlin.','CompletableFuture'):
    assert forbidden not in TEXT, forbidden
imports=re.findall(r'^import\s+([^;]+);',TEXT,flags=re.M)
assert set(imports) <= {'android.os.SystemClock'}, sorted(set(imports))
for p in FILES:
    txt=p.read_text()
    assert 'Handler' not in txt and 'Runnable' not in txt and 'Thread' not in txt, p.name
# Critical frame-path methods must not allocate.
def body(txt,start,end): return txt.split(start,1)[1].split(end,1)[0]
adm=(DATA/'CombustionDisplayAdmission.java').read_text()
assert 'new ' not in body(adm,'public Snapshot update(','private boolean isTipOutFrontGuard')
trk=(DATA/'EngineStateTracker.java').read_text()
assert 'new ' not in body(trk,'public EngineSemanticState update(','private void resetStateOutputs')
fp=(DATA/'FuelPressureAlertTracker.java').read_text()
assert 'new ' not in body(fp,'public boolean update(','public void reset')
mem=(DATA/'EphemeralDiagnosticMemory.java').read_text()
assert 'new ' not in body(mem,'public void record(','public int retainedSamples')
for forbidden in ('java.io','File','Writer','OutputStream','SharedPreferences','SQLite','Socket','Log.'):
    assert forbidden not in mem, f'ephemeral memory persists/IOs: {forbidden}'
assert 'CAPACITY = 256' in mem and 'MIN_SAMPLE_MS = 20L' in mem
trusted=(DATA/'TrustedDisplayMemory.java').read_text()
assert 'new ' not in body(trusted,'public void record(','/** Capture once')
assert 'new ' not in body(trusted,'public boolean captureHold(','public boolean hasHoldValue')
# Existing UI handlers are permitted; RC7 may not add animation machinery or SHIFT banners.
assert 'new Animation' not in MAIN and 'ObjectAnimator' not in MAIN and 'ValueAnimator' not in MAIN
assert not re.search(r'setText\s*\(\s*["\']SHIFT["\']\s*\)',MAIN)
assert 'private float boostFilter' in MAIN and 'public void onError(final String msg)' in MAIN
print('PASS: RC7 API17/static hot-path, bounded-memory and no-persistence contracts')
print('NOTE: Android SDK resource linking/APK install must be re-run in the user RC6 build environment.')
