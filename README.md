# Hondata Dash — Car Dashboard / 车载仪表盘

A custom Android dashboard app that replaces the Hondata official app, designed for 800×480 landscape car head units. Connects to Hondata FlashPro via Bluetooth SPP and displays 42 sensor parameters in real time.

自定义 Android 车载仪表盘，替代 Hondata 官方 App，运行在 800×480 横屏车机上。通过蓝牙 SPP 连接 Hondata FlashPro，实时显示 42 个传感器参数。

## Requirements / 设备要求

| Item / 项目 | Spec / 规格 |
|------|------|
| OS / 系统 | Android 4.2+ (API 17) |
| Screen / 屏幕 | 800×480 landscape, 160dpi |
| Data Source / 数据源 | Hondata FlashPro Bluetooth |
| Build / 构建 | Gradle + JDK 17 |

## Build & Deploy / 构建与部署

```bash
JAVA_HOME="~/.jdks/jbr-17.0.14" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hondata.dash/.MainActivity
```

## UI Layout / 界面布局 (V1.0)

```
┌──────────────────────────────────────────────────────────────────────┐
│            ▓▓  ▓▓  ▓▓  ▓▓  ▓▓  ▓▓          6-LED Shift Light      │ ← 42dp (LED+Header overlay)
│ Powered by Helijohnny                        Demo (模拟)  已连接    │
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

### 6-LED Shift Light / 6-LED 转速灯条

- 6 large LEDs with progressive RPM activation / 6 颗大 LED，RPM 渐进点亮
- Thresholds / 阈值: 4000 / 4500 / 5000 / 5500 / 6000 / 6200
- Colors / 颜色: Green ×2 → Yellow ×2 → Red ×2 / 绿 绿 黄 黄 红 红
- ≥6400 RPM: all lit, 1Hz flash / ≥6400 RPM 全亮 1Hz 闪烁 (红色)
- Overlaid with Header in FrameLayout to save vertical space / 与 Header 叠加 FrameLayout，节省纵向空间

### 4×2 Main Data Grid / 4×2 主数据网格

Each sensor card contains / 每个传感器卡片包含:
- **Top / 顶部**: English abbreviation + unit in parentheses / 英文缩写 + 单位(括号) 并排
- **Center-left / 中央左**: Large value (Row 1 height×1.2, Row 2 height×1.4, width unchanged) / 大数值 (第1行字高×1.2, 第2行字高×1.4, 宽度不变)
- **Center-right / 中央右**: MAX/MIN real-time tracking / MAX/MIN 实时追踪
- **Bottom / 底部**: ScaleBarView scale progress bar / ScaleBarView 刻度进度条

#### Row 1 Parameters / 第1行参数

| Card | PID | Label | Format | Range | Color/Flash |
|------|-----|-------|--------|-------|-------------|
| card0 | 0xB03 | Ethanol | %.0f | 0-100% | "E" prefix, bright green / 前缀"E", 亮绿 |
| card1 | 0x160 | ECT | %.0f | 40-120°C | <80 blue, 80~95 white, 96~100 red, >100 purple flash / <80蓝 80~95白 96~100红 >100紫闪烁 |
| card2 | 0x151 | IAT | %.0f | 20-100°C | <35 green, 35~44 white, 45~54 yellow, 55~64 red, ≥65 purple flash / <35绿 35~44白 45~54黄 55~64红 ≥65紫闪烁 |
| card3 | 0x110 | MAP | %.1f | -0.3~2.0 bar | kPa÷100→bar |

#### Row 2 Parameters / 第2行参数

| Card | PID | Label | Format | Range | Color/Flash |
|------|-----|-------|--------|-------|-------------|
| card4 | 0x320 | A/F | %.1f | 9-18 | Lambda×14.7, <11 red, 11~14.5 yellow, 14.5~15.5 green, >15.5 red + flash on throttle / <11红 11~14.5黄 14.5~15.5绿 >15.5红, 红色+踩油门闪烁 |
| card5 | 0x140 | IGN | %.1f | -40~40° | — |
| card6 | 0x330 | S.TRIM | %+.1f | -25~+25% | — |
| card7 | 0x332 | L.TRIM | %+.1f | -25~+25% | — |

> **A/F Flash Exclusion / 闪烁排除**: Only flashes when throttle plate (TP) > 5%, ignoring coasting, lifting, and shifting / 仅在节气门开度(TP)>5%时闪烁，松油/滑行/换挡不触发。

### Bottom Row (59dp) / 底部行

| Element | Weight | PID | Format | Color/Note |
|---------|--------|-----|--------|------------|
| K.C | weight=2 | 0x412 | %.0f | <55 green, 55~65 yellow, >65 red flash / <55绿 55~65黄 >65红闪烁 |
| CYL 1-4 | weight=1×4 | 0x421-424 | %.0f | 0 green, 1 yellow, ≥2 red / 0绿 1黄 ≥2红 |
| K.R | weight=1 | 0x410 | %.1f | Knock Retard / 爆震延迟 |
| K.L | weight=1 | 0x411 | %.1f | Knock Limit / 爆震限值 |
| BAT(V) | weight=1 | 0x180 | %.1f | Battery voltage / 电池电压 |
| F.P(bar) | weight=1 | 0x191 | %.1f | Fuel pressure, kPa÷100→bar / 油压 |
| W.G(%) | weight=1 | 0x1A0 | %.0f | Wastegate / 废气旁通阀 |
| T.P(%) | weight=1 | 0x122 | %.0f | Throttle Plate / 节气门板 |

### ScaleBarView / 刻度进度条

- Data bar + semi-transparent color zones + zone dividers + anchor fill + current value indicator / 数据条 + 半透明颜色区间 + 区间分界线 + 锚点填充 + 当前值指示线
- **Zone expansion / 区间放大**: A/F green zone (14.5~15.5) visually expanded 2.5× to highlight safe range / A/F 绿色区间视觉放大 2.5 倍，突出安全范围
- Auto-alignment for edge tick labels / 边缘刻度文字自动对齐

## File Structure / 文件结构

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # Main UI, data binding, flash warning control / 主界面, 数据绑定, 闪烁控制
├── ScaleBarView.java          # Scale progress bar (with zone expansion) / 刻度进度条 (支持区间放大)
├── ShiftLightView.java        # 6-LED shift light / 6-LED转速灯条
└── data/
    ├── DataSource.java        # Data source interface (Callback) / 数据源接口
    ├── BluetoothSource.java   # Bluetooth SPP (FlashPro, no handshake popup) / 蓝牙SPP (无握手弹窗)
    ├── HondataProtocol.java   # Protocol parsing + scaling formulas / 协议解析+缩放公式
    ├── SensorData.java        # PID→Double Map
    └── DemoSource.java        # Demo source (20Hz) / 模拟数据源 (20Hz)

app/src/main/res/
├── layout/
│   ├── activity_main.xml      # Main layout (LED+Header overlay + 2-row grid + bottom) / 主布局
│   ├── item_sensor_card.xml   # Sensor card / 传感器卡片
│   └── item_knock_cyl.xml     # Knock cylinder card / 爆震缸子卡片
├── drawable/
│   └── bg_card.xml            # Card background (black + 0.5dp #333 border) / 卡片背景
└── values/
    ├── styles.xml             # NoTitleBar.Fullscreen
    └── strings.xml
```

