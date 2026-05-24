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

## UI Layout (V1.3)

```
┌──────────────────────────────────────────────────────────────────────┐
│            ▓▓  ▓▓  ▓▓  ▓▓  ▓▓  ▓▓          6-LED Shift Light      │ ← 42dp (LED+Header overlay)
│ Powered by Helijohnny                        Demo (Sim)   Connected │
├────────────┬────────────┬────────────┬──────────────────────────────┤
│ Ethanol(%) │ ECT(°C)    │ IAT(°C)    │ L.TRIM(%)                    │
│            │            │            │                              │
│ E85        │ 86         │ 33         │ +2.5                         │ ← Row 1: height×1.5
│  92    87  │  90    82  │  35    31  │  +1.0  4.5                   │ ← MAX/MIN
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │ ← ScaleBar+Peak
├────────────┼────────────┼────────────┼──────────────────────────────┤
│ MAP(bar)   │ A/F        │ IGN(°)     │ S.TRIM(%)                    │
│            │            │            │                              │
│ +0.15      │ 14.6       │ +22.5      │ +1.8                         │ ← Row 2: height×1.65
│  1.20 0.88 │  15.0 14.2 │  25.0 12.5 │  2.5  5.0                    │ ← MAX/MIN
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
- **Center-left**: Large value (Row 1 height×1.5, Row 2 height×1.65, width unchanged)
- **Center-right**: MAX/MIN real-time tracking (± shown by color, not symbol)
- **Bottom**: ScaleBarView scale progress bar

#### Row 1 Parameters (Slow Data)

| Card | PID | Label | Format | Range | Dynamics | Color/Flash |
|------|-----|-------|--------|-------|----------|-------------|
| card0 | 0xB03 | Ethanol | %.0f | 0-100% | STATIC | "E" prefix, <E20 white, E20-40 green, E40-60 yellow, >E60 red |
| card1 | 0x160 | ECT | %.0f | 40-120°C | THERMAL | <80 blue, 80~95 white, 96~100 red, >100 purple flash |
| card2 | 0x151 | IAT | %.0f | 20-100°C | THERMAL | <35 green, 35~44 white, 45~54 yellow, 55~64 red, ≥65 purple flash |
| card3 | 0x332 | L.TRIM | %+.1f | -25~+25% | THERMAL | Frozen during DFCO |

#### Row 2 Parameters (Fast Data)

| Card | PID | Label | Format | Range | Dynamics | Color/Flash |
|------|-----|-------|--------|-------|----------|-------------|
| card4 | 0x110 | MAP | %+.1f | -1.0~2.0 bar | MECHANICAL | Relative pressure (MAP - Baro), kPa÷100→bar |
| card5 | 0x320 | A/F | %.1f | 9-18 | TRANSIENT | Lambda×14.7, <11 red, 11~14.5 yellow, 14.5~15.5 green, >15.5 red + flash on throttle; shows "DFCO" during decel fuel cut |
| card6 | 0x140 | IGN | %+.1f | -40~40° | MECHANICAL | Shows "DFCO" during decel fuel cut |
| card7 | 0x330 | S.TRIM | %+.1f | -25~+25% | TRANSIENT | Shows "DFCO" during decel fuel cut |

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

### ScaleBarView — V3 Dynamics Archetype Engine

4 dynamics archetypes, each using a completely different mathematical model:

| Archetype | Parameters | Math Model | Visual Effect |
|-----------|-----------|------------|---------------|
| **STATIC** | Ethanol | Locked state, no energy system | Pure display, no peak hold |
| **THERMAL** | ECT, IAT, L.TRIM | Newton's Law of Cooling (bidirectional) | Fast decay at high heat, slow at low; Drift Memory Peak |
| **MECHANICAL** | Boost, IGN | Spring-Damper 2nd-order system (Euler integration) | Natural overshoot + rebound + physical residual |
| **TRANSIENT** | A/F, S.TRIM | Oscillation Envelope (bidirectional) | Expand-only, breathing envelope |

- Data bar 15dp (was 10dp in V1.2), ScaleBar total height 42dp
- Color zone backgrounds + zone dividers + anchor fill + current value indicator line
- **Zone expansion**: A/F green zone (14.5~15.5) 2.5×, Boost 0~1.5bar 2.0×
- Auto-alignment for edge tick labels
- **Peak parameters per-card**: independent decay/hold parameters for each card

### Emotion Rendering

7 emotion states with gradual follow dynamics ("felt, not distracting"):

| Emotion | Trigger | Visual |
|---------|---------|--------|
| NONE | Default | No effect |
| BUILDING | Boost rising / temp rising / accumulation | Warm orange tint |
| STABLE | Steady state | No effect |
| RELEASING | Pressure release / cooling | Cool blue tint |
| DANGER | Out of bounds (ECT>105, A/F<10.5) | Red tint |
| WARNING | Approaching danger | Yellow tint |
| PROTECTION | ECU protection (IGN<-10) | Orange tint |

- **Gradual follow**: `emotionCurrent` smoothly interpolates toward `emotionIntensity`, no sudden jumps
- Three-layer rendering: fill color blend + indicator line color shift + edge glow

## Data Processing Pipeline (V1.3)

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

- **EMA filter**: Per-parameter α (Ethanol 0.05, ECT 0.1, IAT 0.05, L.TRIM 1.0, A/F 0.3, IGN 0.4, S.TRIM 0.2)
- **Boost asymmetric filter**: Fast attack α=0.6, dynamic release (NORMAL 0.15, TRANSIENT 0.05, DFCO 0.02)
- **A/F adaptive**: WOT/TRANSIENT α=0.7 at 20Hz, NORMAL α=0.3 at 10Hz
- **IGN adaptive**: WOT/TRANSIENT 20Hz refresh
- **Ethanol slow filter**: α=0.05, smoothing Flex Fuel sensor noise

### Rate Limiting

Per-parameter display update interval:

| Parameter | Interval | Hz |
|-----------|----------|-----|
| Ethanol | 500ms | 2Hz |
| ECT | 500ms | 2Hz |
| IAT | 500ms | 2Hz |
| L.TRIM | 1000ms | 1Hz |
| Boost | 50ms | 20Hz |
| A/F | 100ms (WOT: 50ms) | 10Hz (WOT: 20Hz) |
| IGN | 100ms (WOT: 50ms) | 10Hz (WOT: 20Hz) |
| S.TRIM | 200ms | 5Hz |

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

During TRANSIENT state, A/F and S.TRIM display opacity reduced to 45% to indicate potentially inaccurate readings. L.TRIM excluded (ECU long-term learning value is inherently stable).

### NaN Protection

NaN check before EMA filtering prevents sensor anomaly frames from contaminating filter state.

### DFCO Handling

During deceleration fuel cut-off:
- A/F displays "DFCO" in gray (Lambda reads air when injectors off)
- S.TRIM and L.TRIM frozen at last valid value

### Last-Valid Cache

When sensor data is temporarily missing (Bluetooth dropout), displays the last valid value at 40% opacity instead of "--".

### Bluetooth Auto-Reconnect (V1.4 fullReset)

On disconnect, the data layer restarts from scratch while the UI stays alive (equivalent to restarting the app without closing the screen):
1. **Kill old thread** — completely stop pollThread + close Socket/Streams
2. **Wait for BT stack** — 1s delay for RFCOMM channel release (V1.3 reconnect failed because the Bluetooth stack still held the old connection)
3. **Create new thread** — fresh connection attempt from scratch (identical to app startup)
4. **Full handshake** — ignition detection → init → sensor definitions
5. **Auto-resume** — start polling automatically on success
6. **Exponential backoff** — 1s→2s→4s→8s on repeated failures

## File Structure

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # Main UI, data binding, filtering, flash control, emotion engine
├── ScaleBarView.java          # V3 Dynamics Archetype Engine (4 archetype + emotion rendering)
├── ShiftLightView.java        # 6-LED shift light
└── data/
    ├── DataSource.java        # Data source interface (Callback)
    ├── BluetoothSource.java   # Bluetooth SPP (FlashPro, V1.4 fullReset auto-reconnect)
    ├── EngineStateTracker.java # Engine state detection (V1.4: optimized hysteresis timings)
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
- Row 1 (i=0~2): scale=1.5 → textSize=112.5sp, scaleX=0.667
- Row 1 (i=0, Ethanol): scaleX=0.50 (extra narrow for "E85" prefix to fit container)
- Row 2 (i=3~7): scale=1.65 → textSize=99sp, scaleX=0.606
- Row 2 (i=6,7 IGN/S.TRIM): scaleX=0.50 (narrower for signed two-digit + decimal)

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
- Release APK: **45 KB**

## Version History

### V1.4 (2026-05-24) — Response Optimization + Bluetooth fullReset

Real-car testing identified three issues: sluggish state transitions during aggressive driving, delayed data recovery after DFCO exit, and bluetooth auto-reconnect not working at all.

#### 1. Hysteresis Optimization (EngineStateTracker.java)

V1.3 hysteresis timings caused a perceived delay of 600~1000ms during aggressive driving state transitions (e.g., DFCO→WOT when quickly getting back on throttle). The root cause was DFCO EXIT hysteresis at 300ms — when TP goes from 0% to 80%+, there's zero ambiguity, so the long hysteresis was unnecessary.

V1.4 reduces all timings based on real-car data analysis:

| Transition | V1.3 | V1.4 | Reduction | Reasoning |
|------------|------|------|-----------|-----------|
| DFCO ENTER | 200ms | **100ms** | -50% | TP<2% + Inj<0.5ms + Speed>15 triple condition is unambiguous |
| DFCO EXIT | 300ms | **50ms** | -83% | TP recovering >2% is unambiguous, 50ms is just debounce |
| WOT ENTER | 100ms | **30ms** | -70% | TP>80% is zero-ambiguity |
| WOT EXIT | 200ms | **80ms** | -60% | Moderately reduced |
| IDLE ENTER | 300ms | **200ms** | -33% | Slightly reduced |
| TRANSIENT | 80ms | **40ms** | -50% | Faster transient capture |
| DEFAULT | 100ms | **50ms** | -50% | Faster general transitions |

**Worst-case DFCO→WOT latency** reduced from ~600-1000ms to ~80-130ms (DFCO EXIT 50ms + WOT ENTER 30ms + Rate Limit bypass).

#### 2. Instant State-Change Response (MainActivity.java)

Two additional optimizations beyond hysteresis reduction:

**a) DFCO exit resets ALL filters + skips Rate Limit:**

V1.3 only reset filters for A/F/IGN/S.TRIM on DFCO exit. V1.4 extends this to Boost (card 4) as well — during DFCO, Boost asymmetric filter uses release=0.02 (extremely slow), so even after exiting DFCO the displayed Boost value was stuck. V1.4 resets the Boost filter state on DFCO exit, allowing immediate display of the actual value.

Additionally, all cards skip Rate Limit on DFCO exit (`lastUpdateTime[i] = 0`), forcing an immediate display refresh rather than waiting for the next Rate Limit interval.

**b) Before V1.4 delay chain (DFCO→WOT):**
```
t=0     Throttle applied
t=300ms DFCO EXIT hysteresis satisfied
t=400ms WOT ENTER hysteresis satisfied
t=500ms Rate Limit interval elapsed
t=600ms First real value displayed (Boost EMA climbing)
```

**After V1.4:**
```
t=0     Throttle applied
t=50ms  DFCO EXIT hysteresis satisfied, filters reset, Rate Limit bypassed
t=80ms  WOT ENTER hysteresis satisfied
t=80ms  First real value displayed immediately (no filter lag)
```

#### 3. Bluetooth fullReset Reconnect (BluetoothSource.java)

V1.3's autoReconnect failed in practice — only restarting the app restored the connection. Root cause: reconnection was attempted within the same poll thread, and the Android Bluetooth stack didn't fully release the RFCOMM channel before the new connection attempt.

V1.4 replaces the in-loop reconnect with a **fullReset** strategy:

| Step | V1.3 autoReconnect | V1.4 fullReset |
|------|--------------------|----------------|
| Thread | Same pollThread | **New thread**, old thread fully destroyed |
| BT stack | No cleanup wait | **1s wait** for RFCOMM channel release |
| Protocol | New instance | New instance (same) |
| Reconnect | Within polling loop | **Separate reset thread**, polling loop exits on disconnect |
| UI | Stays alive | Stays alive (same) |

Flow on disconnect:
1. `IOException` in polling loop → set `connected = false`
2. Notify UI `onDisconnected()`
3. Exit polling loop (V1.3 continued in same loop)
4. Trigger `fullReset()` from UI thread
5. `fullReset()`: kill old thread → close socket → **wait 1s for BT stack** → new Protocol → new connection thread
6. On success: auto-start polling from fresh state

#### 4. Modified Files

| File | Changes |
|------|---------|
| `EngineStateTracker.java` | All 7 hysteresis timings reduced (DFCO EXIT 300→50ms, WOT ENTER 100→30ms, etc.) |
| `MainActivity.java` | DFCO exit resets Boost filter + skips Rate Limit for all cards |
| `BluetoothSource.java` | fullReset reconnect (new thread, BT stack cleanup, fresh connection) |
| `build.gradle` | versionCode 3→4, versionName "1.3"→"1.4" |

### V1.3 (2026-05-22) — Dynamics Archetype Engine + UI Polish

#### 1. V3 Dynamics Archetype Engine (ScaleBarView.java full rewrite)

V1.2's simple peak hold (static PEAK_HOLD_MS + PEAK_DECAY_RATE constants) was completely replaced by 4 dynamics archetype engines, each using a fundamentally different mathematical model:

##### ARCH_STATIC — Locked State (Ethanol)
- No energy system, pure current-value display
- No peak hold, no residual
- Rationale: Ethanol changes extremely slowly (2Hz), physically no "peak memory" exists

##### ARCH_THERMAL — Newton's Law of Cooling (ECT, IAT, L.TRIM)
- Bidirectional independent tracking: `heatPos` (above anchor) + `heatNeg` (below anchor) each tracked independently
- **Direct deviation tracking**: expands only when actual deviation exceeds memory (no delta accumulation, fixing L.TRIM's heat never catching up due to extremely slow changes)
- **Newton's cooling**: `heat -= heat × coolingRate × dt` → high heat decays fast (nonlinear), low heat decays slowly
- Per-card parameters:
  - ECT: `setThermal(gain=0.5, cooling=0.3, memory=0.2)` — moderate absorption, slow dissipation
  - IAT: `setThermal(gain=0.6, cooling=0.4, memory=0.25)` — slightly faster dissipation (heat soak behavior)
  - L.TRIM: `setThermal(gain=0.2, cooling=0.1, memory=0.08)` — extremely slow (ECU long-term learning value)
- V1.2→V1.3 fix: L.TRIM negative direction had no peak hold + positive direction stuck at max → bidirectional independent + direct deviation tracking

##### ARCH_MECHANICAL — Spring-Damper 2nd-Order System (Boost/MAP, IGN)
- **Euler integration** of a 2nd-order spring-damper system, naturally producing overshoot + rebound + physical residual
- `mechDamping` = damping ratio ζ: 0=undamped oscillation, 1=critical damping (no overshoot)
- Damping coefficient = `mechDamping × 2√stiffness` (standard 2nd-order system)
- Bidirectional residual: position > curVal (positive) and position < curVal (negative) drawn separately
- Residual alpha: `α = min(1, 0.6 + |velocity| × 0.4)` — brighter when velocity is high
- Per-card parameters:
  - Boost: `setMechanical(stiffness=5, damping=0.45)` — ζ=0.45 underdamped, large overshoot with slow decay
  - IGN: `setMechanical(stiffness=2.5, damping=0.40)` — ζ=0.40 more underdamped, longer residual
- V1.2→V1.3 fix: damping formula `v -= v×d×dt×60` caused velocity reversal (damping=0.65, dt=0.05 multiplies by -0.95) → corrected to proper 2nd-order Euler integration

##### ARCH_TRANSIENT — Oscillation Envelope (A/F, S.TRIM)
- Bidirectional independent envelope: `envHigh` (above anchor) + `envLow` (below anchor) each tracked and decayed independently
- **Expand-only**: envelope extends only when deviation exceeds current envelope (fixes A/F data bouncing causing full-bar expansion)
- Exponential decay: `env × pow(decay, dt)` — frame-rate independent
- Envelope alpha: `α = min(0.7, env × 0.5)` — brighter at larger deviation
- Per-card parameters:
  - A/F: `setTransient(gain=3.0, decay=0.55)` — retains 55%/sec, half-life ~3.3s
  - S.TRIM: `setTransient(gain=2.5, decay=0.65)` — retains 65%/sec, half-life ~5.3s
- V1.2→V1.3 fix: decay values from 0.08/0.15 (92%/85% lost per second) adjusted to 0.55/0.65

##### Data Bar Thicker
- `barH`: 10dp → 15dp (×1.5), ScaleBar XML height 37dp → 42dp (+5dp)
- Card total value space reduced by 5dp → MAX/MIN naturally shift up, better display ratio

#### 2. Emotion Rendering (ScaleBarView.java + MainActivity.java)

##### 7 Emotion States
Each card sets different emotions based on parameter type and current operating conditions, with intensity gradually followed by ScaleBar internally:

| Parameter | BUILDING | STABLE | RELEASING | DANGER | WARNING | PROTECTION |
|-----------|----------|--------|-----------|--------|---------|------------|
| Ethanol | — | Default | — | — | — | — |
| ECT | dT>0.1 | <95°C | — | >105°C | 95~105°C | — |
| IAT | dT>0.1 | <50°C | dT<-0.1 | ≥65°C | 50~65°C | — |
| L.TRIM | — | \|trim\|<20 | — | — | \|trim\|>20 | — |
| Boost | val>0.3 & ↑ | 0.1~1.5 | val>0.3 & ↓ | — | — | — |
| A/F | — | 12~16 | — | <10.5 or >16+throttle | <12+throttle | — |
| IGN | — | ≥-5 | — | — | -5~-10+throttle | <-10+throttle |
| S.TRIM | \|trim\|>8+throttle | \|trim\|<8 | — | — | \|trim\|>15+throttle | — |

##### Gradual Follow
- `emotionCurrent` smoothly interpolates toward `emotionIntensity`, no sudden jumps
- Interpolation speed: `emotionSpeed = 3.0`
- Three-layer rendering:
  1. **Fill color blend**: base color + emotion color × (intensity × ratio)
  2. **Indicator line color shift**: white → emotion color × (intensity × ratio)
  3. **Edge glow**: semi-transparent rectangle overlay, extremely subtle (alpha 20~35/255)

#### 3. UI Optimization (MainActivity.java + layouts)

##### Font Scaling Upgrade
- Row 1: scale 1.2→1.5 → textSize 90sp→112.5sp (height +50%)
- Row 2: scale 1.4→1.65 → textSize 84sp→99sp (height +65%)
- Ethanol-specific scaleX=0.50: "E85" prefix needs extra character space, separately narrowed to prevent container overflow

##### Card Position Rearrangement
- Row 1: Ethanol | ECT | IAT | **L.TRIM** (moved from Row 2, slow data grouped together)
- Row 2: **MAP** | A/F | IGN | **S.TRIM** (fast data grouped together)

##### DFCO Display Optimization
- A/F(5), IGN(6), S.TRIM(7): gray "DFCO" + ScaleXSpan(0.75) compression to prevent clipping
- Reset filters on DFCO exit for immediate correct values when throttle is reapplied

##### Confidence System Simplification
- Removed L.TRIM low-opacity handling (ECU long-term learning value is inherently stable, no transient unreliability)
- Only A/F(5) and S.TRIM(7) reduce opacity to 45% during TRANSIENT state

##### Data Update Rate Refinement
- Per-parameter Rate Limit: Ethanol/ECT/IAT 2Hz, L.TRIM 1Hz, Boost 20Hz, A/F 10~20Hz, IGN 10~20Hz, S.TRIM 5Hz
- MAX/MIN tracking always updates (not limited by Rate Limit)

#### 4. Bluetooth Auto-Reconnect Rewrite (BluetoothSource.java)

V1.2 reconnect had protocol state residue issues → V1.3 changed to full rebuild strategy:

| Step | Logic |
|------|-------|
| 1. Close old connection | Fully close Socket + InputStream + OutputStream |
| 2. Create new Protocol | `new HondataProtocol()` clears all protocol state |
| 3. First attempt zero delay | Immediate connection attempt, same as app restart |
| 4. Exponential backoff | Subsequent failures: 1s → 2s → 4s → 8s |
| 5. Full handshake | Ignition detect (10 retries) → INIT → sensor definitions |
| 6. Resume polling | Automatically continues data collection on success |

Connection method: reflection ch1 → insecure SPP → standard SPP (triple fallback)

#### 5. Modified Files

| File | Changes |
|------|---------|
| `ScaleBarView.java` | Full rewrite: 4 Archetype engines + 7 Emotion rendering + barH 10→15dp + peak parameters per-instance |
| `MainActivity.java` | Font 1.5/1.65 + card rearrangement + emotion engine + DFCO optimization + confidence simplification + Rate Limit |
| `BluetoothSource.java` | Full-rebuild reconnect strategy + triple fallback connection + first-attempt zero delay + exponential backoff |
| `activity_main.xml` | Bottom row 59dp + card layout adjustments |
| `item_sensor_card.xml` | ScaleBarView height 37→42dp |
| `build.gradle` | versionCode 2→3, versionName "2.0"→"1.3" |

### V1.2 (2026-05-21) — UI Fix + Repo Cleanup

- **Boost card font fix**: Changed from row-1 large font (90sp) to row-2 smaller font (84sp) to fix decimal part being clipped
- **Repo cleanup**: Removed unused code (GaugeView/WheelView/PidRegistry), old layouts, dev screenshots, old APK
- **Trimmed colors.xml**: Kept only actually-used color resources
- **MAC sanitization**: Replaced with placeholder for privacy

### V1.1 (2026-05-21) — Data Pipeline Upgrade

- **Engine state detection**: EngineStateTracker 5-state priority machine (DFCO > TRANSIENT > WOT > IDLE > NORMAL) + hysteresis anti-bounce
- **TRANSIENT triple detection**: dTP/dt>50%/s OR dRPM/dt>1200rpm/s OR dMAP/dt>300kPa/s (essential for MT)
- **MAP → Boost** (relative pressure, MAP - Barometric), range -1.0~2.0 bar
- **Ethanol 4-level color**: <E20 white, E20-40 green, E40-60 yellow, >E60 red
- **DFCO handling**: A/F shows "DFCO", S.TRIM/L.TRIM frozen
- **EMA signal filtering**: Per-parameter α (Ethanol 0.05, ECT/IAT 0.05, A/F 0.3, IGN 0.3)
- **Adaptive filtering**: A/F WOT/TRANSIENT α=0.7+20Hz, NORMAL α=0.3+10Hz
- **Boost asymmetric filter**: Fast attack α=0.6, dynamic release (NORMAL 0.15, TRANSIENT 0.05, DFCO 0.02)
- **Range validation**: 8-parameter physical limits + NaN protection
- **IGN load gating**: Reduced α when TP<3%
- **Confidence system**: A/F/S.TRIM/L.TRIM opacity 45% during TRANSIENT
- **MAP near-zero clamp**: Eliminates "-0" display
- **Bluetooth auto-reconnect**: Exponential backoff (1s→2s→4s→8s) + 3s read timeout
- **Shift light thresholds lowered**: 3000/3500/4000/4500/5000/5500, ≥5500 flash
- **DemoSource EXTREME**: Sine sweep across all ScaleBar ranges
- **Last-valid cache**: Retains last value during Bluetooth dropout
- **MAX/MIN color-coded ±** (blue positive, red negative)

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
