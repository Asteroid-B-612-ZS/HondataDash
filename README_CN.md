# Hondata Dash — 车载仪表盘

自定义 Android 车载仪表盘，替代 Hondata 官方 App，运行在 800×480 横屏车机上。通过蓝牙 SPP 连接 Hondata FlashPro，实时显示 42 个传感器参数。

## 设备要求

| 项目 | 规格 |
|------|------|
| 系统 | Android 4.2+ (API 17) |
| 屏幕 | 800×480 横屏, 160dpi |
| 数据源 | Hondata FlashPro 蓝牙版 |
| 构建 | Gradle + JDK 17 |

## 构建与部署

```bash
JAVA_HOME="~/.jdks/jbr-17.0.14" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hondata.dash/.MainActivity
```

## 界面布局 (V1.0)

```
┌──────────────────────────────────────────────────────────────────────┐
│            ▓▓  ▓▓  ▓▓  ▓▓  ▓▓  ▓▓          6-LED 转速灯条         │ ← 42dp (LED+Header叠加)
│ Powered by Helijohnny                        Demo (模拟)  已连接    │
├────────────┬────────────┬────────────┬──────────────────────────────┤
│ Ethanol(%) │ ECT(°C)    │ IAT(°C)    │ MAP(bar)                     │
│            │            │            │                              │
│ E85        │ 86         │ 33         │ 1.15                         │ ← 第1行: 整数, 字高×1.2
│  92    87  │  90    82  │  35    31  │  1.20  0.88                  │ ← MAX/MIN
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │ ← ScaleBar
├────────────┼────────────┼────────────┼──────────────────────────────┤
│ A/F        │ IGN(°)     │ S.TRIM(%)  │ L.TRIM(%)                    │
│            │            │            │                              │
│ 14.6       │ 22.5       │ +1.8       │ -2.5                         │ ← 第2行: 小数, 字高×1.4
│  15.0 14.2 │  25.0 12.5 │  +2.5 -5.0 │  +1.0 -4.5                 │ ← MAX/MIN 21sp
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │
├──────┬─┬─┬─┬─┬────┬────┬─────┬──────┬──────┬───────────────────────┤
│ K.C  │C1│C2│C3│C4│K.R │K.L │BAT(V)│F.P   │W.G  │T.P               │ ← 底部 59dp
│  25% │ 0│ 1│ 0│ 0│ 0.5│24.0│ 13.7 │2.35  │ 45  │ 18                │
└──────┴─┴─┴─┴─┴────┴────┴─────┴──────┴──────┴───────────────────────┘
```

### 6-LED 转速灯条

- 6 颗大 LED，RPM 渐进点亮
- 阈值: 4000 / 4500 / 5000 / 5500 / 6000 / 6200
- 颜色: 绿 绿 黄 黄 红 红
- ≥6400 RPM 全亮 1Hz 闪烁
- 与 Header 叠加 FrameLayout，节省纵向空间

### 4×2 主数据网格

每个传感器卡片包含:
- **顶部**: 英文缩写 + 单位(括号) 并排
- **中央左**: 大数值 (第1行字高×1.2, 第2行字高×1.4, 宽度不变)
- **中央右**: MAX/MIN 实时追踪
- **底部**: ScaleBarView 刻度进度条

#### 第1行参数

| 卡片 | PID | 缩写 | 格式 | 范围 | 颜色/闪烁 |
|------|-----|------|------|------|----------|
| card0 | 0xB03 | Ethanol | %.0f | 0-100% | 前缀"E", 亮绿 |
| card1 | 0x160 | ECT | %.0f | 40-120°C | <80蓝 80~95白 96~100红 >100紫闪烁 |
| card2 | 0x151 | IAT | %.0f | 20-100°C | <35绿 35~44白 45~54黄 55~64红 ≥65紫闪烁 |
| card3 | 0x110 | MAP | %.1f | -0.3~2.0 bar | kPa÷100→bar |

#### 第2行参数

| 卡片 | PID | 缩写 | 格式 | 范围 | 颜色/闪烁 |
|------|-----|------|------|------|----------|
| card4 | 0x320 | A/F | %.1f | 9-18 | Lambda×14.7, <11红 11~14.5黄 14.5~15.5绿 >15.5红, 红色+踩油门闪烁 |
| card5 | 0x140 | IGN | %.1f | -40~40° | — |
| card6 | 0x330 | S.TRIM | %+.1f | -25~+25% | — |
| card7 | 0x332 | L.TRIM | %+.1f | -25~+25% | — |

