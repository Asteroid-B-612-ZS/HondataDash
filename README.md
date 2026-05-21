# Hondata Dash — Car Dashboard

A custom Android dashboard app that replaces the Hondata official app, designed for 800×480 landscape car head units. Connects to Hondata FlashPro via Bluetooth SPP and displays 42 sensor parameters in real time.

## Requirements

| Item | Spec |
|------|------|
| OS | Android 4.2+ (API 17) |
| Screen | 800×480 landscape, 160dpi |
| Data Source | Hondata FlashPro Bluetooth |
| Build | Gradle + JDK 17 |

## Build & Deploy

```bash
JAVA_HOME="~/.jdks/jbr-17.0.14" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hondata.dash/.MainActivity
```

## UI Layout (V1.2)

```
┌──────────────────────────────────────────────────────────────────────┐
│            ▓▓  ▓▓  ▓▓  ▓▓  ▓▓  ▓▓          6-LED Shift Light      │ ← 42dp (LED+Header overlay)
│ Powered by Helijohnny                        Demo (模拟)  Connected │
├────────────┬────────────┬────────────┬──────────────────────────────┤
│ Ethanol(%) │ ECT(°C)    │ IAT(°C)    │ Boost(bar)                   │
│            │            │            │                              │
│ E30        │ 86         │ 33         │ 0.15                         │ ← Row 1: integer, height×1.2
│  92    87  │  90    82  │  35    31  │  1.20  0.88                  │ ← MAX/MIN (color-coded ±)
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │ ← ScaleBar
├────────────┼────────────┼────────────┼──────────────────────────────┤
│ A/F        │ IGN(°)     │ S.TRIM(%)  │ L.TRIM(%)                    │
│            │            │            │                              │
│ 14.6       │ 22.5       │ 1.8        │ 2.5                          │ ← Row 2: decimal, height×1.4
│  15.0 14.2 │  25.0 12.5 │  2.5  5.0  │  1.0  4.5                   │ ← MAX/MIN (± by color)
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │
├──────┬─┬─┬─┬─┬────┬────┬─────┬──────┬──────┬───────────────────────┤
│ K.C  │C1│C2│C3│C4│K.R │K.L │BAT(V)│F.P   │W.G  │T.P               │ ← Bottom 59dp
│  25% │ 0│ 1│ 0│ 0│ 0.5│24.0│ 13.7 │2.35  │ 45  │ 18                │
└──────┴─┴─┴─┴─┴────┴────┴─────┴──────┴──────┴───────────────────────┘
```

### 6-LED Shift Light

- 6 large LEDs with progressive RPM activation
- Thresholds: 3000 / 3500 / 4000 / 4500 / 5000 / 5500
- Colors: Green ×2 → Yellow ×2 → Red ×2
- ≥6400 RPM: all lit, 10Hz flash
- Thresholds lowered: 3000/3500/4000/4500/5000/5500 (tuned for 1.5T powerband)
- Overlaid with Header in FrameLayout to save vertical space

### 4×2 Main Data Grid

Each sensor card contains:
- **Top**: English abbreviation + unit in parentheses
- **Center-left**: Large value (Row 1 height×1.2, Row 2 height×1.4, width unchanged)
- **Center-right**: MAX/MIN real-time tracking (± shown by color, not symbol)
- **Bottom**: ScaleBarView scale progress bar

#### Row 1 Parameters

| Card | PID | Label | Format | Range | Color/Flash |
|------|-----|-------|--------|-------|-------------|
| card0 | 0xB03 | Ethanol | %.0f | 0-100% | "E" prefix, <E20 white, E20-40 green, E40-60 yellow, >E60 red |
| card1 | 0x160 | ECT | %.0f | 40-120°C | <80 blue, 80~95 white, 96~100 red, >100 purple flash |
| card2 | 0x151 | IAT | %.0f | 20-100°C | <35 green, 35~44 white, 45~54 yellow, 55~64 red, ≥65 purple flash |
| card3 | 0x110 | Boost | %.1f | -1.0~2.0 bar | Relative pressure (MAP - Baro), kPa÷100→bar |

#### Row 2 Parameters

| Card | PID | Label | Format | Range | Color/Flash |
|------|-----|-------|--------|-------|-------------|
| card4 | 0x320 | A/F | %.1f | 9-18 | Lambda×14.7, <11 red, 11~14.5 yellow, 14.5~15.5 green, >15.5 red + flash on throttle; shows "DFCO" during decel fuel cut |
| card5 | 0x140 | IGN | %.1f | -40~40° | — |
| card6 | 0x330 | S.TRIM | %+.1f | -25~+25% | Frozen during DFCO |
| card7 | 0x332 | L.TRIM | %+.1f | -25~+25% | Frozen during DFCO |

