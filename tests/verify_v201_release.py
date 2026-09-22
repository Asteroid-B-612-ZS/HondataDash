#!/usr/bin/env python3
"""V2.0.1 production-release contract: HF3 semantics, no persistent diagnostics."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash'
DATA=JAVA/'data'
BUILD=(ROOT/'app/build.gradle').read_text()
MANIFEST=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
MAIN=(JAVA/'MainActivity.java').read_text()
BT=(DATA/'BluetoothSource.java').read_text()
STRINGS=(ROOT/'app/src/main/res/values/strings.xml').read_text()
TRACK=(DATA/'EngineStateTracker.java').read_text()

assert re.search(r'\bversionCode\s+51\b', BUILD)
assert 'versionName "2.0.1"' in BUILD
assert 'applicationId "io.github.asteroidb612zs.hondatadash"' in BUILD
assert 'Hondata Dash' in STRINGS and 'IT3' not in STRINGS and 'HF3' not in STRINGS

# Production must not persist local diagnostics.
assert 'WRITE_EXTERNAL_STORAGE' not in MANIFEST
assert 'FlightRecorder' not in MAIN
assert 'setDiagnosticObserver' not in MAIN
assert 'DiagnosticObserver' not in BT
assert 'setDiagnosticObserver' not in BT
assert not (JAVA/'diagnostic/FlightRecorder.java').exists()
assert not (DATA/'DiagnosticObserver.java').exists()
assert '"HondataDash/Diagnostics"' not in MAIN

# HF3 proven product semantics stay frozen.
assert 'tp >= 0.0 && tp <= 105.0' in MAIN
assert 'map >= 10.0 && map <= 400.0' in MAIN
for token in (
    'BOOST_EVENT_START_BAR = 0.20f',
    'BOOST_EVENT_END_GRACE_MS = 1800L',
    'updateLastBoostEventPeak(valueForExtreme, state, data, now)',
    'BT42_GEAR_STABLE_MS = 100L',
    'boolean bt42Profile = gearAvailable && !clutchAvailable',
    'boolean bt42GearEvidence = bt42Profile && stableGearChanged',
    'fuelCut && shiftFuelCutAuthoritative',
):
    assert token in (MAIN + TRACK), token

# No accidental transport/polling redesign.
assert BT.count('sendCommand(HondataProtocol.CMD_SENSOR_DATA)') == 1
assert 'data.receivedAtElapsedMs = receivedAt;' in BT
assert 'pollingPausedByLifecycle' in BT
assert 'resumePollingAfterLifecyclePause' in BT

print('PASS: V2.0.1 production contract (HF3 semantics, recorder removed)')
