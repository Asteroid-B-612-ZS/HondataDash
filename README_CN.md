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

## 界面布局 (V1.3)

```
┌──────────────────────────────────────────────────────────────────────┐
│            ▓▓  ▓▓  ▓▓  ▓▓  ▓▓  ▓▓          6-LED 转速灯条         │ ← 42dp (LED+Header叠加)
│ Powered by Helijohnny                        Demo (模拟)  已连接    │
├────────────┬────────────┬────────────┬──────────────────────────────┤
│ Ethanol(%) │ ECT(°C)    │ IAT(°C)    │ L.TRIM(%)                    │
│            │            │            │                              │
│ E85        │ 86         │ 33         │ +2.5                         │ ← 第1行: 字高×1.5
│  92    87  │  90    82  │  35    31  │  +1.0  4.5                   │ ← MAX/MIN
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │ ← ScaleBar+峰值
├────────────┼────────────┼────────────┼──────────────────────────────┤
│ MAP(bar)   │ A/F        │ IGN(°)     │ S.TRIM(%)                    │
│            │            │            │                              │
│ +0.15      │ 14.6       │ +22.5      │ +1.8                         │ ← 第2行: 字高×1.65
│  1.20 0.88 │  15.0 14.2 │  25.0 12.5 │  2.5  5.0                    │ ← MAX/MIN
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                    │
├──────┬─┬─┬─┬─┬────┬────┬─────┬──────┬──────┬───────────────────────┤
│ K.C  │C1│C2│C3│C4│K.R │K.L │BAT(V)│F.P   │W.G  │T.P               │ ← 底部 59dp
│  25% │ 0│ 1│ 0│ 0│ 0.5│24.0│ 13.7 │2.35  │ 45  │ 18                │
└──────┴─┴─┴─┴─┴────┴────┴─────┴──────┴──────┴───────────────────────┘
```

### 6-LED 转速灯条

- 6 颗大 LED，RPM 渐进点亮
- 阈值: 3000 / 3500 / 4000 / 4500 / 5000 / 5500
- 颜色: 绿 绿 黄 黄 红 红
- ≥6400 RPM 全亮 10Hz 闪烁
- 阈值下移: 3000/3500/4000/4500/5000/5500 (适配 1.5T 动力区间)
- 与 Header 叠加 FrameLayout，节省纵向空间

### 4×2 主数据网格

每个传感器卡片包含:
- **顶部**: 英文缩写 + 单位(括号) 并排
- **中央左**: 大数值 (第1行字高×1.5, 第2行字高×1.65, 宽度不变)
- **中央右**: MAX/MIN 实时追踪 (用颜色区分正负，蓝色正值，红色负值)
- **底部**: ScaleBarView 刻度进度条

#### 第1行参数 (慢数据)

| 卡片 | PID | 缩写 | 格式 | 范围 | 动力学 | 颜色/闪烁 |
|------|-----|------|------|------|--------|----------|
| card0 | 0xB03 | Ethanol | %.0f | 0-100% | STATIC | 前缀"E", <E20白 E20-40绿 E40-60黄 >E60红 |
| card1 | 0x160 | ECT | %.0f | 40-120°C | THERMAL | <80蓝 80~95白 96~100红 >100紫闪烁 |
| card2 | 0x151 | IAT | %.0f | 20-100°C | THERMAL | <35绿 35~44白 45~54黄 55~64红 ≥65紫闪烁 |
| card3 | 0x332 | L.TRIM | %+.1f | -25~+25% | THERMAL | DFCO时冻结 |

#### 第2行参数 (快数据)

| 卡片 | PID | 缩写 | 格式 | 范围 | 动力学 | 颜色/闪烁 |
|------|-----|------|------|------|--------|----------|
| card4 | 0x110 | MAP | %+.1f | -1.0~2.0 bar | MECHANICAL | 相对压力 (MAP-大气压), kPa÷100→bar |
| card5 | 0x320 | A/F | %.1f | 9-18 | TRANSIENT | Lambda×14.7, <11红 11~14.5黄 14.5~15.5绿 >15.5红, 红色+踩油门闪烁; DFCO时显示"DFCO" |
| card6 | 0x140 | IGN | %+.1f | -40~40° | MECHANICAL | DFCO时显示"DFCO" |
| card7 | 0x330 | S.TRIM | %+.1f | -25~+25% | TRANSIENT | DFCO时显示"DFCO" |

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

### ScaleBarView — V3 动力学原型引擎

4 类动力学原型，每类使用完全不同的数学结构：

