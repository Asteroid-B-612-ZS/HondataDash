#!/usr/bin/env python3
"""V2.0.T3 product + recorder contract checks."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash'
MAIN=(JAVA/'MainActivity.java').read_text()
TRACK=(JAVA/'data/EngineStateTracker.java').read_text()
REC=(JAVA/'diagnostic/FlightRecorder.java').read_text()
BT=(JAVA/'data/BluetoothSource.java').read_text()
BUILD=(ROOT/'app/build.gradle').read_text()
STRINGS=(ROOT/'app/src/main/res/values/strings.xml').read_text()

assert re.search(r'\bversionCode\s+50\b',BUILD)
assert 'versionName "2.0.1-internal.3-hf3"' in BUILD
assert 'Hondata Dash IT3 HF3' in STRINGS

# Last Boost Event Peak: event-latched, raw-MAP sourced, no rolling MAX decay.
for token in (
    'BOOST_EVENT_START_BAR = 0.20f',
    'BOOST_EVENT_END_GRACE_MS = 1800L',
    'updateLastBoostEventPeak(valueForExtreme, state, data, now)',
    'hasLastBoostEventPeak',
    'lastBoostEventPeak',
    'BOOST_EVENT_START',
    'BOOST_EVENT_END',
):
    assert token in MAIN, token
assert 'i != 4 && (EXTREME_POLICY[i] & EXTREME_MAX)' in MAIN
assert 'i != 4 && (EXTREME_POLICY[i] & EXTREME_MAX) != 0' in MAIN
assert '(i == 4 && !hasLastBoostEventPeak)' in MAIN
assert 'resetLastBoostEventPeak();' in MAIN
assert 'flightRecorder.onExtremaReset(4, now);' in MAIN

# Placeholder transport frame cannot initialize semantic state.
gate=MAIN.index('if (!isSemanticFramePlausible(data))')
state=MAIN.index('EngineSemanticState state = engineState.update(data);')
assert gate < state
for token in ('tp >= 0.0 && tp <= 105.0','map >= 10.0 && map <= 400.0',
              'rpm >= 0.0 && rpm <= 10000.0'):
    assert token in MAIN

# HF3: FC1 BT42 high-load T.P may legitimately exceed 100% slightly.
# Road evidence reached 101.5%; preserve narrow headroom without weakening
# the reconnect-placeholder guards.
def plausible(rpm, speed, map_kpa, tp, inj):
    return (0.0 <= rpm <= 10000.0
            and 0.0 <= speed <= 350.0
            and 10.0 <= map_kpa <= 400.0
            and 0.0 <= tp <= 105.0
            and 0.0 <= inj <= 50.0)

assert plausible(2850.0, 80.0, 180.0, 101.5, 5.0)
assert plausible(2500.0, 70.0, 150.0, 105.0, 4.0)
assert not plausible(2500.0, 70.0, 150.0, 105.5, 4.0)
assert not plausible(0.0, 0.0, 0.0, -10.0, 0.0)
assert not plausible(1010.0, 0.0, 0.0, 4.5, 1.0)

# BT42 shift: stable Gear is authoritative; trajectory alone is only an arm.
for token in (
    'BT42_GEAR_STABLE_MS = 100L',
    'BT42_ARM_TIMEOUT_MS = 650L',
    'boolean bt42Profile = gearAvailable && !clutchAvailable',
    'boolean bt42GearEvidence = bt42Profile && stableGearChanged',
    'updateStableGear(gear, now)',
    'shiftFuelCutAuthoritative',
):
    assert token in TRACK, token
assert 'roadShiftContext && !gearAvailable && !clutchAvailable' in TRACK
assert 'fuelCut && shiftFuelCutAuthoritative' in TRACK
assert 'boolean clutchGearEvidence = clutchAvailable && rawGearChanged' in TRACK
assert 'rawGearChanged || (bt42Profile && stableGearChanged)' in TRACK

# Recorder is engine-run gated, not App-open gated.
for token in (
    'PRE_DRIVE_RAW_FRAMES = 64',
    'driveQualified',
    'onEngineRunningSample',
    'onEngineSessionEnded',
    'onAppForegroundChanged',
    'POWER_CUT_RECOVERED',
    '"ACTIVE"',
    'writeSessionMetadataAtomic',
    'writeStatsAtomic',
    'STATS_CHECKPOINT_MS = 10000L',
    'resetSessionCountersExceptRawReceived',
):
    assert token in REC, token
assert 'SESSION_IDLE_CLOSE_MS' not in REC
assert 'if (!driveQualified || !protocolReady || hash != manifestHash) return;' in REC
assert 'if (!startupMaintenanceDone)' in REC
assert 'if (protocolReady && !startupMaintenanceDone)' not in REC
assert 'if (!isEnabled() || !driveQualified || data == null' in REC
assert 'rawCount >= PRE_DRIVE_RAW_FRAMES' in REC
assert 'stamp + "_IT3"' in REC
assert 'syncBestEffort' in REC and 'SyncFailedException' in REC and 'syncWarnings' in REC
# HF2: during road acceptance, Recorder has no automatic Session-directory deletion path.
assert 'enforceRetention()' not in REC
assert 'deleteRecursively(' not in REC
assert 'MAX_TOTAL_BYTES' not in REC and 'MAX_COMPLETE_SESSIONS' not in REC
assert 'if (recoveryMarked) active.delete();' in REC

# HF1: drive qualification must use the existing 1 s stable-running gate.
gate_update = MAIN.index('updateEngineRunningGate(data);')
qualify = MAIN.index('flightRecorder.onEngineRunningSample(')
assert gate_update < qualify
qualify_window = MAIN[qualify:qualify+220]
assert 'engineRunningStable' in qualify_window
assert 'rpmSample >= ENGINE_RUNNING_RPM_THRESHOLD' not in MAIN[qualify-250:qualify+300]

# HF1: lifecycle pause owns poll-worker shutdown until the old reader exits.
for token in (
    'resumePollingAfterLifecyclePause',
    'if (pollingPausedByLifecycle) resumePollingAfterLifecyclePause = true;',
    'if (intentionalDisconnect || pollingPausedByLifecycle)',
    'if (resumePollingAfterLifecyclePause && connected)',
):
    assert token in BT, token
io_catch = BT.index('} catch (IOException e)')
io_block = BT[io_catch:io_catch+1100]
assert io_block.index('if (intentionalDisconnect || pollingPausedByLifecycle)') < io_block.index('connected = false;')

# UI lifecycle only hints recorder; Bluetooth background policy remains unchanged.
assert 'flightRecorder.onAppForegroundChanged(false)' in MAIN
assert 'flightRecorder.onAppForegroundChanged(true)' in MAIN
assert 'dataSource.stopPolling();' in MAIN
assert 'dataSource.startPolling();' in MAIN

# Event noise: transient Modifier remains in semantic_trace, not mirrored every edge.
assert 'Do not mirror every' in REC
assert 'enqueueEvent(now, "MODIFIER"' not in REC

print('PASS: IT3 Last-Boost/BT42-shift/placeholder/drive-session lifecycle contracts')
