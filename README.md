# HondataDash

HondataDash is a lightweight Android landscape dashboard for **Hondata FlashPro Bluetooth data**, primarily designed for real-time use on 800×480 in-car head units.

> Unofficial personal/community project. Not affiliated with or endorsed by Hondata, Inc.

## Current release

**V2.0** is the current generation. The public release history is intentionally reduced to two meaningful generations:

- **V1.0** — the original stable release, corresponding to the previously validated V2.7.0 baseline.
- **V2.0** — the current generation with the OEM-style interface, semantic engine-state handling, trusted display memory, semantic extrema and more complete transient handling.

Earlier development numbers and RC labels were internal iterations and are no longer retained as public releases.

## Key features

- Hondata FlashPro Bluetooth SPP data acquisition.
- Lightweight Android 4.2+ / API 17 implementation.
- OEM-inspired 800×480 landscape dashboard.
- 4×2 primary cards: Ethanol, ECT, IAT, L.TRIM, MAP, A/F, IGN and S.TRIM.
- Symmetric 5+5 RPM shift indicator.
- Context-aware A/F, IGN and S.TRIM protection during shifts, DFCO, fuel cut and recovery.
- MAP and RPM remain live where transient response is genuinely useful.
- Interpretability-weighted S.TRIM presentation.
- Every primary card retains two extrema slots; semantic admission prevents cold-start, fuel-cut and short transient spikes from contaminating useful history.
- Persistence-gated fuel-pressure warnings.
- Short diagnostic traces remain in RAM rather than being continuously written to storage.
- Pure Android Framework implementation with no AndroidX, Kotlin or native `.so` dependency.

## Display philosophy

> **Real data is not automatically useful driver information.**

V2.0 separates raw validity, current-context interpretability, warning relevance and extrema eligibility. Normal operation stays visually calm, transient conditions avoid misleading emphasis, and persistent credible abnormalities receive stronger attention.

## Main layout

| ETHANOL | ECT | IAT | L.TRIM |
|---|---|---|---|
| MAP | A/F | IGN | S.TRIM |

Every primary card retains its name, unit, large current value, two extrema slots, scale bar and live position indication. V2.0 changes extrema **admission and retention rules**, not the established card geometry or slot count.

## V1.0 → V2.0

V1.0 established stable FlashPro Bluetooth communication, data display, basic semantic colors, reconnect behavior and session handling. V2.0 adds separate SHIFT/DFCO/OTHER_FUEL_CUT semantics, trusted display memory, recovery gating, interpretability-weighted S.TRIM, semantic extrema, persistence-gated fuel-pressure alerts, in-memory diagnostic traces and a complete OEM-style UI redesign.

## Application identity

V2.0: `io.github.asteroidb612zs.hondatadash`  
V1.0: `com.hondata.dash`

Android treats the two generations as separate applications. Moving to V2.0 is therefore a fresh install and does not automatically migrate V1.0 settings.

## Build environment

| Item | Value |
|---|---|
| compileSdk | 33 |
| minSdk | 17 |
| targetSdk | 28 |
| JDK | 17 |
| Java source compatibility | 11 |
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |

Font binaries are not stored in the repository; builds must restore and verify the required fonts from the official source.

## Limitations

- Hondata FlashPro Bluetooth protocol only.
- Primarily designed for 800×480 landscape head units.
- No ECU flashing, calibration editing or automatic tuning.
- Display thresholds and semantic rules are project-specific and are not official Hondata product definitions.

Designed by **ZhouQiZhi**