> **A/F Flash Exclusion**: Only flashes when throttle plate (TP) > 5%, ignoring coasting, lifting, and shifting.

### Bottom Row (59dp)

| Element | Weight | PID | Format | Color/Note |
|---------|--------|-----|--------|------------|
| K.C | weight=2 | 0x412 | %.0f | <55 green, 55~65 yellow, >65 red flash |
| CYL 1-4 | weight=1×4 | 0x421-424 | %.0f | 0 green, 1 yellow, ≥2 red |
| K.R | weight=1 | 0x410 | %.1f | Knock Retard |
| K.L | weight=1 | 0x411 | %.1f | Knock Limit |
| BAT(V) | weight=1 | 0x180 | %.1f | Battery voltage |
| F.P(bar) | weight=1 | 0x191 | %.1f | Fuel pressure, kPa÷100→bar |
| W.G(%) | weight=1 | 0x1A0 | %.0f | Wastegate |
| T.P(%) | weight=1 | 0x122 | %.0f | Throttle Plate |

### ScaleBarView

- Data bar + semi-transparent color zones + zone dividers + anchor fill + current value indicator
- **Zone expansion**: A/F green zone (14.5~15.5) visually expanded 2.5× to highlight safe range
- Auto-alignment for edge tick labels

## Data Processing Pipeline (V1.2)

### Engine State Detection

`EngineStateTracker` with hysteresis-based priority state machine, detecting 5 states:

**Priority**: DFCO > TRANSIENT > WOT > IDLE > NORMAL

| State | Detection Criteria | Hysteresis |
|-------|--------------------|------------|
| DFCO | TP<2%, Speed>15, Inj<0.5ms | Enter 200ms, Exit 300ms |
| TRANSIENT | dTP/dt>50%/s OR dRPM/dt>1200rpm/s OR dMAP/dt>300kPa/s | 80ms |
| WOT | TP>80% | Enter 100ms, Exit 200ms |
| IDLE | RPM<1000, TP<2%, Speed<3 | 300ms |
| NORMAL | Default | 100ms |

> TRANSIENT triple detection (essential for MT vehicles): rapid throttle lift / shift RPM drop / boost collapse — any trigger activates.

### Adaptive Filtering

- **EMA filter**: Per-parameter α (Ethanol 0.05, ECT/IAT 0.05, Boost 0, A/F 0.3, IGN 0.3, S.TRIM 0.4, L.TRIM 1.0)
- **Boost asymmetric filter**: Fast attack α=0.6, dynamic release (NORMAL 0.15, TRANSIENT 0.05, DFCO 0.02)
- **A/F adaptive**: WOT/TRANSIENT α=0.7 at 20Hz, NORMAL α=0.3 at 10Hz
- **IGN load gating**: Lower α when TP<3%, fast return-to-zero on throttle lift
- **Ethanol slow filter**: α=0.05, smoothing Flex Fuel sensor noise

### Range Validation

Per-parameter physical limits — out-of-range values are discarded:

| Parameter | Valid Range |
|-----------|-------------|
| Ethanol | 0~100% |
| ECT | -20~130°C |
| IAT | -20~130°C |
| Boost | -1.5~3.0 bar |
| A/F | 8~25 |
| IGN | -25~55° |
| S.TRIM | -30~30% |
| L.TRIM | -30~30% |

### Confidence System

During TRANSIENT state, A/F, S.TRIM, L.TRIM display opacity reduced to 45% to indicate potentially inaccurate readings.

### NaN Protection

NaN check before EMA filtering prevents sensor anomaly frames from contaminating filter state.

### DFCO Handling

During deceleration fuel cut-off:
- A/F displays "DFCO" in gray (Lambda reads air when injectors off)
- S.TRIM and L.TRIM frozen at last valid value

### Last-Valid Cache

When sensor data is temporarily missing (Bluetooth dropout), displays the last valid value at 40% opacity instead of "--".

### Bluetooth Auto-Reconnect

Automatic exponential backoff reconnect on disconnection (1s→2s→4s→8s). Restores data stream on success — no manual app restart needed.

## File Structure

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # Main UI, data binding, filtering, flash control
├── ScaleBarView.java          # Scale progress bar (with zone expansion)
├── ShiftLightView.java        # 6-LED shift light
└── data/
    ├── DataSource.java        # Data source interface (Callback)
    ├── BluetoothSource.java   # Bluetooth SPP (FlashPro, auto-reconnect)
    ├── EngineStateTracker.java # Engine state detection (V1.2: multi-condition transient + hysteresis)
    ├── HondataProtocol.java   # Protocol parsing + scaling formulas
    ├── SensorData.java        # PID→Double Map
    └── DemoSource.java        # Demo source with 6-phase cycling + EXTREME (V1.2)

