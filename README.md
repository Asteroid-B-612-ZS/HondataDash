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
│ Powered by Helijohnny                        Demo (模拟)  Connected │
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

4 类动力学原型，每类使用完全不同的数学结构：

| Archetype | Parameters | Math Model | Visual Effect |
|-----------|-----------|------------|---------------|
| **STATIC** | Ethanol | 锁定态, 无能量系统 | 纯显示, 无峰值保持 |
| **THERMAL** | ECT, IAT, L.TRIM | 牛顿冷却定律 (双向独立) | 高温快散低温慢散, Drift Memory Peak |
| **MECHANICAL** | Boost, IGN | Spring-Damper 二阶系统 (Euler积分) | 自然过冲+回弹+物理残影 |
| **TRANSIENT** | A/F, S.TRIM | Oscillation Envelope (双向独立) | 只扩展不收缩, 呼吸包络 |

- Data bar 15dp (V1.2 为 10dp), ScaleBar 总高 42dp
- 颜色区间背景 + 区间分割线 + anchor 填充 + 当前值指示线
- **Zone expansion**: A/F green zone (14.5~15.5) 2.5×, Boost 0~1.5bar 2.0×
- Auto-alignment for edge tick labels
- **Peak parameters per-card**: 每个卡片独立的衰减/保持参数

### Emotion Rendering (情绪渲染)

7 种情绪状态，渐变跟随动力学状态 ("感受到，但不打扰"):

| Emotion | Trigger | Visual |
|---------|---------|--------|
| NONE | Default | 无效果 |
| BUILDING | 增压/温度上升/积累 | 微暖橙色偏移 |
| STABLE | 稳态 | 无效果 |
| RELEASING | 泄放/降温 | 微冷蓝色偏移 |
| DANGER | 超限 (ECT>105, A/F<10.5) | 微红色偏移 |
| WARNING | 接近危险 | 微黄色偏移 |
| PROTECTION | ECU保护 (IGN<-10) | 微橙色偏移 |

- **Gradual follow**: `emotionCurrent` 平滑渐变趋向 `emotionIntensity`, 不突变
- 三层渲染: 填充色混合 + 指示线变色 + 边缘微弱发光

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

### Bluetooth Auto-Reconnect (V1.3 Full Rebuild)

Full rebuild strategy (identical to restarting the app):
1. Close old socket completely (prevent Bluetooth stack residue)
2. Create new `HondataProtocol` instance (clear all protocol state)
3. First reconnect attempt: zero delay (same as app restart)
4. Subsequent failures: exponential backoff (1s→2s→4s→8s)
5. Full handshake sequence: ignition detection (10 retries) → init → sensor definitions
6. Automatic resume of data polling on success

## File Structure

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # Main UI, data binding, filtering, flash control, emotion engine
├── ScaleBarView.java          # V3 Dynamics Archetype Engine (4 archetype + emotion rendering)
├── ShiftLightView.java        # 6-LED shift light
└── data/
    ├── DataSource.java        # Data source interface (Callback)
    ├── BluetoothSource.java   # Bluetooth SPP (FlashPro, V1.3 full-rebuild auto-reconnect)
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

### V1.3 (2026-05-22) — Dynamics Archetype Engine + UI Polish

#### 1. V3 Dynamics Archetype Engine (`ScaleBarView.java` 全面重写)

V1.2 的简单峰值保持 (PEAK_HOLD_MS + PEAK_DECAY_RATE 静态常量) 被 4 类动力学原型引擎完全替代，每类使用完全不同的数学结构：

##### ARCH_STATIC — 锁定态 (Ethanol)
- 无能量系统，纯显示当前值
- 无峰值保持，无残影
- 适用场景: Ethanol 变化极慢 (2Hz)，物理上不存在"峰值记忆"