| 原型 | 参数 | 数学模型 | 视觉效果 |
|------|------|---------|---------|
| **STATIC** | Ethanol | 锁定态, 无能量系统 | 纯显示, 无峰值保持 |
| **THERMAL** | ECT, IAT, L.TRIM | 牛顿冷却定律 (双向独立) | 高温快散低温慢散, Drift Memory Peak |
| **MECHANICAL** | Boost/MAP, IGN | Spring-Damper 二阶系统 (Euler积分) + 峰值保持 | 自然过冲+回弹+物理残影+双向峰值追踪 |
| **TRANSIENT** | A/F, S.TRIM | Oscillation Envelope (双向独立) | 只扩展不收缩, 呼吸包络 |

- 数据条 15dp (V1.2 为 10dp), ScaleBar 总高 42dp
- 颜色区间背景 + 区间分割线 + 锚点填充 + 当前值指示线
- **区间放大**: A/F 绿色区间(14.5~15.5) 2.5×, Boost 0~1.5bar 2.0×
- 边缘刻度文字自动对齐
- **峰值参数按卡片独立**: 每个卡片独立的衰减/保持参数

### 情绪渲染

7 种情绪状态，渐变跟随动力学状态 ("感受到，但不打扰"):

| 情绪 | 触发 | 视觉 |
|------|------|------|
| NONE | 默认 | 无效果 |
| BUILDING | 增压/温度上升/积累 | 微暖橙色偏移 |
| STABLE | 稳态 | 无效果 |
| RELEASING | 泄放/降温 | 微冷蓝色偏移 |
| DANGER | 超限 (ECT>105, A/F<10.5) | 微红色偏移 |
| WARNING | 接近危险 | 微黄色偏移 |
| PROTECTION | ECU保护 (IGN<-10) | 微橙色偏移 |

- **渐变跟随**: `emotionCurrent` 平滑趋向 `emotionIntensity`, 不突变
- 三层渲染: 填充色混合 + 指示线变色 + 边缘微弱发光

## 数据处理管线 (V2.0)

### 引擎状态检测 — 三维语义状态模型

`EngineStateTracker` 返回 `EngineSemanticState`，包含四个正交维度：

- **MainState** (ECU 策略层，互斥): DFCO / WOT / WARMUP / IDLE / NORMAL
- **SubState** (工况细节): WOT→SPOOL/PEAK/HOLD, DFCO→ENTER/HOLD, 或 NONE
- **Modifier** (动态修饰，正交叠加于任意 MainState): TIP_IN / TIP_OUT / BOOST_SURGE / RPM_DIP / NONE
- **Confidence** (状态可信度 0.0~1.0): 加权计算 + 低通滤波 (α=0.1, ~200ms 惯性)

#### MainState 检测

| 状态 | 判定条件 | 滞回时间 |
|------|---------|---------|
| DFCO | TP<2%, 车速>15, 喷油<0.5ms, **RPM>1400** | 进入100ms, 退出50ms |
| WOT | **ClosedLoop OFF + TargetLambda<0.95 + MAP>120kPa + RPM>1500** | 候选30ms, 维持80ms |
| WARMUP | ECT<65°C 进入, ECT>72°C + ClosedLoop ON 5s 退出 | 滞回 7°C |
| IDLE | RPM<1000, TP<2%, 车速<3 | 200ms |
| NORMAL | 默认 | 50ms |

> **WOT 重新定义 (V2.0)**: L15B7 扭矩模型 ECU 下，节气门位置 ≠ 发动机负荷。WOT 改为基于 ECU 策略信号：Open Loop (ClosedLoop OFF) + 富油命令 (TargetLambda<0.95) + 涡轮增压建立 (MAP>120kPa) + RPM 高于阈值。无论节气门位置如何都能正确识别 WOT。

> **WARMUP**: 冷启动检测，ECT 滞回防抖。防止暖机期间的类 WOT 信号被误判。

> **DFCO 四重锁定**: 新增 RPM>1400 条件，防止怠速恢复边缘误触发。

#### SubState 检测

| MainState | SubState | 条件 |
|-----------|----------|------|
| WOT | SPOOL | dMAP/dt > 100 kPa/s (增压建立中) |
| WOT | PEAK | WOT 持续 < 2s |
| WOT | HOLD | WOT 持续 ≥ 2s |
| DFCO | ENTER | DFCO 持续 < 200ms |
| DFCO | HOLD | DFCO 持续 ≥ 200ms |

#### Modifier 检测（优先级顺序）

| 优先级 | Modifier | 条件 |
|--------|----------|------|
| 1 | RPM_DIP | dRPM/dt < -1200 rpm/s |
| 2 | BOOST_SURGE | dMAP/dt > 300 kPa/s |
| 3 | TIP_OUT | dTP/dt < -50%/s |
| 4 | TIP_IN | dTP/dt > 50%/s |

> Modifier 是正交的 — 可叠加于任意 MainState，提供瞬态上下文而不干扰状态机流程。这替代了旧的 TRANSIENT 主状态（旧方案中 TRANSIENT 会劫持 WOT 检测）。

### 自适应滤波

