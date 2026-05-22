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
| **MECHANICAL** | Boost/MAP, IGN | Spring-Damper 二阶系统 (Euler积分) | 自然过冲+回弹+物理残影 |
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

## 数据处理管线 (V1.3)

### 引擎状态检测

`EngineStateTracker` 带滞回防抖动的优先级状态机，检测 5 种工况：

**优先级**: DFCO > TRANSIENT > WOT > IDLE > NORMAL

| 状态 | 判定条件 | 滞回时间 |
|------|---------|---------|
| DFCO | TP<2%, 车速>15, 喷油<0.5ms | 进入200ms, 退出300ms |
| TRANSIENT | dTP/dt>50%/s OR dRPM/dt>1200rpm/s OR dMAP/dt>300kPa/s | 80ms |
| WOT | TP>80% | 进入100ms, 退出200ms |
| IDLE | RPM<1000, TP<2%, 车速<3 | 300ms |
| NORMAL | 默认 | 100ms |

> TRANSIENT 三重判定（MT 车必备）: 快速松油 / 换挡 RPM 暴跌 / 增压崩溃，任一触发即判定。

### 自适应滤波

- **EMA 滤波**: 各参数独立 α 系数 (Ethanol 0.05, ECT 0.1, IAT 0.05, L.TRIM 1.0, A/F 0.3, IGN 0.4, S.TRIM 0.2)
- **Boost 不对称滤波**: 增压快攻击 α=0.6，泄压动态释放（NORMAL 0.15, TRANSIENT 0.05, DFCO 0.02）
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

### 信心系统

TRANSIENT 状态下降低 A/F、S.TRIM 的显示透明度至 45%，提示数据可能不准确。L.TRIM 已排除 (ECU 长期学习值本身极稳定)。

### NaN 保护

EMA 滤波前检查 NaN，防止传感器异常帧污染滤波状态。

### DFCO 处理

减速断油期间：
- A/F 显示灰色 "DFCO"（断油时 Lambda 读到的是空气，无意义）
- S.TRIM 和 L.TRIM 冻结在最后有效值

### Last-Valid 缓存

蓝牙丢包时保留最后有效值，以 40% 透明度显示，而非直接显示 "--"。

### 蓝牙自动重连 (V1.3 完全重建)

完全重建策略 (和重启 App 完全一致):
1. 彻底关闭旧连接 (Socket + Stream)
2. 新建 `HondataProtocol` 实例 (清零所有协议状态)
3. 首次重连无延迟 (和重启 App 一样立即尝试)
4. 后续失败: 指数退避 (1s→2s→4s→8s)
5. 完整握手: 点火检测(10次) → INIT → 传感器定义
6. 成功后自动恢复数据采集

## 文件结构

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # 主界面, 数据绑定, 滤波, 闪烁控制, 情绪引擎
├── ScaleBarView.java          # V3 动力学原型引擎 (4原型+情绪渲染)
├── ShiftLightView.java        # 6-LED 转速灯条
└── data/
    ├── DataSource.java        # 数据源接口 (Callback)
    ├── BluetoothSource.java   # 蓝牙SPP (FlashPro, V1.3 完全重建式自动重连)
    ├── EngineStateTracker.java # 引擎状态检测 (V1.2: 多条件瞬态+滞回)
    ├── HondataProtocol.java   # Hondata 协议解析+缩放公式
    ├── SensorData.java        # PID→Double Map
    └── DemoSource.java        # 模拟数据源 (V1.2: 6阶段+EXTREME)

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
- 第1行 (i=0~2): scale=1.5 → textSize=112.5sp, scaleX=0.667
- 第1行 (i=0, Ethanol): scaleX=0.50 (额外压窄，"E85"前缀适配容器宽度)
- 第2行 (i=3~7): scale=1.65 → textSize=99sp, scaleX=0.606
- 第2行 (i=6,7 IGN/S.TRIM): scaleX=0.50 (带符号两位数+小数更窄)

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