##### ARCH_THERMAL — 牛顿冷却定律 (ECT, IAT, L.TRIM)
- 双向独立追踪: `heatPos` (anchor 上方) + `heatNeg` (anchor 下方) 各自独立
- **直接追踪偏差**: 当实际偏差超过记忆时扩展 (不累积 delta，解决 L.TRIM 因变化极慢导致 heat 永远追不上实际值的问题)
- **牛顿散热**: `heat -= heat × coolingRate × dt` → 高热量快散 (非线性)，低热量慢散
- 每卡片独立参数:
  - ECT: `setThermal(gain=0.5, cooling=0.3, memory=0.2)` — 中等吸热，慢散热
  - IAT: `setThermal(gain=0.6, cooling=0.4, memory=0.25)` — 略快散热 (热浸特性)
  - L.TRIM: `setThermal(gain=0.2, cooling=0.1, memory=0.08)` — 极慢 (ECU 学习值)
- V1.2→V1.3 修复: L.TRIM 负方向没有峰值保持 + 正方向永远最大 → 双向独立 + 直接偏差追踪

##### ARCH_MECHANICAL — Spring-Damper 二阶系统 (Boost/MAP, IGN)
- **Euler 积分**的二阶弹簧-阻尼系统，自然产生过冲 (overshoot) + 回弹 (rebound) + 物理残影 (residual)
- `mechDamping` = 阻尼比 ζ: 0=无阻尼振荡, 1=临界阻尼无过冲
- 阻尼系数 = `mechDamping × 2√stiffness` (标准二阶系统)
- 双向残影: position > curVal (正方向) 和 position < curVal (负方向) 各自绘制
- 残影透明度: `α = min(1, 0.6 + |velocity| × 0.4)` — 速度大时更亮
- 每卡片独立参数:
  - Boost: `setMechanical(stiffness=5, damping=0.45)` — ζ=0.45 欠阻尼，大过冲慢衰减
  - IGN: `setMechanical(stiffness=2.5, damping=0.40)` — ζ=0.40 更欠阻尼，更长残影
- V1.2→V1.3 修复: 阻尼公式 `v -= v×d×dt×60` 导致速度反向 (damping=0.65, dt=0.05 时乘以 -0.95) → 改为正确的二阶系统 Euler 积分

##### ARCH_TRANSIENT — Oscillation Envelope (A/F, S.TRIM)
- 双向独立包络: `envHigh` (anchor 上方) + `envLow` (anchor 下方) 各自追踪和衰减
- **只扩展不收缩**: 只有偏差超过当前包络才扩展 (解决 A/F 数据反弹时包络直接拉满的问题)
- 指数衰减: `env × pow(decay, dt)` — 帧率无关
- 包络透明度: `α = min(0.7, env × 0.5)` — 偏差大时更亮
- 每卡片独立参数:
  - A/F: `setTransient(gain=3.0, decay=0.55)` — 每秒保留 55%，半衰期 ~3.3s
  - S.TRIM: `setTransient(gain=2.5, decay=0.65)` — 每秒保留 65%，半衰期 ~5.3s
- V1.2→V1.3 修复: decay 值从 0.08/0.15 (92%/85% 每秒消失) 调整到 0.55/0.65

##### 数据条加粗
- `barH`: 10dp → 15dp (×1.5)，ScaleBar XML 高度 37dp → 42dp (+5dp)
- 卡片总值域减少 5dp → MAX/MIN 自然上移，显示比例更佳

#### 2. Emotion Rendering 情绪渲染 (`ScaleBarView.java` + `MainActivity.java`)

##### 7 种情绪状态
每张卡片根据参数类型和当前工况设置不同的情绪，强度由 ScaleBar 内部渐变跟随：