- **EMA 滤波**: 各参数独立 α 系数 (Ethanol 0.05, ECT 0.1, IAT 0.05, L.TRIM 1.0, A/F 0.3, IGN 0.4, S.TRIM 0.2)
- **Boost 不对称滤波**: 增压快攻击 α=0.6，泄压动态释放（NORMAL 0.15, Modifier 0.05, DFCO 0.02）
- **A/F 自适应**: WOT/TRANSIENT 时 α=0.7 且 20Hz 刷新，NORMAL 时 α=0.3 且 10Hz
- **IGN 自适应**: WOT/TRANSIENT 时 20Hz 刷新
- **Ethanol 慢滤波**: α=0.05，过滤 Flex Fuel 传感器噪声

### 范围验证

每个参数独立的物理极限检查，超范围值直接丢弃：

| 参数 | 有效范围 |
|------|---------|
| Ethanol | 0~100% |
| ECT | -20~130°C |
| IAT | -20~130°C |
| Boost | -1.5~3.0 bar |
| A/F | 8~25 |
| IGN | -25~55° |
| S.TRIM | -30~30% |
| L.TRIM | -30~30% |

### 置信度系统

各状态加权置信度计算 + 低通滤波 (α=0.1, ~200ms 惯性)。驱动：

| 输出 | 公式 | 效果 |
|------|------|------|
| A/F alpha | `0.3 + confidence × 0.4` | 基于状态可信度的连续调制 (0.3~0.7) |
| 文字透明度 | `0.45 + 0.55 × confidence` | 敏感卡片 (A/F, IGN, S.TRIM) 在不确定时渐隐 |
| Boost 释放率 | `0.02 / 0.05 / 0.15` | 三级自动切换，基于 MainState + Modifier |

WOT 置信度权重: ClosedLoop 0.35 + TargetLambda 0.25 + MAP 0.25 + RPM 0.15

### NaN 保护

EMA 滤波前检查 NaN，防止传感器异常帧污染滤波状态。

### DFCO 处理

减速断油期间：
- A/F、IGN、S.TRIM 显示灰色 "DFCO"，退出过渡完全同步（同步进入/退出，相同 alpha 恢复）
- L.TRIM 冻结在最后有效值（变化极慢，无需特殊处理）

### Last-Valid 缓存

蓝牙丢包时保留最后有效值，以 40% 透明度显示，而非直接显示 "--"。

### 蓝牙自动重连 (V1.4 fullReset)

断线后数据层完全重启，UI 不退出 (等同重启 App 的数据层而不关闭界面):
1. **销毁旧线程** — 完全停止 pollThread + 关闭 Socket/Stream
2. **等待蓝牙栈** — 1s 等待 RFCOMM channel 释放 (V1.3 失败的原因：蓝牙栈仍持有旧连接)
3. **新建线程** — 从零连接 (等同 App 启动流程)
4. **完整握手** — 点火检测 → INIT → 传感器定义
5. **自动恢复** — 成功后自动开始数据轮询
6. **指数退避** — 反复失败时 1s→2s→4s→8s

## 文件结构

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # 主界面, 数据绑定, 滤波, 闪烁控制, 情绪引擎
├── ScaleBarView.java          # V3 动力学原型引擎 (4原型+情绪渲染+峰值保持)
├── ShiftLightView.java        # 6-LED 转速灯条
└── data/
    ├── DataSource.java        # 数据源接口 (Callback)
    ├── BluetoothSource.java   # 蓝牙SPP (FlashPro, V1.4 fullReset 自动重连)
    ├── EngineSemanticState.java # V2.0 三维语义状态 (Main+Sub+Modifier+Confidence)
    ├── EngineStateTracker.java # 引擎状态检测 (V2.0: ECU 语义模型)
    ├── HondataProtocol.java   # Hondata 协议解析+缩放公式
    ├── SensorData.java        # PID→Double Map
    └── DemoSource.java        # 演示数据源 — 真实 LOG 回放 (V2.1) + EXTREME 扫描

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

Android 无原生字高缩放。V2.4.2 使用自适应宽度缩放:
- **第1行** (Ethanol, ECT, IAT, L.TRIM): textSize=112sp, textScaleX 根据实测文本宽度动态适配
- **第2行** (MAP, A/F, IGN, S.TRIM): textSize=102sp, textScaleX 根据实测文本宽度动态适配
- **每卡最小 scaleX**: Ethanol 0.40, ECT/IAT 0.42, L.TRIM 0.34, MAP/A.F 0.38, IGN/S.TRIM 0.30
- **滞后防抖**: 缩窄立即生效，放宽需 gap > 0.06（防止宽度跳动）
- **布局权重**: valueArea 4.0 (80%), extremePanel 1.0 (20%) — 最大化主值显示空间

### 全屏方案

