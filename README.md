# HondataDash

A lightweight Android dashboard for displaying Hondata FlashPro Bluetooth data on a dedicated 800×480 landscape head unit. It focuses on a small set of tuning-relevant values and is designed for quick in-car readability rather than full ECU calibration.

## What This App Is

- A compact, low-latency dashboard that reads Hondata FlashPro over Bluetooth SPP.
- Built around **semantic display logic** so values shown during DFCO, warmup, sync recovery, or transient conditions are less likely to be mistaken for stable tuning data.
- Zero-dependency: pure Android Framework API, no `androidx`, no Kotlin, no `.so`. Release APK is about 52 KB.

## What This App Is Not

- Not a general OBD reader. It only speaks the Hondata FlashPro Bluetooth protocol.
- Not a replacement for Hondata FlashPro Manager. It does not flash or edit calibrations.
- Not an auto-tuning tool. It displays data; tuning decisions stay with the user.

## Target Setup

| Item | Spec |
|------|------|
| OS | Android 4.2+ (API 17) |
| Screen | 800×480 landscape, 160dpi |
| Data source | Hondata FlashPro Bluetooth |
| Build | Gradle + JDK 17 |

The layout is tuned for a single fixed-size head unit, not for arbitrary phone screens.

## Main Dashboard

```
┌──────────────────────────────────────────────────────────────────────┐
│            ▓▓  ▓▓  ▓▓  ▓▓  ▓▓  ▓▓          6-LED Shift Light      │ ← 42dp (LED + header overlay)
│                                              status                │
├────────────┬────────────┬────────────┬──────────────────────────────┤
│ Ethanol(%) │ ECT(°C)    │ IAT(°C)    │ L.TRIM(%)                    │
│            │            │            │                              │
│ E85        │ 86         │ 33         │ +2.5                         │
│  92    87  │  90    82  │  35    31  │  +1.0  4.5                   │ ← MAX / MIN
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │ ← ScaleBar + peak
├────────────┼────────────┼────────────┼──────────────────────────────┤
│ MAP(bar)   │ A/F        │ IGN(°)     │ S.TRIM(%)                    │
│            │            │            │                              │
│ +0.15      │ 14.6       │ +22.5      │ +1.8                         │
│  1.20 0.88 │  15.0 14.2 │  25.0 12.5 │  2.5  5.0                    │
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │
├──────┬─┬─┬─┬─┬────┬────┬─────┬──────┬──────┬───────────────────────┤
│ K.C  │C1│C2│C3│C4│K.R │K.L │BAT(V)│F.P   │W.G  │T.P               │ ← Bottom 59dp
│  25% │ 0│ 1│ 0│ 0│ 0.5│24.0│ 13.7 │2.35  │ 45  │ 18                │
└──────┴─┴─┴─┴─┴────┴────┴─────┴──────┴──────┴───────────────────────┘
```

### 6-LED Shift Light

- 6 LEDs with progressive RPM activation.
- Thresholds (tuned for the 1.5T powerband): 3000 / 3500 / 4000 / 4500 / 5000 / 5500.
- Colors: Green ×2 → Yellow ×2 → Red ×2.
- ≥ 6400 RPM: all lit, 10 Hz flash.
- Overlaid with the header in a `FrameLayout` to save vertical space.

### 4×2 Main Grid

Each card has: label + unit (top), large main value (center-left), live MAX/MIN (center-right), and a scale bar (bottom).

**Row 1 — slow data**

| Card | PID | Label | Format | Range | Color / flash |
|------|-----|-------|--------|-------|----------------|
| 0 | 0xB03 | Ethanol | %.0f | 0–100% | <E20 white, E20–40 green, E40–60 yellow, >E60 red |
| 1 | 0x160 | ECT | %.0f | 40–120°C | <80 blue, 80–95 white, 96–100 red, >100 purple flash |
| 2 | 0x151 | IAT | %.0f | 20–100°C | <35 green, 35–44 white, 45–54 yellow, 55–64 red, ≥65 purple flash |
| 3 | 0x332 | L.TRIM | %+.1f | −25…+25% | Frozen during DFCO |

**Row 2 — fast data**

| Card | PID | Label | Format | Range | Color / flash |
|------|-----|-------|--------|-------|----------------|
| 4 | 0x110 | MAP | %+.1f | −1.0…2.0 bar | Relative pressure (MAP − Baro), kPa÷100 → bar |
| 5 | 0x320 | A/F | %.1f | 9–18 | Lambda×14.7; <11 red, 11–14.5 yellow, 14.5–15.5 green, >15.5 red + flash on throttle; shows "DFCO" on decel cut |
| 6 | 0x140 | IGN | %+.1f | −40…40° | Shows "DFCO" on decel cut |
| 7 | 0x330 | S.TRIM | %+.1f | −25…+25% | Shows "DFCO" on decel cut |

> **A/F flash exclusion**: the A/F red flash only fires when throttle plate (TP) > 5%, so coasting, lifting, and shifting do not trigger false warnings.

### Bottom Row (59dp)

| Element | Weight | PID | Format | Note |
|---------|--------|-----|--------|------|
| K.C | 2 | 0x412 | %.0f | Knock control; <55 green, 55–65 yellow, >65 red flash |
| CYL 1–4 | 1×4 | 0x421–424 | %.0f | Per-cylinder knock count; 0 green, 1 yellow, ≥2 red |
| K.R | 1 | 0x410 | %.1f | Knock retard |
| K.L | 1 | 0x411 | %.1f | Knock limit |
| BAT(V) | 1 | 0x180 | %.1f | Battery voltage |
| F.P(bar) | 1 | 0x191 | %.1f | Fuel pressure, kPa÷100 → bar |
| W.G(%) | 1 | 0x1A0 | %.0f | Wastegate |
| T.P(%) | 1 | 0x122 | %.0f | Throttle plate |

