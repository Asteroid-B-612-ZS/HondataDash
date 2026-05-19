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

## UI Layout (V1.0)

```
┌──────────────────────────────────────────────────────────────────────┐
│            ▓▓  ▓▓  ▓▓  ▓▓  ▓▓  ▓▓          6-LED Shift Light      │ ← 42dp (LED+Header overlay)
│ Powered by Helijohnny                        Demo (模拟)  Connected │
├────────────┬────────────┬────────────┬──────────────────────────────┤
│ Ethanol(%) │ ECT(°C)    │ IAT(°C)    │ MAP(bar)                     │
│            │            │            │                              │
│ E85        │ 86         │ 33         │ 1.15                         │ ← Row 1: integer, height×1.2
│  92    87  │  90    82  │  35    31  │  1.20  0.88                  │ ← MAX/MIN
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │ ← ScaleBar
├────────────┼────────────┼────────────┼──────────────────────────────┤
│ A/F        │ IGN(°)     │ S.TRIM(%)  │ L.TRIM(%)                    │
│            │            │            │                              │
│ 14.6       │ 22.5       │ +1.8       │ -2.5                         │ ← Row 2: decimal, height×1.4
│  15.0 14.2 │  25.0 12.5 │  +2.5 -5.0 │  +1.0 -4.5                 │ ← MAX/MIN 21sp
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │
├──────┬─┬─┬─┬─┬────┬────┬─────┬──────┬──────┬───────────────────────┤
│ K.C  │C1│C2│C3│C4│K.R │K.L │BAT(V)│F.P   │W.G  │T.P               │ ← Bottom 59dp
│  25% │ 0│ 1│ 0│ 0│ 0.5│24.0│ 13.7 │2.35  │ 45  │ 18                │
└──────┴─┴─┴─┴─┴────┴────┴─────┴──────┴──────┴───────────────────────┘
```

### 6-LED Shift Light

- 6 large LEDs with progressive RPM activation
- Thresholds: 4000 / 4500 / 5000 / 5500 / 6000 / 6200
- Colors: Green ×2 → Yellow ×2 → Red ×2
- ≥6400 RPM: all lit, 1Hz flash
- Overlaid with Header in FrameLayout to save vertical space

### 4×2 Main Data Grid

Each sensor card contains:
- **Top**: English abbreviation + unit in parentheses
- **Center-left**: Large value (Row 1 height×1.2, Row 2 height×1.4, width unchanged)
- **Center-right**: MAX/MIN real-time tracking
- **Bottom**: ScaleBarView scale progress bar

#### Row 1 Parameters

| Card | PID | Label | Format | Range | Color/Flash |
|------|-----|-------|--------|-------|-------------|
| card0 | 0xB03 | Ethanol | %.0f | 0-100% | "E" prefix, bright green |
| card1 | 0x160 | ECT | %.0f | 40-120°C | <80 blue, 80~95 white, 96~100 red, >100 purple flash |
| card2 | 0x151 | IAT | %.0f | 20-100°C | <35 green, 35~44 white, 45~54 yellow, 55~64 red, ≥65 purple flash |
| card3 | 0x110 | MAP | %.1f | -0.3~2.0 bar | kPa÷100→bar |

#### Row 2 Parameters

| Card | PID | Label | Format | Range | Color/Flash |
|------|-----|-------|--------|-------|-------------|
| card4 | 0x320 | A/F | %.1f | 9-18 | Lambda×14.7, <11 red, 11~14.5 yellow, 14.5~15.5 green, >15.5 red + flash on throttle |
| card5 | 0x140 | IGN | %.1f | -40~40° | — |
| card6 | 0x330 | S.TRIM | %+.1f | -25~+25% | — |
| card7 | 0x332 | L.TRIM | %+.1f | -25~+25% | — |

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

## File Structure

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # Main UI, data binding, flash warning control
├── ScaleBarView.java          # Scale progress bar (with zone expansion)
├── ShiftLightView.java        # 6-LED shift light
└── data/
    ├── DataSource.java        # Data source interface (Callback)
    ├── BluetoothSource.java   # Bluetooth SPP (FlashPro, no handshake popup)
    ├── HondataProtocol.java   # Protocol parsing + scaling formulas
    ├── SensorData.java        # PID→Double Map
    └── DemoSource.java        # Demo source (20Hz)

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

- Demo mode (DemoSource): 20Hz, RPM sine wave 800~6800 covering all shift light stages
- Bluetooth mode (BluetoothSource): FlashPro SPP real-time, 50Hz polling

## Zero Dependencies

Pure Android Framework API, no third-party libraries:
- No `androidx` / No Kotlin / No `.so` native libs
- Debug APK: **85 KB**

## Version History

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