| 参数 | BUILDING | STABLE | RELEASING | DANGER | WARNING | PROTECTION |
|------|----------|--------|-----------|--------|---------|------------|
| Ethanol | — | 默认 | — | — | — | — |
| ECT | dT>0.1 | <95°C | — | >105°C | 95~105°C | — |
| IAT | dT>0.1 | <50°C | dT<-0.1 | ≥65°C | 50~65°C | — |
| L.TRIM | — | \|trim\|<20 | — | — | \|trim\|>20 | — |
| Boost | val>0.3 & ↑ | 0.1~1.5 | val>0.3 & ↓ | — | — | — |
| A/F | — | 12~16 | — | <10.5 or >16+throttle | <12+throttle | — |
| IGN | — | ≥-5 | — | — | -5~-10+throttle | <-10+throttle |
| S.TRIM | \|trim\|>8+throttle | \|trim\|<8 | — | — | \|trim\|>15+throttle | — |

##### 渐变跟随
- `emotionCurrent` 平滑渐变趋向 `emotionIntensity`，不突变
- 渐变速度: `emotionSpeed = 3.0`
- 三层渲染:
  1. **填充色混合**: 基础色 + 情绪色 × (intensity × 比例)
  2. **指示线变色**: 白色 → 情绪色 × (intensity × 比例)
  3. **边缘发光**: 矩形半透明覆盖，极微弱 (alpha 20~35/255)

#### 3. UI 优化 (`MainActivity.java` + 布局)

##### 字体缩放升级
- Row 1: scale 1.2→1.5 → textSize 90sp→112.5sp (高度 +50%)
- Row 2: scale 1.4→1.65 → textSize 84sp→99sp (高度 +65%)
- Ethanol 专属 scaleX=0.50: "E85" 前缀需额外字符空间，单独压窄避免容器溢出

##### 卡片位置重排
- Row 1: Ethanol | ECT | IAT | **L.TRIM** (从 Row 2 移到 Row 1，慢数据归组)
- Row 2: **MAP** | A/F | IGN | **S.TRIM** (快数据归组)

##### DFCO 显示优化
- A/F(5), IGN(6), S.TRIM(7): 灰色 "DFCO" + ScaleXSpan(0.75) 压缩防截断
- 退出 DFCO 时重置滤波器，踩油门即时恢复正确值

##### 置信度系统精简
- 移除 L.TRIM 的低透明度处理 (ECU 长期学习值本身极稳定，不存在瞬态不可信)
- 仅保留 A/F(5) 和 S.TRIM(7) 在 TRANSIENT 时降低透明度到 45%

##### 数据更新频率细化
- 各参数独立 Rate Limit: Ethanol/ECT/IAT 2Hz, L.TRIM 1Hz, Boost 20Hz, A/F 10~20Hz, IGN 10~20Hz, S.TRIM 5Hz
- MAX/MIN 追踪始终更新 (不受 Rate Limit 限制)

#### 4. 蓝牙自动重连重写 (`BluetoothSource.java`)

V1.2 的重连存在协议状态残留问题 → V1.3 改为完全重建策略:

| 步骤 | 逻辑 |
|------|------|
| 1. 关闭旧连接 | 彻底关闭 Socket + InputStream + OutputStream |
| 2. 新建 Protocol | `new HondataProtocol()` 清零所有协议状态 |
| 3. 首次无延迟 | 和重启 App 一样立即尝试连接 |
| 4. 指数退避 | 后续失败: 1s → 2s → 4s → 8s |
| 5. 完整握手 | 点火检测(10次) → INIT → 传感器定义 |
| 6. 恢复轮询 | 成功后自动继续数据采集 |

连接方式: 反射 ch1 → 不安全 SPP → 标准 SPP (三重容错)

#### 5. 修改文件清单

| File | 变更 |
|------|------|
| `ScaleBarView.java` | 全面重写: 4 Archetype 引擎 + 7 Emotion 渲染 + barH 10→15dp + 峰值参数实例化 |
| `MainActivity.java` | 字体 1.5/1.65 + 卡片重排 + 情绪引擎 + DFCO 优化 + 置信度精简 + Rate Limit |
| `BluetoothSource.java` | 重连策略完全重建 + 三重容错连接 + 首次零延迟 + 指数退避 |
| `activity_main.xml` | 底部行 59dp + 卡片布局调整 |
| `item_sensor_card.xml` | ScaleBarView 高度 37→42dp |
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