## Data Source

- **Bluetooth (production)**: Hondata FlashPro over SPP, 50 Hz polling. The target device MAC is configured in `BluetoothSource`.
- **Demo**: `DemoSource` plays back a real LOG at 20 Hz through a 30-second state cycle (IDLE → NORMAL → WOT → TRANSIENT → DFCO → CRUISE), used to exercise the display logic without a car.

## Semantic Display Logic

`EngineSemanticState` carries four orthogonal dimensions:

- **MainState** (mutually exclusive): `DFCO` / `WOT` / `WARMUP` / `IDLE` / `NORMAL`.
- **SubState** (detail): WOT → SPOOL / PEAK / HOLD; DFCO → ENTER / HOLD; or NONE.
- **Modifier** (orthogonal overlay): TIP_IN / TIP_OUT / BOOST_SURGE / RPM_DIP / NONE.
- **Confidence** (0.0–1.0): weighted calculation + low-pass filter (~200 ms inertia).

WOT is detected from ECU strategy signals (Open Loop + rich command + boost established + RPM threshold) rather than throttle position — important for the L15B7 torque-model ECU where throttle ≠ load.

**DFCO handling**: A/F, IGN, S.TRIM show "DFCO" in gray during deceleration fuel cut; L.TRIM is frozen at its last valid value. Transition in and out is synchronized.

## MAX/MIN Recording

- **History admission**: only samples from eligible states enter the extreme records, so DFCO exit, warmup, and transient spikes do not pollute tuning-relevant MAX/MIN.
- **Session vs. recent**: session-level extremes are separated from recent dynamic extremes.
- **Engine baseline** (V2.6.7): at engine start, each card takes its first valid sample as a one-shot MAX/MIN baseline, gated per card (ethanol waits for the settling gate). The flag clears when the engine stops.
- **Confidence gray**: A/F, IGN, S.TRIM show a fixed gray color when confidence is low, so cold-start and warmup readings are visually marked as low-reference.

## Bluetooth Recovery

On disconnect the data layer restarts while the UI stays alive:

1. Stop the old poll thread and close socket/streams.
2. Wait for the Bluetooth stack to release the RFCOMM channel (2.5 s in V2.6.7, raised from 1 s for older Android stacks).
3. Reconnect from scratch on a fresh thread, with a full handshake.
4. Exponential backoff (1 s → 2 s → 4 s → 8 s) on repeated failures.
5. **Session preserve** (V2.6.7): a short dropout (under 5 min) keeps the value and extreme layer; only a long dropout with RPM ≤ 300 resets the session.

## Installation

No APK is published in GitHub Releases (the production build carries a private device MAC). Build it locally — see below — and install the generated APK.

## Build

```bash
JAVA_HOME="~/.jdks/jbr-17.0.14" ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

For a demo build (LOG playback, no car needed), set `USE_DEMO = true` in `MainActivity` and rebuild.

## Limitations

- Fixed 800×480 layout; not responsive to other screen sizes.
- Speaks only the Hondata FlashPro Bluetooth protocol.
- The production device MAC is hardcoded; for your own FlashPro, edit `BluetoothSource.FLASHPRO_MAC`.
- No configuration UI; tuning-relevant thresholds are constants in code.

## File Structure

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # UI, data binding, filtering, state machine
├── ShiftLightView.java        # 6-LED shift light
└── data/
    ├── DataSource.java        # Data source interface
    ├── BluetoothSource.java   # FlashPro SPP + reconnect (V2.6.7 hardened)
    ├── EngineSemanticState.java
    ├── EngineStateTracker.java
    ├── HondataProtocol.java   # Protocol parsing + scaling
    ├── SensorData.java        # PID → Double map
    └── DemoSource.java        # Real-LOG playback
```

## Version History (recent)

See [CHANGELOG.md](CHANGELOG.md) for the full history from V1.0.

### V2.6.7 (2026-06-15) — Engine Baseline, BT Reconnect Hardening, Confidence Cleanup
Removed startup self-test; added one-shot engine baseline at ignition-on; short BT dropouts now preserve the session; fixed silent reconnect death. *Pending in-car verification.*

### V2.6.6 (2026-06-11) — Self-Test Isolation, Confidence Gray, Ethanol Settling
Isolated self-test from extreme records; restored low-confidence gray for A/F/IGN/S.TRIM; added ethanol settling gate. Note: self-test was later removed in V2.6.7.

### V2.6.5 (2026-06-08) — Unified Main Value Height + Fixed-Width Signs
Unified main value height across cards; made +/- signs render at fixed equal width.

### V2.6.4 (2026-06-07) — Fixed Sign, Startup Polish, Engine Gate
Fixed-width sign rendering; first engine-running gate for extreme recording.

### V2.6.3 (2026-06-06) — Semantic Alpha + Compact Sign Pass
Corrected DFCO/SYNC semantic alpha; smoothed main value fit; improved compact sign rendering.

## Notes

- This app is a personal project for a specific car (2020 Civic 1.5T with FlashPro). It is not a productized OBD tool.
- No telemetry, no network calls, no analytics. The only Bluetooth traffic is the FlashPro SPP session.
