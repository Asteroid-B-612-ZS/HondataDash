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
adb -s emulator-5554 uninstall com.hondata.dash
adb -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk

# 启动
adb shell am start -n com.hondata.dash/.MainActivity
```

## 界面布局 (V2 硬核科技风格)

### 整体结构

```
┌──────────────────────────────────────────────────────────────────────┐
│ ── HONDA RACING ──────────────────── Demo (模拟)   已连接           │ ← Header (30dp)
├────────────┬────────────┬────────────┬──────────────────────────────┤
│ 乙醇含量    │ 水温       │ 进气温度    │ 电压                         │
│ ETHANOL    │ WATER TEMP │ IAT        │ VOLTAGE                      │
│            │            │            │                              │
│    85 %    │   93 °C    │   38 °C    │  13.6 V                     │
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                   │ ← 进度条+刻度
├────────────┼────────────┼────────────┼──────────────────────────────┤
│ 涡轮压力    │ 点火角度    │ 空燃比      │ 长期燃油调整                  │
│ BOOST      │ IGNITION   │ AIR FUEL   │ LT FUEL TRIM                │
│            │            │            │                              │
│  1.28 BAR  │   14.0 °   │   11.8     │  +2.3 %                     │
│ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬  │ ▬▬▬▬▬▬▬▬                   │
├──────┬───┬───┬───┬───┬──────────────────────┬───────────────────────┤
│爆震  │C1 │C2 │C3 │C4 │    FL    FR          │ 油门开度      32.4%   │
│控制  │ 0 │ 1 │ 0 │ 0 │   (●)    (●)         │ 进气压力     115 kPa  │
│0.0°  │   │   │   │   │    ┌──────┐           │ 空燃比        11.8    │ ← 底部行 (88dp)
│▬▬▬▬  │▬▬▬│▬▬▬│▬▬▬│▬▬▬│ RL (●)  (●) RR      │ 发动机负荷     68%    │
├──────┴───┴───┴───┴───┴──────────────────────┴───────────────────────┤
│ ● REC   LOG: ON          ECU: Hondata                    12:34:56  │ ← Footer (24dp)
└──────────────────────────────────────────────────────────────────────┘
```

### Header (顶部)

- 红色 "HONDA RACING" 斜体标识
- 两侧红色几何装饰线向边缘延伸
- 数据源名称 (左) + 连接状态 (右)

### 4×2 主数据网格

每个传感器卡片包含:
- **左上**: 中文字段名 (10sp, 浅灰 #999999)
- **中文下方**: 英文全拼 (8sp, 深灰 #555555, 加粗大写)
- **中央**: 核心数值 (32sp, 白色, 粗斜体) + 小号单位 (10sp, 灰 #777777)
- **底部**: 带刻度线和数字标尺的水平进度条 (ScaleBarView, 22dp)

#### 第1行参数

| 卡片 | PID | 中文 | 英文 | 范围 | 刻度 | 颜色区间 |
|------|-----|------|------|------|------|----------|
| card0 | 0xB03 | 乙醇含量 | ETHANOL | 0-100% | 0, 50, 100 | 全绿 #3FB950 |
| card1 | 0x160 | 水温 | WATER TEMP | 40-120°C | 40, 60, 80, 100, 120 | <100蓝 / >100红 |
| card2 | 0x150 | 进气温度 | IAT | 20-100°C | 20, 40, 60, 80, 100 | 全蓝 #00D8FF |
| card3 | 0x180 | 电压 | VOLTAGE | 10-18V | 10, 12, 14, 16, 18 | 全蓝 #00D8FF |

#### 第2行参数

| 卡片 | PID | 中文 | 英文 | 范围 | 刻度 | 颜色区间 |
|------|-----|------|------|------|------|----------|
| card4 | 0x114 | 涡轮压力 | BOOST | -1~2 BAR | -1, 0, .5, 1, 1.5, 2 | 负压蓝 / 0-1蓝 / >1红 |
| card5 | 0x140 | 点火角度 | IGNITION ADV | -10~40° | -10, 0, 10, 20, 30, 40 | 全蓝 #00D8FF |
| card6 | 0x320 | 空燃比 | AIR FUEL RATIO | 10-18 | 10, 12, 14, 14.7, 16, 18 | <14.7富油绿 / >14.7稀油红 |
| card7 | 0x332 | 长期燃油调整 | LT FUEL TRIM | -25~+25% | -25, 0, 25 | 负红 / 正蓝 |

> **涡轮增压换算**: PID 0x114 原始单位 kPa，显示时 ÷100 转为 BAR。

### 底部行 (88dp)

左侧 (占3/4宽度) — 爆震控制区域:

| 元素 | 权重 | 说明 |
|------|------|------|
| KNOCK RETARD | weight=1 | PID 0x603, 范围 -15~5°, 绿色迷你进度条 |
| CYL 1-4 | weight=0.5×4 | PID 0x604-0x607, 爆震计数, 颜色: 0=绿 / 1=黄 / >1=红 |
| 四轮图标 | weight=2 | WheelView, PID 0x710-0x713, 打滑>2%时变红+光晕 |

右侧 (占1/4宽度) — 辅助数据列表:

| 数据 | PID | 格式 |
|------|-----|------|
| 油门开度 | 0x122 | %.1f% |
| 进气压力 | 0x110 | %.0f kPa |
| 空燃比 | 0x320 | %.1f |
| 发动机负荷 | 0x116 | %.0f% |

### Footer (底部状态栏)

- `● REC` — 红色录制标识
- `LOG: ON` — 绿色日志状态
- `ECU: Hondata` — 暗红居中
- `HH:mm:ss` — 实时时钟, 每秒更新

## 文件结构

```
app/src/main/java/com/hondata/dash/
├── MainActivity.java          # 主界面 Activity, 数据绑定与卡片配置
├── ScaleBarView.java          # 自定义进度条 (带刻度线、颜色区间、锚点)
├── WheelView.java             # 四轮动态图标 (顶视图, 打滑高亮)
├── GaugeView.java             # [废弃] 早期弧形仪表, 未使用
└── data/
    ├── DataSource.java        # 数据源接口 (Callback 模式)
    ├── BluetoothSource.java   # 蓝牙 SPP 数据源 (FlashPro)
    ├── DemoSource.java        # 模拟数据源 (20Hz, 模拟器调试用)
    ├── PidRegistry.java       # PID 注册表 (350+ PID, 18种缩放类型)
    ├── HondataProtocol.java   # Hondata 蓝牙协议解析
    └── SensorData.java        # 传感器数据容器