三层防御确保车机全屏:
1. 主题层: `Theme.NoTitleBar.Fullscreen`
2. 代码层: `FLAG_FULLSCREEN` + `FLAG_KEEP_SCREEN_ON`
3. 回调层: `onWindowFocusChanged()` 重置

### 数据刷新

- 模拟模式 (DemoSource): 20Hz, 30秒状态循环 (IDLE→NORMAL→WOT→TRANSIENT→DFCO→CRUISE)
- 蓝牙模式 (BluetoothSource): FlashPro SPP 实时, 50Hz 轮询

## 零依赖

纯 Android Framework API，无第三方库:
- 无 `androidx` / 无 Kotlin / 无 `.so` 原生库
- Release APK: **45 KB**

## 版本历史

### V2.4.2 (2026-06-04) — 最大化主值字体

V2.4.1 适配策略过于保守 — 字体太小，根因：worst-case 预适配 + 过大安全边距 (dp(28)) + 低 maxScaleX。V2.4.2 从"永不截断"转为"最大化字体且永不截断"。

#### 变更

1. **恢复大字体** — 第1行 112sp，第2行 102sp，语义标签 92sp
2. **移除 worst-case 默认测量** — 测量当前文本宽度，不再为假设的最长值预缩窄
3. **安全边距 dp(28) → dp(4)** — TextPaint measureText 精确，仅需微量余量
4. **不设 maxScaleX 上限** — scaleX 可达 1.0（窄文本如单位数不压缩）
5. **scaleX 滞后防抖** — `lastMainScaleX[]` 数组：缩窄立即生效，放宽需 gap > 0.06
6. **布局权重 3:1.5 → 4.0:1.0** — 主值区 80%，MAX/MIN 区 20%
7. **移除 FitParam 类** — 替换为更简单的 `getBaseSp(i)` + `MIN_SCALE_X[]` 数组
8. **统一渲染路径** — `renderMainValueMaxFit()` 替代 `fitSplitValueTextWidthOnly()`

### V2.4.1 (2026-06-03) — Width-Only 主数值适配

修复带符号大值 (+25.0、+39.6、+24.4) 被截断的问题。高度固定，只横向压缩 textScaleX。

#### 变更

1. **Width-Only Fit** — 主数据 textSize 固定在 FitParam.baseSp，只动态调整 textScaleX，不再降低高度
2. **Worst-Case 预适配** — L.TRIM、IGN、S.TRIM 用最坏情况文本测量，防止数值变化时突然截断
3. **FitParam 类** — 替换平铺 float 数组，每卡片 {baseSp, minScaleX, maxScaleX, useWorstCase, worstCaseText}
4. **语义标签 Width-Only** — DFCO/SYNC 也改为高度固定只压缩宽度
5. **lastFitWidth 缓存** — 文本和宽度没变时跳过重复测量

### V2.4 (2026-06-03) — 显示适配收口

最终显示质量收口。DFCO/SYNC 标签完整显示不再截断，主数值自动适配卡片宽度。

#### 1. 语义标签全宽模式

A/F、IGN、S.TRIM 显示 `DFCO` 或 `SYNC` 时：
- 隐藏小数部分和极值面板
- 展开数值区域为全卡片宽度
- TextPaint 自动适配标签文字（基准 88sp，最小 58sp）

#### 2. 主数值自动适配

替换固定 `textSize` + `textScaleX` 为 TextPaint 测量的自动适配：
- 测量 `valueInt + valueDec` 组合宽度
- 先压缩 scaleX（到参数最小值），再降字号（到参数最小值）
- 每卡片独立适配参数

#### 3. XML 布局 ID

`item_sensor_card.xml` 新增 `@+id/valueArea` 和 `@+id/extremePanel` 支持动态可见性控制。

#### 4. 缓存优化

`lastMainCombinedText` 和 `lastSemanticText` 缓存避免 20Hz 刷新时重复 TextPaint 测量。

#### 5. 修改文件

| 文件 | 变更 |
|------|------|
| `MainActivity.java` | fitSplitValueText、fitSingleText、setSemanticLayoutMode、FIT_PARAMS 数组、缓存数组 |
| `item_sensor_card.xml` | 新增 valueArea/extremePanel ID、ellipsize=none |
| `build.gradle` | versionCode 8→9，versionName "2.3"→"2.4" |

### V2.3 (2026-06-03) — 显示真实性优化

核心理念：有效数据清晰显示，无燃烧意义的数据显示 DFCO/SYNC 标签。

#### 1. CombustionInvalid — 多源 DFCO 检测

显示层使用多源语义检测替代单一 `TPlate < 2` 判断。通过 INJ=0 + Lambda/Target ≥ 1.8 + Open Loop + 低负荷（pedal/TP/MAP）+ 行车中 来检测 DFCO/overrun fuel cut。正确捕捉 TPlate = 2~3 的真实 DFCO 帧。