app/src/main/res/
├── layout/
│   ├── activity_main.xml      # Main layout (LED+Header overlay + 2-row grid + bottom)
│   ├── item_sensor_card.xml   # Sensor card
│   └── item_knock_cyl.xml     # Knock cylinder card
├── drawable/
│   └── bg_card.xml            # Card background (black + 0.5dp #333 border)
└── values/
    ├── styles.xml             # NoTitleBar.Fullscreen
    └── strings.xml
```

## Technical Details

### Flash Warning System

Unified Handler manages a 1Hz flash timer, supporting:
- **ECT** >100°C purple flash
- **IAT** ≥65°C purple flash
- **A/F** red zone + throttle applied (TP>5%) red flash
- **K.C** >65% red flash

Text alpha alternates between 255↔40 during flash, restored to 1 when not flashing.

### Vertical Font Stretching

Android has no native font height scaling. Achieved via `textSize × scale` + `textScaleX = 1/scale`:
- Row 1: scale=1.2 (height +20%, width unchanged)
- Row 2: scale=1.4 (height +40%, width unchanged)

### Fullscreen

Three-layer defense for car head units:
1. Theme: `Theme.NoTitleBar.Fullscreen`
2. Code: `FLAG_FULLSCREEN` + `FLAG_KEEP_SCREEN_ON`
3. Callback: `onWindowFocusChanged()` re-apply

### Data Refresh

- Demo mode (DemoSource): 20Hz, 30-second state cycle (IDLE→NORMAL→WOT→TRANSIENT→DFCO→CRUISE)
- Bluetooth mode (BluetoothSource): FlashPro SPP real-time, 50Hz polling

## Zero Dependencies

Pure Android Framework API, no third-party libraries:
- No `androidx` / No Kotlin / No `.so` native libs
- Debug APK: **89 KB**

## Version History

### V1.2 (2026-05-21) — Data Pipeline Deep Optimization

- **TRANSIENT multi-condition detection**: dTP/dt>50%/s OR dRPM/dt>1200rpm/s OR dMAP/dt>300kPa/s, any trigger
- **State priority system**: DFCO > TRANSIENT > WOT > IDLE > NORMAL
- **Hysteresis anti-bounce**: Independent enter/exit delays per state
- **Adaptive filtering**: A/F α=0.7 at 20Hz during WOT/TRANSIENT, α=0.3 at 10Hz during NORMAL
- **Boost dynamic release**: NORMAL 0.15, TRANSIENT 0.05, DFCO 0.02
- **Range validation**: Per-parameter physical limit checks, out-of-range values discarded
- **NaN protection**: Pre-EMA check prevents sensor anomaly contamination
- **IGN load gating**: Reduced α when TP<3%, fast return-to-zero on throttle lift
- **Confidence system**: A/F, S.TRIM, L.TRIM opacity reduced to 45% during TRANSIENT
- **Ethanol slow filter**: α=0.05, smoothing Flex Fuel sensor noise
- **MAP near-zero clamp**: |value|<0.05 → 0, eliminates "-0" display
- **Shift light thresholds lowered**: 3000/3500/4000/4500/5000/5500, tuned for 1.5T powerband
- **Bluetooth auto-reconnect**: Exponential backoff (1s→2s→4s→8s), no manual restart needed
- **Read timeout**: 3-second per-frame timeout triggers reconnect
- **DemoSource EXTREME phase**: Sine sweep across all ScaleBar ranges

### V1.1 (2026-05-21) — Data Pipeline Upgrade

- MAP → Boost (relative pressure, MAP - Barometric)
- Boost range changed to -1.0~2.0 bar with 4-zone color
- Ethanol 4-level color: <E20 white, E20-40 green, E40-60 yellow, >E60 red
- Engine state detection: IDLE / NORMAL / WOT / DFCO / TRANSIENT
- DFCO handling: A/F shows "DFCO", S.TRIM/L.TRIM frozen
- EMA signal filtering with per-parameter α coefficients
- Boost asymmetric filter (fast attack/slow release)
- Rate limiting per parameter (Boost 20Hz, A/F 10Hz, temps 2Hz)
- Last-valid-value cache for Bluetooth dropouts
- MAX/MIN color-coded ± (blue positive, red negative)
- DemoSource state cycle simulation

### V1.0 (2026-05-19) — First Release

- 6-LED shift light (progressive + ≥6400 flash) + Header overlay FrameLayout
- Temp warnings: ECT 4-level + purple flash, IAT 5-level + purple flash
- A/F 4-zone color + red zone throttle flash (TP>5% excludes coasting/shifting)
- K.C 3-level color + >65% red flash
- ScaleBar zone expansion (A/F green 2.5×)
- MAP/F.P unit changed to bar
- Bottom row: K.C + CYL1-4 + K.R + K.L + BAT + F.P + W.G + T.P
- Production: removed handshake popups, errors only

---

[中文说明](README_CN.md)
