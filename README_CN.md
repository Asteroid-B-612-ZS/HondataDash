# HondataDash

HondataDash 是一个面向 **Hondata FlashPro 蓝牙数据**的轻量 Android 横屏仪表，主要用于 800×480 车机实时查看发动机与调校相关参数。

> 非官方个人/社区项目，与 Hondata, Inc. 无隶属或官方合作关系。

## 当前版本

**V2.0** 是当前代际版本。公开版本历史仅保留两个具有实际意义的代际版本：

- **V1.0** — 初代稳定版本，对应此前已验证的 V2.7.0 稳定基线。
- **V2.0** — 当前版本，采用新一代 OEM 风格界面、语义状态、可信显示、语义极值和更完整的瞬态处理。

早期开发阶段使用过的其它版本号和 RC 编号仅属于内部研发过程，不再作为公开发布历史保留。

## 主要特性

- Hondata FlashPro 蓝牙 SPP 数据读取。
- Android 4.2+ / API 17 轻量实现。
- 800×480 横屏 OEM 风格仪表界面。
- 4×2 主数据卡片：Ethanol、ECT、IAT、L.TRIM、MAP、A/F、IGN、S.TRIM。
- 5+5 对称式 RPM Shift Indicator。
- A/F、IGN、S.TRIM 的工况语义保护，减少换挡、DFCO、燃油切断和恢复阶段的误导性瞬态。
- MAP / RPM 保持实时响应。
- S.TRIM 使用解释可信度加权呈现。
- 所有主卡片保留双极值槽；极值更新采用语义准入，避免冷车、断油和短时尖峰污染历史。
- 燃油压力异常采用独立持续时间门控。
- 短时诊断事件保存在 RAM，不持续写磁盘。
- 纯 Android Framework 实现，无 AndroidX、Kotlin 或 native `.so` 依赖。

## 显示理念

> **真实数据不等于当前对驾驶者有用的数据。**

V2.0 区分数据有效性、当前工况解释价值、异常提示价值和极值记录资格。正常状态保持安静，瞬态状态避免误导，持续且可信的异常才提高视觉权重。

## 主界面

| ETHANOL | ECT | IAT | L.TRIM |
|---|---|---|---|
| MAP | A/F | IGN | S.TRIM |

每张主卡片均保留参数名称、单位、大号当前值、两组极值槽、数据条和实时位置提示。V2.0 改变的是极值的**准入条件与保留策略**，不是卡片布局或极值槽数量。

## V1.0 → V2.0

V1.0 建立了稳定的 FlashPro 蓝牙通信、数据显示、基础语义颜色、重连与会话能力。V2.0 在此基础上进一步引入 SHIFT/DFCO/OTHER_FUEL_CUT 分离、可信显示记忆、恢复门控、S.TRIM 解释可信度、语义极值、燃油压力持续异常门控和 RAM 诊断记忆，并完成 OEM 风格 UI 重构。

## 应用身份

V2.0：`io.github.asteroidb612zs.hondatadash`  
V1.0：`com.hondata.dash`

两代使用不同 applicationId，因此 Android 会把它们视为不同应用。V2.0 采用全新安装，不自动迁移 V1.0 设置。

## 构建环境

| 项目 | 配置 |
|---|---|
| compileSdk | 33 |
| minSdk | 17 |
| targetSdk | 28 |
| JDK | 17 |
| Java source compatibility | 11 |
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |

仓库不保存字体二进制；构建时需从官方来源恢复并校验指定字体。

## 限制

- 仅针对 Hondata FlashPro 蓝牙协议。
- 主要针对 800×480 横屏车机。
- 不提供 ECU 刷写、标定编辑或自动调校。
- 本项目显示阈值与语义规则不代表 Hondata 官方产品定义。

Designed by **ZhouQiZhi**