app/src/main/res/
├── layout/
│   ├── activity_main.xml      # 主布局 (Header + 2行网格 + 底部行 + Footer)
│   ├── item_sensor_card.xml   # 传感器卡片 (双语标签 + 数值+单位 + ScaleBar)
│   ├── item_sensor.xml        # [旧版] 极简卡片, 不再使用
│   └── item_knock_cyl.xml     # 爆震缸子卡片 (标签 + 数值 + 迷你进度条)
├── drawable/
│   ├── bg_card.xml            # 卡片背景 (纯黑 + 0.5dp 深灰线框 #333333)
│   ├── bg_sensor_card.xml     # [旧版] 未使用
│   └── bg_grid_border.xml     # [旧版] 未使用
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
3. **回调层**: `onWindowFocusChanged()` 重新应用全屏标志 (车载机休眠/唤醒后状态栏可能恢复)

### 数据刷新

- 模拟模式 (DemoSource): 20Hz 定时器生成逼真传感器数据
- 蓝牙模式 (BluetoothSource): 通过 Hondata FlashPro SPP 协议实时读取
- UI 更新通过 `runOnUiThread()` 确保线程安全

### ScaleBarView 进度条

自定义 View 支持以下配置:

| 属性 | 方法 | 说明 |
|------|------|------|
| 范围 | `setRange(min, max)` | 进度条数值范围 |
| 刻度 | `setTicks(values, labels)` | 刻度位置和文字 |
| 颜色区间 | `addZone(start, end, color)` | 不同范围的填充颜色 |
| 锚点 | `setAnchor(value)` | 进度条填充起始位置 |
| 标签 | `setShowLabels(bool)` | 是否显示刻度数字 |