#### 2. DFCO 退出动态 SYNC 窗口

每个参数独立释放时间，不使用固定 800ms 硬锁：

- **IGN**：最短 250ms，喷油恢复即释放（约 250~350ms）
- **A/F**：最短 300ms，lambda 脱离极稀状态后释放（约 300~500ms），最长 800ms
- **S.TRIM**：最短 500ms，closed loop + stoich target + injector active 后释放（约 500~800ms）

#### 3. Lambda 语义 A/F 报警

A/F 颜色与闪烁使用 measured lambda vs target lambda 替代固定 AFR 阈值：

- **WOT**：绝对 lambda 阈值 — 过稀危险 > 0.86（红闪），偏稀警告 > 0.83（黄），过浓警告 < 0.68（黄），安全（绿）
- **闭环**：Lambda 偏差 — 绿 ≤ 0.03，黄 ≤ 0.06，红 > 0.06

#### 4. 置信度不再压暗显示

移除 `state.textAlpha()` 对 A/F、IGN、S.TRIM 实时值的压暗效果。置信度继续驱动滤波强度、WOT 检测、增压释放率、历史准入 — 但不再让有效数据看起来变暗。

#### 5. S.TRIM 闭环门控

S.TRIM 仅在 Fuel Status = closed loop + stoich target + injector active 时显示真实值。WOT/open loop 期间显示 `SYNC`。

#### 6. MAX/MIN 在 DFCO/SYNC 期间保护

A/F、IGN、S.TRIM 的 recent MAX/MIN 在 DFCO 或 SYNC 状态下不更新。防止 DFCO 退出瞬态污染极值追踪。

#### 7. A/F 闪烁防御

A/F 红色闪烁在 DFCO/SYNC 期间被抑制，防止残留闪烁状态压暗 DFCO/SYNC 标签。

#### 8. 修改文件

| 文件 | 变更 |
|------|------|
| `MainActivity.java` | combustionInvalid 门控、动态 SYNC 窗口、Lambda 语义 A/F 报警、S.TRIM 闭环门控、移除置信度压暗、闪烁防御 |
| `build.gradle` | versionCode 7→8，versionName "2.2"→"2.3" |

### V2.2 (2026-06-03) — 全程极值与语义报警

#### 1. 双路径 MAX/MIN — Session Extreme vs Recent Extreme

参数分为两类：

- **全程极值 (Session Extreme)** (Ethanol、ECT、IAT、L.TRIM、MAP)：保留整个驾驶周期的 MAX/MIN，不衰减、不冷却、蓝牙重连或 DFCO 退出不重置。通过可信值过滤排除 NaN、Infinity 和物理不可能值。
- **近期动态 (Recent Extreme)** (A/F、IGN、S.TRIM)：继续使用 V2.1 三层准入（语义准入 + 非对称冷却 + 突破阈值）+ 30s 自动衰减。

#### 2. MAP 全程极值使用原始增压值

MAP 全程 MAX/MIN 使用校验后的原始增压值（在不对称滤波、近零归零、Rate Limit 之前），防止滤波器压低真实的瞬态峰值。

#### 3. A/F 按工况分层报警

- **DFCO**：不报警（断油，无燃烧）
- **WOT**：过稀危险优先 — A/F > 12.2 红色闪烁（危险稀）；A/F < 10.2 仅黄色警告（过浓，非危险）
- **NORMAL/IDLE/WARMUP**：标准四区变色，保守闪烁阈值（仅 TP > 5% 且 A/F < 12.5 或 > 16.5 时闪烁）

#### 4. 数据新鲜度指示

顶部状态栏显示实时数据新鲜度：`LIVE`（绿，<500ms）→ `STALE`（黄，<1500ms）→ `DATA LOST`（红，<3000ms）→ `BT LOST`（红，≥3000ms）。

#### 5. 蓝牙重连保留全程极值

短暂蓝牙断开/重连不再清空 Ethanol、ECT、IAT、L.TRIM、MAP 的全程 MAX/MIN。仅重置滤波状态和 A/F/IGN/S.TRIM 的近期极值。

#### 6. 修改文件

| 文件 | 变更 |
|------|------|
| `MainActivity.java` | 双路径 MAX/MIN、`isTrustedForSessionExtreme()`、A/F 工况报警、数据新鲜度、重连保留 |
| `build.gradle` | versionCode 6→7, versionName "2.1"→"2.2" |

### V2.1 (2026-05-27) — 历史准入系统 + 真实 LOG 回放

#### 1. History Admission System — 三层 MAX/MIN 质量控制

V2.0 的 MAX/MIN 追踪器是裸记录器——任何工况的值都无条件写入。导致 DFCO 退出 AFR 闪到 10.0 污染 MIN、冷启动 IGN 退角到 -18° 污染 MIN、瞬态 S.TRIM 暴跳污染 MAX。V2.1 用三层准入系统替代：

