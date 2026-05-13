# Hondata Dash — 车载仪表盘

替代 Hondata Dash App 的自定义 Android 车载仪表盘应用，运行在 2020 本田思域 1.5T 的 Root Display Audio 车机上。

## 设备要求

| 项目 | 规格 |
|------|------|
| 系统 | Android 4.2.2 (API 17) |
| 屏幕 | 800×480, 7寸横屏 |
| 数据源 | Hondata FlashPro 蓝牙 SPP |
| 构建工具 | Gradle 8.7 + AGP 8.5.2 + Java 21 |

## 构建与部署

```bash
# 构建
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug

# 安装到模拟器
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动
adb shell am start -n com.hondata.dash/.MainActivity

# 模拟器截图 (需要指定 display ID)
adb exec-out screencap -p -d 4619827259835644672 > screenshot.png
```

## 界面布局 (V3.0)

```
┌──────────────────────────────────────────────────────────────────────┐
│         ▓▓▓▓▓▓▓▓▓▓▓▓▓▓ 彩虹转速灯条 ▓▓▓▓▓▓▓▓▓▓▓▓▓▓              │ ← ShiftLight (14dp)
├──────────────────────────────────────────────────────────────────────┤
│ Powered by Helijohnny ─────── Demo (模拟)   已连接                  │ ← Header (26dp)
├────────────┬────────────┬────────────┬──────────────────────────────┤
│ Ethanol    │ ECT        │ IAT        │ MAP                          │
│            │            │            │                              │
│ E85     %  │ 86      °C │ 33      °C │ 115     kPa                 │
│   92   87  │  90    82  │  35    31  │  120   88                   │ ← MAX/MIN 纯数字
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                   │ ← ScaleBar
├────────────┼────────────┼────────────┼──────────────────────────────┤
│ BAT        │ IGN        │ A/F        │ L.TRIM                       │
│            │            │            │                              │
│ 13.7    V  │ 22.5    °  │ 14.6       │ +1.8     %                  │
│  14.0 13.1 │  25.0 12.5 │  15.0 14.2 │  +2.5  -2.5                │
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                   │
├──────┬───┬───┬───┬───┬────┬─────┬──────┬───────────────────────────┤
│ K.C  │C1 │C2 │C3 │C4 │S.TR│ AFM │ FUEL │        四轮防滑           │ ← 底部行 (59dp)
│ 24sp │ 0 │ 1 │ 0 │ 0 │-5  │ 120 │ 52 % │     (●)    (●)           │
│  25  │   │   │   │   │    │     │      │      ┌──────┐            │
│  %   │   │   │   │   │    │     │      │  (●)        (●)          │
└──────┴───┴───┴───┴───┴────┴─────┴──────┴───────────────────────────┘
```

### 彩虹转速灯条 (ShiftLightView)

- 15 段 LED，RPM 1000~5500 渐进点亮
- 颜色: 绿→黄绿→黄→橙→红橙→红
- 5500 RPM 以上全闪 (120ms 间隔)
- 宽度 = 主数据栏两栏宽度，居中

### Header (顶部)

- "Powered by Helijohnny" 红色斜体标识，左对齐
- 红色装饰线延伸
- 数据源名称 + 连接状态

### 4×2 主数据网格