## Technical Details / 技术细节

### Flash Warning System / 闪烁警告系统

Unified Handler manages a 1Hz flash timer, supporting / 统一 Handler 管理 1Hz 闪烁定时器，支持:
- **ECT** >100°C purple flash / 紫色闪烁
- **IAT** ≥65°C purple flash / 紫色闪烁
- **A/F** red zone + throttle applied (TP>5%) red flash / 红色区间 + 踩油门时红色闪烁
- **K.C** >65% red flash / 红色闪烁

Text alpha alternates between 255↔40 during flash, restored to 1 when not flashing / 闪烁时文字 alpha 在 255↔40 间交替，非闪烁状态恢复 alpha=1。

### Vertical Font Stretching / 字体纵向拉伸

Android has no native font height scaling. Achieved via `textSize × scale` + `textScaleX = 1/scale` / Android 无原生字高缩放，通过 textSize × scale + textScaleX = 1/scale 实现:
- Row 1: scale=1.2 (height +20%, width unchanged) / 第1行: 字高+20%, 宽度不变
- Row 2: scale=1.4 (height +40%, width unchanged) / 第2行: 字高+40%, 宽度不变

### Fullscreen / 全屏方案

Three-layer defense for car head units / 三层防御确保车机全屏:
1. Theme layer / 主题层: `Theme.NoTitleBar.Fullscreen`
2. Code layer / 代码层: `FLAG_FULLSCREEN` + `FLAG_KEEP_SCREEN_ON`
3. Callback layer / 回调层: `onWindowFocusChanged()` re-apply

### Data Refresh / 数据刷新

- Demo mode (DemoSource): 20Hz, RPM sine wave 800~6800 covering all shift light stages / 模拟模式: 20Hz, RPM 正弦波 800~6800 覆盖全部灯条阶段
- Bluetooth mode (BluetoothSource): FlashPro SPP real-time, 50Hz polling / 蓝牙模式: FlashPro SPP 实时, 50Hz 轮询

## Zero Dependencies / 零依赖

Pure Android Framework API, no third-party libraries / 纯 Android Framework API，无第三方库:
- No `androidx` / No Kotlin / No `.so` native libs / 无 `androidx` / 无 Kotlin / 无 `.so` 原生库
- Debug APK: **85 KB**

## Version History / 版本历史

### V1.0 (2026-05-19) — First Release / 首个上车版本

- 6-LED shift light (progressive + ≥6400 flash) + Header overlay FrameLayout / 6-LED 转速灯条 + Header 叠加
- Temp warnings: ECT 4-level + purple flash, IAT 5-level + purple flash / 温度警告: ECT 4级+紫闪, IAT 5级+紫闪
- A/F 4-zone color + red zone throttle flash (TP>5% excludes coasting/shifting) / A/F 4区变色 + 红色区间踩油门闪烁
- K.C 3-level color + >65% red flash / K.C 3级变色 + >65%红闪
- ScaleBar zone expansion (A/F green 2.5×) / ScaleBar 区间放大 (A/F绿色2.5倍)
- MAP/F.P unit changed to bar / MAP/F.P 单位改为 bar
- Bottom row: K.C + CYL1-4 + K.R + K.L + BAT + F.P + W.G + T.P / 底部行完整数据
- Production: removed handshake popups, errors only / 正式版: 移除握手弹窗, 仅保留错误提示