### WheelView 四轮图标

- 顶视图绘制: 车身轮廓 + 4个车轮圆 + 车轴连线
- 打滑检测: slip > 2% 时车轮变红并显示光晕效果
- 打滑数值: 有打滑时在车轮下方显示百分比

### PID 速查表 (本应用使用)

| PID | 名称 | 缩放 | 显示位置 |
|-----|------|------|----------|
| 0xB03 | Ethanol % | 直接 | card0 主网格 |
| 0x160 | ECT 水温 | raw-40 | card1 主网格 |
| 0x150 | IAT 进气温度 | raw-40 | card2 主网格 |
| 0x180 | Battery 电压 | raw×0.04 | card3 主网格 |
| 0x114 | Boost 压力 | raw×10→kPa→÷100=BAR | card4 主网格 |
| 0x140 | Ignition 点火角 | raw×0.5 | card5 主网格 |
| 0x320 | Air Fuel 空燃比 | 直接 | card6 主网格 + 底部文字 |
| 0x332 | LTFT 长期燃油修正 | % | card7 主网格 |
| 0x603 | Knock Retard 爆震控制 | 直接 | 底部爆震控制栏 |
| 0x604-0x607 | Knock CYL1-4 爆震计数 | 直接 | 底部爆震缸 |
| 0x710-0x713 | 轮速差 FL/FR/RL/RR | 直接 | 底部四轮图标 |
| 0x122 | TP Plate 节气门开度 | 直接% | 底部文字 |
| 0x110 | MAP 进气压力 | raw×10 kPa | 底部文字 |
| 0x116 | Air Charge 负荷 | 直接% | 底部文字 |

## APK 构建产物

| 类型 | 文件 | 大小 | 用途 |
|------|------|------|------|
| Release | `app/build/outputs/apk/release/app-release.apk` | **34 KB** | 实机车机部署 |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | 46 KB | 模拟器调试 |

### 零依赖架构

本项目**不依赖任何第三方库**，纯 Android Framework API：

- 无 `androidx.appcompat` — 直接使用 `Activity` + `Theme.NoTitleBar.Fullscreen`
- 无 `kotlin` 运行时 — 纯 Java
- 无 `.so` 原生库 — 纯 Java Canvas 绘制
- R8 代码压缩 + 资源压缩 + 日志剥离

### Release 构建配置

```groovy
buildTypes {
    release {
        minifyEnabled true       // R8 代码压缩
        shrinkResources true     // 移除未使用资源
        proguardFiles ...
    }
}
```

> **实机部署注意**: 如果车机 Android 4.2 拒绝安装，将 `app/build.gradle` 中 `targetSdk 28` 改为 `targetSdk 17` 重新构建。

## 设计风格

- **背景**: 纯黑 #000000
- **线框**: 极细深灰 #333333 (0.5dp)
- **数值**: 白色粗斜体 32sp
- **标签**: 浅灰 #999999 / 深灰 #555555
- **主色调**: 赛车蓝 #00D8FF, 亮绿 #3FB950, 警告红 #FF4444
- **字体风格**: 无衬线斜体数字 (系统默认 sans-serif italic)
- **布局**: 严格网格, 无圆角/渐变/阴影/动画

## 版本历史

### V2.0 — 硬核科技风格重构
- 4×2 网格 + 中英文双语标签 + 带刻度进度条
- 底部爆震控制栏: KNOCK RETARD + CYL1-4 + 四轮打滑图标
- 辅助数据列表 (油门/MAP/AFR/负荷)
- Footer 状态栏 (REC/LOG/ECU/时钟)
- 三层全屏防御 (主题+代码+回调)
- BOOST 单位从 kPa 改为 BAR

### V2.1 — 轻量化优化
- 移除 `androidx.appcompat` 依赖, 改为纯 Framework API
- APK 从 5.1MB 缩减至 34KB (缩小 150 倍)
- 启用 R8 代码压缩 + 资源压缩
- ProGuard 剥离调试日志 (Log.d/v/i)