- **第一层 — 语义准入**: 每卡片独立规则，定义哪些工况产生有分析意义的值。例如 A/F 在 DFCO/WARMUP/SPOOL/DFCO退出恢复(500ms) 期间被排除；IGN 在 DFCO/WARMUP 期间被排除。
- **第二层 — 非对称冷却**: MAX 和 MIN 有独立的每参数冷却时间。例如 Boost MAX=500ms MIN=1500ms（真空不如正压峰值重要）。WOT 模式缩短所有冷却时间（状态关联冷却）。
- **第三层 — 逐参数突破**: 绝对增量阈值允许重大事件绕过冷却。例如 A/F 稀 Δ>+0.5，IGN MIN Δ>-3°。突破后重置冷却计时器。

#### 2. Recent Peak — 30s 自动衰减

显示用 MAX/MIN 在 30s 无新极值后自动衰减回当前值。Session Peak 继续内部追踪但不在 UI 显示。确保驾驶员看到"最近的记忆"而非历史极值。

#### 3. Demo 数据源 — 真实 LOG 回放

用 2026-05-27 真实驾驶 LOG（E26, 27% 乙醇, 24℃）替代合成正弦波。五段顺序播放：IDLE(5s) → NORMAL(8s) → WOT(12s) → DFCO(8s) → ACCEL(15s)，后接 EXTREME 合成扫描(9s)。Demo 模式现在正确触发 V2.0 WOT 检测（提供了旧 DemoSource 没有的 `ClosedLoop` + `TargetLambda` PID）。

#### 4. DFCO 退出过渡同步

V2.0 仅对 A/F 和 S.TRIM 在 DFCO 退出时应用置信度驱动的渐进 alpha 恢复（textAlpha）。IGN 瞬间恢复全亮，视觉上不同步。V2.1 将 textAlpha 扩展到所有三个 DFCO 卡片（A/F、IGN、S.TRIM），确保过渡完全同步。

#### 5. 修改文件

| 文件 | 变更 |
|------|------|
| `MainActivity.java` | History Admission (3 层机制) + Recent Peak + 状态关联冷却 + DFCO 退出追踪 + DFCO 过渡同步, 约 100 行 |
| `DemoSource.java` | **重写** — 真实 LOG 数据回放, 5 段关键帧 + 线性插值 |
| `proguard-rules.pro` | 精准逐类 keep 规则（排除 DemoSource 不进入正式版） |
| `build.gradle` | versionCode 5→6, versionName "2.0"→"2.1" |

### V2.0 (2026-05-24) — ECU 语义状态引擎 + 峰值保持

#### 1. 三维语义状态模型

从 `enum State` 升级为 `EngineSemanticState` 结构体，四个正交维度：

- **MainState** (ECU 策略层，互斥): DFCO / WOT / WARMUP / IDLE / NORMAL
- **SubState** (工况细节): WOT→SPOOL/PEAK/HOLD, DFCO→ENTER/HOLD
- **Modifier** (动态修饰，正交叠加): TIP_IN / TIP_OUT / BOOST_SURGE / RPM_DIP
- **Confidence** (0.0~1.0): 加权计算 + 低通滤波

#### 2. WOT 重新定义 — ECU 策略信号检测

L15B7 扭矩模型 ECU 下，节气门位置 ≠ 发动机负荷。WOT 改为基于 ECU 策略信号：

- `ClosedLoop(0x0340) OFF` + `TargetLambda(0x0322) < 0.95` + `MAP > 120kPa` + `RPM > 1500`
- 三重 AND：Open Loop + 富油命令 + 涡轮增压建立

#### 3. 新增 WARMUP 暖机状态

冷启动检测，ECT 滞回防抖（进入 <65°C，退出 >72°C + ClosedLoop ON 持续 5s）

#### 4. DFCO 四重锁定

新增 RPM>1400，防止怠速恢复边缘误触发。

#### 5. TRANSIENT → Modifier

瞬态不再是主状态，改为正交 Modifier，可叠加于任意 MainState，解决 TRANSIENT 劫持 WOT 的问题。

#### 6. 置信度连续调制

各状态加权置信度 + 低通滤波 (α=0.1) 驱动：
- A/F alpha: `0.3 + confidence × 0.4`（连续调制，非二值）
- 文字透明度: `0.45 + 0.55 × confidence`（敏感卡片渐隐）
- Boost 释放率: 三级自动切换

#### 7. MECHANICAL 峰值保持

Boost 和 IGN 卡片新增双向峰值追踪：
- Boost: peakRetention=0.70（半衰期 ~3s），半透明填充 + 粗标记线
- IGN: peakRetention=0.85（半衰期 ~4.6s）
- A/F 包络衰减调慢: 0.55 → 0.80（半衰期 1.2s → 3.1s）

#### 8. 修改文件

