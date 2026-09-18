#!/usr/bin/env python3
"""V2.0.T2 flight-recorder observer-boundary and identity checks."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash'
DATA=JAVA/'data'
DIAG=JAVA/'diagnostic'
BUILD=(ROOT/'app/build.gradle').read_text()
MANIFEST=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
MAIN=(JAVA/'MainActivity.java').read_text()
BT=(DATA/'BluetoothSource.java').read_text()
PROTOCOL=(DATA/'HondataProtocol.java').read_text()
REC=(DIAG/'FlightRecorder.java').read_text()
OBS=(DATA/'DiagnosticObserver.java').read_text()
FP=(DATA/'FuelPressureAlertTracker.java').read_text()

def body(text,start,end):
    assert start in text, start
    part=text.split(start,1)[1]
    assert end in part, end
    return part.split(end,1)[0]

assert re.search(r'\bversionCode\s+46\b',BUILD)
assert 'versionName "2.0.1-internal.2"' in BUILD
assert 'applicationId "io.github.asteroidb612zs.hondatadash.internal"' in BUILD
assert 'WRITE_EXTERNAL_STORAGE' in MANIFEST
assert 'ACCESS_FINE_LOCATION' not in MANIFEST
assert '"HondataDash/Diagnostics"' in MAIN

# The recorder must observe the existing transport; it may never schedule transport.
assert BT.count('sendCommand(HondataProtocol.CMD_SENSOR_DATA)') == 1
assert 'setDiagnosticObserver' in BT
assert 'observer.onProtocolReady' in BT
assert 'observer.onRawFrame(frameSequence, receivedAt, resp)' in BT
assert 'CMD_SENSOR_DATA' not in REC
assert 'sendCommand' not in REC
assert 'BluetoothSocket' not in REC
assert 'fullReset' not in REC
assert 'scheduleReconnect(' not in REC
assert 'reconnectWithBackoff(' not in REC

# Hot-path hooks enqueue/copy only; all persistent I/O belongs to the writer thread.
raw=body(REC,'public void onRawFrame(','/**\n     * Called on the UI path')
semantic=body(REC,'public void recordSemantic(','/** Records accepted extrema changes only')
for forbidden in ('FileOutputStream','FileWriter','BufferedWriter','writeSessionMetadata',
                  'writeManifest','Thread.sleep','wait('):
    assert forbidden not in raw, ('raw hot path',forbidden)
    assert forbidden not in semantic, ('semantic hot path',forbidden)
assert 'RAW_QUEUE_CAPACITY = 1024' in REC
assert 'TRACE_QUEUE_CAPACITY = 256' in REC
assert 'TRACE_INTERVAL_MS = 50L' in REC
assert 'SESSION_IDLE_CLOSE_MS = 60000L' in REC
assert '"Hondata-FlightRecorder"' in REC
assert 'rawDropped++' in REC and 'traceDropped++' in REC

# Evidence must remain re-decodable and self-identifying.
for token in ('channel_manifest.csv','raw_frames.bin','semantic_trace.csv','events.csv',
              'extrema.csv','session.json','recorder_stats.json','"COMPLETE"','"INCOMPLETE"'):
    assert token in REC, token
assert 'd0703f068517ae43e9a18492a65d4e4f4655f837' in REC
assert 'manifestPids' in REC and 'manifestCs' in REC and 'manifestCt' in REC
assert 'data.frameSequence' in BT and 'public long frameSequence' in (DATA/'SensorData.java').read_text()

# Existing parser remains dynamic and the new observer reads definitions, not a 21-PID list.
assert 'List<SensorDef> sensorDefs' in PROTOCOL
assert 'getSensorDefs()' in PROTOCOL
assert 'sensorDefs.size() == sensorCount' in PROTOCOL
assert 'List<HondataProtocol.SensorDef>' in OBS

# Semantic/fuel-pressure logic does not consult recorder output.
for semantic_file in ('EngineStateTracker.java','CombustionDisplayAdmission.java',
                      'FuelPressureAlertTracker.java','TrustedDisplayMemory.java'):
    text=(DATA/semantic_file).read_text()
    assert 'FlightRecorder' not in text and 'DiagnosticObserver' not in text, semantic_file
assert 'getLowObservedMs()' in FP and 'isLowEvidenceActive()' in FP

print('PASS: IT2 observer-only Flight Recorder boundary, storage and identity contracts')
