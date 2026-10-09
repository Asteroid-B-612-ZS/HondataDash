# 3.0.1-test.1 replay evidence

Baseline: V3.0.0 `c077201222bb18c300ca4f10639c8063907aa2c3`. Candidate app/source hashes, raw-file identities and per-file results: [results.json](results.json).

## Core14

All 14 raw files match the frozen manifest SHA-256 and frame count: **6,458,780 raw frames, 51,339.435 s, 2,566,979 replay frames**. Read-only normalization uses a 20 ms grid starting at the first timestamp, last observation carried forward, stable timestamp sorting, and a 500 ms freshness horizon. Raw files are unchanged and excluded from Git.

Explicit export units matter:

- Speed/temperature are metric in all Core14 files.
- A/F and target are gasoline-equivalent AFR in 20260518, 20260605, 20260609, 20260611; divide by 14.7. The other Core files already contain lambda.
- MAP is absolute psi in 20260603_002 and 20260605; multiply by 6.894757. Their nonnegative MAP ranges (2.32–33.65 / 2.32–20.31), quantized kPa equivalents and metric wheel speeds support this profile. Other Core MAP channels are absolute kPa.
- The 20261007 extension is different: mph speed, Fahrenheit ECT, vacuum inHg / positive gauge psi MAP relative to PA, and AFR A/F. Its raw SHA is `ca990e0754ccf7270bfd8e50a4bc0cb50f107ae68a68a441259223e1d0a65287`.

The production state tracker/admission and extracted A/F/IGN display methods are run twice, against baseline and candidate. BT42 input deliberately excludes Clutch.Pos; available pedal, PA and K.R are included. Both versions use the same inputs, filters, frame validity and display cadence. A native IT3 trace extension uses its original irregular timestamps; durations are weighted by the next row interval, capped at 500 ms to avoid counting stale gaps. It is not included in the Core14 frame/duration total.

## Results

- **0** compared state/combustion/shift/confidence/admission/IGN-number differences.
- **0** WOT A/F red-frame differences.
- No newly introduced red frames in any replayed input.
- Full Core14 displayed IGN red: **1076.34 → 769.04 s**; A/F red: **28.80 → 19.50 s**.
- Within the exploratory low window (warm ECT, 0–10 km/h, 600–1500 RPM, pedal ≤5%, near/below atmospheric MAP): IGN red **421.94 → 121.90 s**; A/F red **11.06 → 1.76 s**.
- Latest 20261007 low window: IGN red **5.84 → 1.24 s**, A/F red **0 → 0 s**. The latest log does not establish the reported A/F red event.
- 2022 OEM-named low window: IGN red **148.46 → 74.86 s**, A/F red **0.70 → 0 s**. Concurrent K.R, unqualified conditions and the initial qualification period intentionally retain IGN warnings.

The colour latch has history. In the 2022 file, 36 frames (0.72 s) lose an old red latch more than 600 ms after leaving the qualified context. All have a current filtered IGN above −5°; candidate still displays amber during their recovery, and actual angle-danger frames are not suppressed outside qualification. This intended recovery-history delta is recorded instead of hiding it in a broad zero-delta claim.

These are **counterfactual display durations**, not evidence that the historical engine was faulty or that all removed warnings were false. They do not certify wideband sensor accuracy, ECU calibration, physical clutch position or vehicle safety.

## Reproduce

With JDK 17, Python, numpy and pandas, and authorized access to the unchanged raw files:

```bash
python3 tests/prepare_core_display.py --raw-dir /path/to/core14 --output /tmp/core14-replay
python3 tests/replay_display.py --inputs /tmp/core14-replay --source-ref baseline
python3 tests/replay_display.py --inputs /tmp/core14-replay --source-ref current
```

The normalizer accepts the manifest filename, or the explicitly recorded short label, and always requires the frozen SHA. `results.json` binds the comparison to source SHA-256 values. CI validates those source hashes and executes deterministic semantic, lifecycle, rendering and candidate boundary probes; raw private logs are not fetched by CI.

## Limits

This is a JVM model of selected production display methods at 50 Hz, not the entire MainActivity event loop or a replay through the Bluetooth stack. The immutable data layer, exact allowed MainActivity integration, existing semantic tests and replay together constrain the change. Original V2 guardrail numbers (such as 21/21 annotated shifts) remain historical reference values; this report does not relabel them as newly re-annotated vehicle events. Real car installation, system uiMode propagation and LCD appearance remain the test objectives.