| 文件 | 变更 |
|------|------|
| `EngineSemanticState.java` | **新增** — 三维语义状态结构体 |
| `EngineStateTracker.java` | **重写** — V2 ECU 语义模型 + 置信度 |
| `ScaleBarView.java` | MECHANICAL 原型: 双向峰值追踪 + peakRetention 参数 |
| `MainActivity.java` | 置信度驱动 alpha/透明度/释放率，`hasModifier()` 快速刷新 |
| `build.gradle` | versionCode 4→5, versionName "1.4"→"2.0" |

### V1.4 (2026-05-24) — 响应优化 + 蓝牙 fullReset

实车测试发现三个问题：激烈驾驶时状态切换迟钝、DFCO 退出后数据恢复慢、蓝牙断线后自动重连完全无效。

#### 1. 滞回时间优化 (EngineStateTracker.java)

V1.3 的滞回时间导致激烈驾驶时感知延迟 600~1000ms（例如 DFCO→WOT 快速补油场景）。根本原因是 DFCO EXIT 滞回 300ms——当 TP 从 0% 跳到 80%+，信号完全无歧义，300ms 的防抖毫无必要。

V1.4 基于实车数据分析全面缩短：

| 转换 | V1.3 | V1.4 | 降幅 | 理由 |
|------|------|------|------|------|
| DFCO 进入 | 200ms | **100ms** | -50% | TP<2%+Inj<0.5ms+车速>15 三重条件明确 |
| DFCO 退出 | 300ms | **50ms** | -83% | TP 恢复 >2% 无歧义，50ms 仅防抖 |
| WOT 进入 | 100ms | **30ms** | -70% | TP>80% 完全无歧义 |
| WOT 退出 | 200ms | **80ms** | -60% | 适度缩短 |
| IDLE 进入 | 300ms | **200ms** | -33% | 稍微缩短 |
| TRANSIENT | 80ms | **40ms** | -50% | 快速捕捉瞬态 |
| 默认 | 100ms | **50ms** | -50% | 缩短通用滞回 |

**DFCO→WOT 最坏情况延迟** 从 ~600-1000ms 降低到 ~80-130ms (DFCO EXIT 50ms + WOT ENTER 30ms + Rate Limit 旁路)。

#### 2. 状态切换即时响应 (MainActivity.java)

除缩短滞回外，两项额外优化：

**a) DFCO 退出重置所有滤波器 + 跳过 Rate Limit：**

V1.3 仅重置 A/F/IGN/S.TRIM 的滤波器。V1.4 扩展到 Boost (card 4)——DFCO 期间 Boost 不对称滤波的 release=0.02（极慢衰减），退出后 Boost 显示值卡住不动。V1.4 重置 Boost 滤波状态，立即显示真实值。

同时所有卡片在 DFCO 退出时跳过 Rate Limit（`lastUpdateTime[i] = 0`），强制立即刷新显示，而非等待下一个 Rate Limit 周期。

**b) 优化前后延迟对比 (DFCO→WOT)：**

```
V1.3:
t=0     踩油门
t=300ms DFCO EXIT 滞回满足
t=400ms WOT ENTER 滞回满足
t=500ms Rate Limit 间隔到达
t=600ms 第一个真实值显示 (Boost EMA 缓慢爬升)

V1.4:
t=0     踩油门
t=50ms  DFCO EXIT 滞回满足，滤波器重置，Rate Limit 旁路
t=80ms  WOT ENTER 滞回满足
t=80ms  第一个真实值立即显示 (无滤波延迟)
```

#### 3. 蓝牙 fullReset 重连 (BluetoothSource.java)

V1.3 的 autoReconnect 实际无效——只有重启 App 才能恢复连接。根因：重连在同一个 pollThread 内执行，Android 蓝牙栈没有完全释放 RFCOMM channel 就开始新连接。

V1.4 用 **fullReset** 替代循环内重连：

| 步骤 | V1.3 autoReconnect | V1.4 fullReset |
|------|--------------------|----------------|
| 线程 | 同一个 pollThread | **新线程**，旧线程完全销毁 |
| 蓝牙栈 | 无清理等待 | **等待 1s** 让 RFCOMM channel 释放 |
| Protocol | 新实例 | 新实例 (相同) |
| 重连 | 在轮询循环内 | **独立重置线程**，轮询循环退出 |
| UI | 保持 | 保持 (相同) |

断线流程：
1. 轮询循环中 `IOException` → `connected = false`
2. 通知 UI `onDisconnected()`
3. 退出轮询循环 (V1.3 继续在循环内)
4. 从 UI 线程触发 `fullReset()`
5. `fullReset()`: 销毁旧线程 → 关闭 Socket → **等待 1s 蓝牙栈清理** → 新 Protocol → 新连接线程
6. 成功后自动开始轮询

#### 4. 修改文件