> **A/F 闪烁排除**: 仅在节气门开度(TP)>5%时闪烁，松油/滑行/换挡不触发。

### 底部行 (59dp)

| 元素 | 权重 | PID | 格式 | 颜色/备注 |
|------|------|-----|------|----------|
| K.C | weight=2 | 0x412 | %.0f | <55绿 55~65黄 >65红闪烁 |
| CYL 1-4 | weight=1×4 | 0x421-424 | %.0f | 0绿 1黄 ≥2红 |
| K.R | weight=1 | 0x410 | %.1f | 爆震延迟 |
| K.L | weight=1 | 0x411 | %.1f | 爆震限值 |
| BAT(V) | weight=1 | 0x180 | %.1f | 电池电压 |
| F.P(bar) | weight=1 | 0x191 | %.1f | 油压, kPa÷100→bar |
| W.G(%) | weight=1 | 0x1A0 | %.0f | 废气旁通阀 |
| T.P(%) | weight=1 | 0x122 | %.0f | 节气门板 |

### ScaleBarView 刻度进度条

- 数据条 + 半透明颜色区间 + 区间分界线 + 锚点填充 + 当前值指示线
- **区间放大**: A/F 绿色区间(14.5~15.5)视觉放大 2.5 倍，突出安全范围
- 边缘刻度文字自动对齐

## 文件结构

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # 主界面, 数据绑定, 闪烁警告控制
├── ScaleBarView.java          # 刻度进度条 (支持区间放大)
├── ShiftLightView.java        # 6-LED 转速灯条
└── data/
    ├── DataSource.java        # 数据源接口 (Callback)
    ├── BluetoothSource.java   # 蓝牙SPP (FlashPro, 无握手弹窗)
    ├── HondataProtocol.java   # Hondata 协议解析+缩放公式
    ├── SensorData.java        # PID→Double Map
    └── DemoSource.java        # 模拟数据源 (20Hz)

app/src/main/res/
├── layout/
│   ├── activity_main.xml      # 主布局 (LED+Header叠加 + 2行网格 + 底部行)
│   ├── item_sensor_card.xml   # 传感器卡片
│   └── item_knock_cyl.xml     # 爆震缸子卡片
├── drawable/
│   └── bg_card.xml            # 卡片背景 (纯黑 + 0.5dp #333线框)
└── values/
    ├── styles.xml             # NoTitleBar.Fullscreen
    └── strings.xml
```

## 技术细节

### 闪烁警告系统

统一 Handler 管理 1Hz 闪烁定时器，支持:
- **ECT** >100°C 紫色闪烁
- **IAT** ≥65°C 紫色闪烁
- **A/F** 红色区间 + 踩油门(TP>5%) 红色闪烁
- **K.C** >65% 红色闪烁

闪烁时文字 alpha 在 255↔40 间交替，非闪烁状态恢复 alpha=1。

### 字体纵向拉伸

Android 无原生字高缩放，通过 `textSize × scale` + `textScaleX = 1/scale` 实现:
- 第1行: scale=1.2 (字高+20%, 宽度不变)
- 第2行: scale=1.4 (字高+40%, 宽度不变)

### 全屏方案

三层防御确保车机全屏:
1. 主题层: `Theme.NoTitleBar.Fullscreen`
2. 代码层: `FLAG_FULLSCREEN` + `FLAG_KEEP_SCREEN_ON`
3. 回调层: `onWindowFocusChanged()` 重置

### 数据刷新

- 模拟模式 (DemoSource): 20Hz, RPM 正弦波 800~6800 覆盖全部灯条阶段
- 蓝牙模式 (BluetoothSource): FlashPro SPP 实时, 50Hz 轮询

## 零依赖

纯 Android Framework API，无第三方库:
- 无 `androidx` / 无 Kotlin / 无 `.so` 原生库
- Debug APK: **85 KB**

## 版本历史

### V1.0 (2026-05-19) — 首个上车版本

- 6-LED 转速灯条 (渐进+≥6400闪烁) + Header 叠加 FrameLayout
- 温度警告: ECT 4级+紫闪, IAT 5级+紫闪
- A/F 4区变色 + 红色区间踩油门闪烁 (TP>5%排除滑行/换挡)
- K.C 3级变色 + >65%红闪
- ScaleBar 区间放大 (A/F绿色2.5倍)
- MAP/F.P 单位改为 bar
- 底部行: K.C + CYL1-4 + K.R + K.L + BAT + F.P + W.G + T.P
- 正式版: 移除握手弹窗, 仅保留错误提示

---

[English](README.md)
