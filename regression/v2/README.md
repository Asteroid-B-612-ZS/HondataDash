# HondataDash V2 Regression Dataset

This directory defines the frozen regression dataset for the HondataDash V2.x line.

## Purpose

The dataset exists to answer one question before every V2.x release:

> Did the new code break semantics that V2.0 already proved correct?

Historical real-car logs are treated as regression assets, not tuning material. New real-car logs may justify refinements, but any change to semantic state handling, display admission, recovery timing, semantic extrema, fuel-pressure gating, or related confidence logic must be replayed against this frozen baseline.

## Frozen Core Dataset v1.0

- Core files: **14**
- Raw Hondata frames: **6,458,780**
- Source duration: **14.260954 h**
- Validated 50 Hz replay frames: **2,566,979**
- Logged channels per file: **105**
- Reference release: **HondataDash V2.0**
- Reference commit: `e8877aa8ebbbfe7392478b77724deb676326f121`

The authoritative file identity is defined by `manifest_v1.0.json`; SHA-256 must match before a file is accepted as part of the frozen Core Dataset.

## V2.0 Regression Guardrails

| Metric | Frozen V2.0 baseline |
|---|---:|
| SHIFT recognition | 21/21 |
| SHIFT false negative | 0 |
| SHIFT false positive | 0 |
| SHIFT → DFCO misclassification | 0 |
| Injector-off / fuel-cut sensitive live leak | 0 |
| Neutral/coast A/F leak | 0 |
| SHIFT active maximum | ≈ 3.02 s |
| A/F physical-stable recovery | ≈ 800 ms |
| A/F display admission | ≈ 820 ms |
| Fuel Pressure false alert | 0 |

A future change may differ from the baseline only when the difference is intentional, supported by real-car evidence, documented, and explicitly accepted as a bug fix or semantic refinement.

## Files

- `manifest_v1.0.json` — authoritative machine-readable identity and baseline definition.
- `manifest_v1.0.csv` — compact human-readable Core Dataset file list and SHA-256 table.

The raw LOG files are intentionally not stored in GitHub. They are kept in the project's Google Drive data repository under `HondataDash V2 Regression Dataset/Core Dataset`.

## Dataset policy

The 14-file Core Dataset is frozen. Do not edit, normalize, trim, overwrite, or replace these files. Derived data belongs outside the Core Dataset.

Additional real-car LOGs may be placed in the Drive `Extended Dataset` area. Extended data can expand future test coverage without changing the identity of Core Dataset v1.0.

## Required use

Full regression is mandatory when changes touch any of the following:

- `EngineSemanticState`
- `EngineStateTracker`
- `CombustionDisplayAdmission`
- SHIFT / DFCO / OTHER_FUEL_CUT semantics
- A/F / IGN / S.TRIM HOLD, recovery, trust, confidence, or weighting
- semantic extrema admission
- Fuel Pressure abnormality gating
- related timers or thresholds

UI-only changes should still run the core regression to prove that display refactoring did not accidentally alter data semantics.

## Development rule

`Real-car evidence → Code change → V2 Regression Dataset replay → Regression delta → Static review → Build → Real-car validation → Release`

The goal is not to make every future version numerically identical to V2.0. The rule is stricter:

> Every regression delta must be an intended delta.