| 文件 | 变更 |
|------|------|
| `EngineStateTracker.java` | 7 项滞回时间全部缩短 (DFCO EXIT 300→50ms, WOT ENTER 100→30ms 等) |
| `MainActivity.java` | DFCO 退出重置 Boost 滤波器 + 跳过所有卡片 Rate Limit |
| `BluetoothSource.java` | fullReset 重连 (新线程, 蓝牙栈清理等待, 全新连接) |
| `build.gradle` | versionCode 3→4, versionName "1.3"→"1.4" |

### V1.3 (2026-05-22) — 动力学原型引擎 + UI 精调

详细变更说明请参阅 [English V1.3 Changelog](README.md#v13-2026-05-22--dynamics-archetype-engine--ui-polish)，以下是摘要：

#### 核心重写: V3 动力学原型引擎
- 4 类数学模型: STATIC (锁定) / THERMAL (牛顿冷却, 双向) / MECHANICAL (弹簧-阻尼, 二阶系统) / TRANSIENT (振荡包络, 双向)
- 每卡片独立参数，修复 L.TRIM 双向峰值、MECHANICAL 阻尼速度反向、TRANSIENT 衰减过快等 5 个 bug

#### 情绪渲染系统
- 7 种情绪状态 (NONE/BUILDING/STABLE/RELEASING/DANGER/WARNING/PROTECTION)
- 渐变跟随 + 三层渲染 (填充色混合+指示线变色+边缘发光)

#### UI 优化
- 字体缩放升级: Row 1 ×1.5 (112.5sp), Row 2 ×1.65 (99sp)
- 卡片位置重排: 慢数据(Ethanol/ECT/IAT/L.TRIM) 上行，快数据(MAP/A/F/IGN/S.TRIM) 下行
- 数据条加粗 10dp→15dp，Ethanol scaleX=0.50 解决 "E85" 溢出裁剪
- DFCO 显示优化 + 置信度精简 (仅 A/F/S.TRIM)

#### 蓝牙自动重连重写
- 完全重建策略: 新建 Protocol + 新建 Socket + 首次零延迟 + 指数退避
- 三重容错连接: 反射 ch1 → 不安全 SPP → 标准 SPP

#### 修改文件
`ScaleBarView.java` (全面重写) / `MainActivity.java` (字体+卡片+情绪) / `BluetoothSource.java` (重连) / `activity_main.xml` / `item_sensor_card.xml` / `build.gradle` (版本号)

### V1.2 (2026-05-21) — UI 修复 + 仓库清理

- **Boost 卡片字号修正**: 从第1行大字号(90sp)改为第2行小字号(84sp)，解决小数部分被遮挡问题
- **仓库清理**: 移除旧代码(GaugeView/WheelView/PidRegistry)、旧布局、开发截图、旧APK
- **精简 colors.xml**: 仅保留实际使用的颜色资源
- **MAC 地址脱敏**: 替换为占位符，保护隐私

### V1.1 (2026-05-21) — 数据处理管线升级

- **引擎状态检测**: EngineStateTracker 5 状态优先级机 (DFCO > TRANSIENT > WOT > IDLE > NORMAL) + 滞回防抖动
- **TRANSIENT 三重检测**: dTP/dt>50%/s OR dRPM/dt>1200rpm/s OR dMAP/dt>300kPa/s (MT 车必备)
- **MAP → Boost 相对压力**（减去大气压），范围 -1.0~2.0 bar
- **Ethanol 4 级变色**: <E20 白 / E20-40 绿 / E40-60 黄 / >E60 红
- **DFCO 处理**: A/F 显示"DFCO"，S.TRIM/L.TRIM 冻结
- **EMA 信号滤波**: 各参数独立 α 系数 (Ethanol 0.05, ECT/IAT 0.05, A/F 0.3, IGN 0.3)
- **自适应滤波**: A/F WOT/TRANSIENT α=0.7+20Hz, NORMAL α=0.3+10Hz
- **Boost 不对称滤波**: 快攻击 α=0.6，动态释放 (NORMAL 0.15, TRANSIENT 0.05, DFCO 0.02)
- **范围验证**: 8 参数物理极限检查 + NaN 保护
- **IGN 负荷门控**: TP<3% 时降低 α
- **信心系统**: TRANSIENT 下 A/F/S.TRIM/L.TRIM 透明度降至 45%
- **MAP 近零钳位**: 消除 "-0" 显示
- **蓝牙自动重连**: 指数退避 (1s→2s→4s→8s) + 3s 读取超时
- **转速灯条下移**: 3000/3500/4000/4500/5000/5500，≥5500 闪烁
- **DemoSource EXTREME**: 正弦波扫满 ScaleBar 全量程
- **Last-Valid 缓存**: 蓝牙丢包时保留最后有效值
- **MAX/MIN 颜色区分正负**（蓝正红负）

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