每个传感器卡片包含:
- **顶部**: 英文缩写 (18sp, #AAAAAA) + 单位右上角 (18sp, #999999)
- **中央左**: 核心数值整数部分 (65sp, 白色粗斜体) + 小数部分 (65sp)
- **中央右**: MAX/MIN 纯数字追踪 (24sp, #999999/#888888)
- **底部**: ScaleBarView 刻度进度条 (37dp)

#### 第1行参数

| 卡片 | PID | 缩写 | 格式 | 范围 | 刻度 | 颜色区间 |
|------|-----|------|------|------|------|----------|
| card0 | 0xB03 | Ethanol | %.0f | 0-100% | 0, 50, 100 | 全绿 #3FB950 |
| card1 | 0x161 | ECT | %.0f | 40-120°C | 40, 60, 80, 100, 120 | <100蓝 / >100红 |
| card2 | 0x151 | IAT | %.0f | 20-100°C | 20, 40, 60, 80, 100 | 全蓝 #00D8FF |
| card3 | 0x110 | MAP | %.0f | -30~250 kPa | -30, 0, 50, 100, 150, 200, 250 | 负压蓝 / 正常蓝 / 正压红 |

#### 第2行参数

| 卡片 | PID | 缩写 | 格式 | 范围 | 刻度 | 颜色区间 |
|------|-----|------|------|------|------|----------|
| card4 | 0x180 | BAT | %.1f | 10-18V | 10, 12, 14, 16, 18 | 全蓝 #00D8FF |
| card5 | 0x140 | IGN | %.1f | -40~40° | -40, -20, 0, 20, 40 | 全蓝 #00D8FF |
| card6 | 0x320 | A/F | %.1f | 9-18 | 9, 14.7, 18 | <14.7富油绿 / >14.7稀油红 |
| card7 | 0x332 | L.TRIM | %+.1f | -25~+25% | -25, 0, 25 | 负红 / 正蓝 |

> **Ethanol 特殊处理**: 数值前加 "E" 前缀 (如 E85)，文字颜色为亮绿色。

### 底部行 (59dp)

| 元素 | 权重 | PID | 说明 |
|------|------|-----|------|
| K.C (Knock Control) | weight=2 | 0x601 | 标题 20sp 左对齐，数值 55sp，<30绿 / <60黄 / >60红 |
| CYL 1-4 | weight=1×4 | 0x604-0x607 | 爆震计数 33sp，0=绿 / 1=黄 / >1=红 |
| S.TRIM | weight=1 | 0x330 | 短期燃油调整 30sp |
| AFM | weight=1 | 0x112 | 空气流量 30sp |
| FUEL % | weight=1 | 0xB04 | 燃油剩余 30sp，% 在标题中 |
| 四轮防滑 | weight=3 | 0x710-0x713 | WheelView，打滑>2%变红+光晕 |

## 文件结构

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # 主界面 Activity, 数据绑定与卡片配置
├── ScaleBarView.java          # 自定义刻度进度条 (带刻度线、颜色区间、锚点、边界对齐)
├── ShiftLightView.java        # 彩虹转速灯条 (15段LED, RPM驱动渐进+闪炼)
├── WheelView.java             # 四轮动态图标 (顶视图, 打滑高亮)
└── data/
    ├── DataSource.java        # 数据源接口 (Callback 模式)
    ├── BluetoothSource.java   # 蓝牙 SPP 数据源 (FlashPro)
    ├── DemoSource.java        # 模拟数据源 (20Hz, 模拟器调试用)
    ├── PidRegistry.java       # PID 注册表 (350+ PID, 18种缩放类型)
    ├── HondataProtocol.java   # Hondata 蓝牙协议解析
    └── SensorData.java        # 传感器数据容器

app/src/main/res/
├── layout/
│   ├── activity_main.xml      # 主布局 (ShiftLight + Header + 2行网格 + 底部行)
│   ├── item_sensor_card.xml   # 传感器卡片 (标题+单位 + 整数/小数分体值 + MAX/MIN + ScaleBar)
│   └── item_knock_cyl.xml     # 爆震缸子卡片 (标签 + 数值)
├── drawable/
│   └── bg_card.xml            # 卡片背景 (纯黑 + 0.5dp 深灰线框 #333333)
└── values/
    ├── styles.xml             # DashboardTheme (NoTitleBar.Fullscreen + 全屏属性)
    ├── colors.xml             # 颜色定义
    └── strings.xml            # 字符串资源
```

## 技术细节

### 全屏方案 (Android 4.2.2 兼容)

三层防御确保车载机全屏:

1. **主题层**: `Theme.NoTitleBar.Fullscreen` + `windowFullscreen=true` + `windowContentOverlay=@null`
2. **代码层**: `FLAG_FULLSCREEN` + `FLAG_KEEP_SCREEN_ON` + `SYSTEM_UI_FLAG_LOW_PROFILE`
3. **回调层**: `onWindowFocusChanged()` 重新应用全屏标志

### 数据刷新

- 模拟模式 (DemoSource): 20Hz 定时器生成逼真传感器数据，RPM 正弦波 800~6200
- 蓝牙模式 (BluetoothSource): 通过 Hondata FlashPro SPP 协议实时读取
- UI 更新通过 `runOnUiThread()` 确保线程安全

### ScaleBarView 刻度进度条

- 数据条 + 半透明颜色区间 + 区间分界线
- 锚点到当前值的填充条 + 当前值指示线
- 边缘刻度文字自动对齐 (左边界左对齐 / 右边界右对齐)

### ShiftLightView 彩虹灯条

- 15 段 LED，圆角矩形绘制 + 发光效果
- Handler 驱动闪烁 (5500+ RPM)，detach 时自动清理

### WheelView 四轮图标

- 顶视图: 车身轮廓 + 4个车轮 + 车轴连线
- 打滑 >2% 时车轮变红 + 光晕效果 + 百分比数值

### PID 速查表

| PID | 名称 | 显示位置 |
|-----|------|----------|
| 0x100 | RPM | ShiftLight 转速灯条 |
| 0xB03 | Ethanol % | card0 |
| 0x161 | ECT2 水温 | card1 |
| 0x151 | IAT2 进气温度 | card2 |
| 0x110 | MAP 进气压力 kPa | card3 |
| 0x180 | Battery 电压 V | card4 |
| 0x140 | Ignition 点火角 ° | card5 |
| 0x320 | Air Fuel 空燃比 | card6 |
| 0x332 | LTFT 长期燃油修正 % | card7 |
| 0x601 | Knock Control % | K.C 爆震控制 |
| 0x604-0x607 | Knock CYL1-4 | 爆震缸 |
| 0x330 | STFT 短期燃油修正 % | S.TRIM |
| 0x112 | AFM 空气流量 g/s | AFM |
| 0xB04 | Fuel Level % | FUEL |
| 0x710-0x713 | 轮速差 FL/FR/RL/RR | 四轮图标 |

## 零依赖架构

本项目**不依赖任何第三方库**，纯 Android Framework API:

- 无 `androidx.appcompat` — 直接使用 `Activity` + `Theme.NoTitleBar.Fullscreen`
- 无 `kotlin` 运行时 — 纯 Java
- 无 `.so` 原生库 — 纯 Java Canvas 绘制
- R8 代码压缩 + 资源压缩 + 日志剥离
- Release APK: **34 KB**

## 设计风格

- **背景**: 纯黑 #000000
- **线框**: 极细深灰 #333333 (0.5dp)
- **主数据**: 白色粗斜体 65sp
- **标题/单位**: #AAAAAA / #999999, 18sp
- **MAX/MIN**: #999999 / #888888, 24sp 纯数字
- **主色调**: 赛车蓝 #00D8FF, 亮绿 #3FB950, 警告红 #FF4444
- **字体风格**: 无衬线斜体数字 (系统默认 sans-serif italic)

## 版本历史

### V2.0 — 硬核科技风格重构
- 4×2 网格 + 中英文双语标签 + 带刻度进度条
- 底部爆震控制栏 + 辅助数据列表 + Footer 状态栏
- 三层全屏防御

### V2.1 — 轻量化优化
- 移除 `androidx.appcompat` 依赖，APK 从 5.1MB → 34KB
- R8 代码压缩 + 资源压缩

### V2.3 — 英文缩写简化
- 标签改为纯英文缩写 (ECT/IAT/MAP/IGN/A/F/L.TRIM)
- MAP 替换 BOOST，ECT 替换水温
- 添加 MAX/MIN 实时追踪

### V2.4 — 彩虹转速灯条
- 新增 ShiftLightView (15段LED, RPM驱动渐进点亮+闪烁)
- 主卡片字体 1.2x，底部栏标题统一 8sp
- 底部栏数据字体 1.5x

### V2.5 — 刻度条与布局优化
- ScaleBarView 边缘刻度文字对齐修复
- MAP -10~200kPa, IGN -40~40, A/F 6~20
- S.TRIM 替换 L.TRIM (PID 0x330)
- 页脚 ECU 文字更新

### V3.0 — UI 全面优化 (当前版本)
- 单位移至右上角，与标题同大小 (18sp)
- 主数据 65sp，整数/小数分体显示
- MAX/MIN 纯数字 24sp
- Ethanol 加 E 前缀
- A/F 刻度简化: 9 / 14.7 / 18
- MAP 负压区间扩展 (-30~250 kPa)
- BAT 与 MAP 位置互换
- K.C 标题 20sp，数据 55sp
- Footer 移除，底部数据栏贴底
- Header: "Powered by Helijohnny" 替换 "HONDA RACING"
- 全局文字对比度提升
- 底部栏数据字体 30sp
