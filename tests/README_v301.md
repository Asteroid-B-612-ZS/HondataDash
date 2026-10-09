# Test candidate validation

Run the commands in `.github/workflows/test-candidate.yml` with JDK 17 and Python 3.
The full Java product must also compile against the official Android API17 jar.

`verify_v301_test.py` reconstructs the V3 MainActivity source after removing only
the explicitly allowed candidate integration. All data classes, layout XML,
shift lights, colour recovery and daytime palette remain frozen. Existing
production verifiers remain in place; historical identity lists also admit this
specific test identity, without accepting arbitrary versions.

`replay_display.py` extracts the relevant production display methods, runs the
actual engine state/admission classes and the new context class, and compares
baseline versus candidate over normalized 50 Hz inputs. It models A/F and IGN
EMA, colour attack/recovery and the 100/50 ms display cadence. It is not an Android
UI event loop, full MainActivity session replay, or physical ECU test. Never use
CSV Clutch.Pos as a candidate input: the supported BT42 manifest omits that PID.

The frozen Core14 manifest identifies raw inputs by SHA-256. Source logs stay
outside Git. Normalization must use explicit per-file units, preserve raw files,
and record gaps and the sampling convention. Candidate results and their source
hashes are recorded with the regression evidence.
