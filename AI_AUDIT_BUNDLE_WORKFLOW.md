# HondataDash — Git Bundle 审查包生成工作流

## 目的

每次完成一轮代码修改后，必须生成一个标准化的 Git Bundle 审查包，方便把完整源码、提交历史、tag、diff 和当前状态交给 ChatGPT 做独立核查。

这个流程用于避免以下问题：

* GitHub 页面缓存或 master / tag / release 不一致；
* 本地 APK 对应代码和远端代码不一致；
* 只看单个文件导致误判；
* ChatGPT 无法确认实际构建来源；
* Claude Code 修改后没有留下可审查证据。

---

## 总原则

1. **每轮修改完成后必须 commit。**
2. **Git Bundle 只包含已提交内容，不包含未提交修改。**
3. **如果存在未提交修改，必须额外导出 patch。**
4. **不得打包 keystore、签名密码、local.properties、build 产物。**
5. **审查包必须能让别人还原完整 Git 历史和当前 HEAD。**

---

## 生成审查包前的检查

在项目根目录执行：

```bash
git status --short
git branch --show-current
git log --oneline --decorate -10
git rev-parse HEAD
git describe --tags --always --dirty
```

如果 `git status --short` 有未提交文件，优先提交：

```bash
git add app/src/main/java app/src/main/res app/build.gradle README.md README_CN.md
git commit -m "Vx.x.x: describe current change"
```

如果暂时不能提交，则必须导出未提交 patch：

```bash
git diff > UNCOMMITTED_CHANGES.patch
git diff --cached > STAGED_CHANGES.patch
```

---

## 推荐输出目录

每次生成审查包时，在项目根目录创建：

```bash
mkdir -p audit_export
```

如果是 Windows PowerShell：

```powershell
New-Item -ItemType Directory -Force audit_export
```

---

## 生成 Git Bundle

在项目根目录执行：

```bash
git bundle create audit_export/HondataDash_full.bundle --all
```

说明：

* `--all` 会包含所有分支和 tag；
* ChatGPT 可通过这个 bundle 查看提交历史、tag、release 基线、HEAD；
* 这是最重要的审查文件。

---

## 生成版本信息文件

执行：

```bash
git status --short > audit_export/AUDIT_STATUS.txt
git branch --show-current > audit_export/AUDIT_BRANCH.txt
git rev-parse HEAD > audit_export/AUDIT_HEAD.txt
git describe --tags --always --dirty > audit_export/AUDIT_DESCRIBE.txt
git log --oneline --decorate --graph -30 > audit_export/AUDIT_LOG.txt
git tag --points-at HEAD > audit_export/AUDIT_TAGS_AT_HEAD.txt
```

---

## 生成与基准版本的 diff

如果当前修改基于某个明确版本，例如 `v2.6.2`，执行：

```bash
git diff v2.6.2..HEAD > audit_export/DIFF_FROM_v2.6.2.patch
git log --oneline --decorate v2.6.2..HEAD > audit_export/LOG_FROM_v2.6.2.txt
```

如果基准版本不是 `v2.6.2`，请替换为实际基准 tag。

例如：

```bash
git diff v2.6.3..HEAD > audit_export/DIFF_FROM_v2.6.3.patch
```

---

## 生成关键 grep 结果

每次涉及显示逻辑修改时，必须执行：

```bash
git grep -n "measureTextUnscaled" > audit_export/GREP_measureTextUnscaled.txt || true
git grep -n "clearAfSemanticContamination" > audit_export/GREP_clearAfSemanticContamination.txt || true
git grep -n "smoothMainScaleX" > audit_export/GREP_smoothMainScaleX.txt || true
git grep -n "displayedMainScaleX" > audit_export/GREP_displayedMainScaleX.txt || true
git grep -n "buildMainDisplayText" > audit_export/GREP_buildMainDisplayText.txt || true
git grep -n "measureMainTextSmart" > audit_export/GREP_measureMainTextSmart.txt || true
git grep -n "semanticMode\\[5\\]" > audit_export/GREP_semanticMode_AF.txt || true
git grep -n "VALID_RANGE" > audit_export/GREP_VALID_RANGE.txt || true
git grep -n "setTextScaleX" > audit_export/GREP_setTextScaleX.txt || true
git grep -n "setTextSize" > audit_export/GREP_setTextSize.txt || true
git grep -n "tv.getPaint().measureText" > audit_export/GREP_direct_measureText_BAD.txt || true
git grep -n "intPart" > audit_export/GREP_intPart_BAD.txt || true
git grep -n "decPart" > audit_export/GREP_decPart_BAD.txt || true
git grep -n "renderMainValueMaxFit" > audit_export/GREP_renderMainValueMaxFit_BAD.txt || true
```

注意：

* `BAD` 文件不一定必须存在内容；
* 如果 `GREP_direct_measureText_BAD.txt`、`GREP_intPart_BAD.txt`、`GREP_decPart_BAD.txt`、`GREP_renderMainValueMaxFit_BAD.txt` 有结果，必须在 `AUDIT_MANIFEST.md` 中解释原因；
* 理想情况下，这些 BAD 项应为空。

---

## 生成 APK 版本信息

如果本地已构建 APK，记录版本号：

```bash
grep -n "versionCode\\|versionName" app/build.gradle > audit_export/AUDIT_APP_VERSION.txt
```

如果使用 Gradle 构建：

```bash
./gradlew assembleDebug
```

构建结果不必放进 Git Bundle。
如需给 ChatGPT 验证实际安装包，可额外上传 APK，但源码审查以 Git Bundle 为准。

---

## 生成审查说明 AUDIT_MANIFEST.md

在 `audit_export/AUDIT_MANIFEST.md` 中写入以下内容：

```md
# HondataDash 审查包说明

## 当前目标

说明本轮修改目的，例如：

- 修复 A/F 在 DFCO/SYNC 下偏暗；
- 优化主数据 textScaleX 过渡；
- 添加 compact +/- sign；
- 不修改蓝牙、协议、状态机和 confidence。

## 当前 HEAD

填写：

- Branch:
- HEAD:
- Describe:
- Base tag:
- Target version:

## 本轮修改文件

列出本轮实际修改文件，例如：

- app/src/main/java/com/hondata/dash/MainActivity.java
- app/build.gradle
- README_CN.md

## 本轮禁止修改项

确认以下内容未修改：

- BluetoothSource.java
- HondataProtocol.java
- EngineStateTracker.java
- SensorData.java
- PID 映射
- 蓝牙连接逻辑
- confidence 算法
- Session MAX/MIN 业务逻辑

## 关键验证

说明以下 grep 结果是否符合预期：

- measureTextUnscaled 是否存在；
- direct tv.getPaint().measureText 是否不存在；
- clearAfSemanticContamination 是否存在；
- smoothMainScaleX 是否存在；
- A/F semantic gate 是否在 VALID_RANGE 前；
- intPart / decPart / renderMainValueMaxFit 是否不存在。

## 已知问题

如仍有问题，明确写出：

- 当前仍存在的问题；
- 复现方式；
- 相关录屏或截图文件名。
```

---

## 打包审查包

### Git Bash / WSL

```bash
cd audit_export
zip -r HondataDash_audit_package.zip .
cd ..
```

### PowerShell

```powershell
Compress-Archive -Path audit_export\* -DestinationPath audit_export\HondataDash_audit_package.zip -Force
```

最终上传给 ChatGPT 的推荐文件：

```text
audit_export/HondataDash_full.bundle
audit_export/HondataDash_audit_package.zip
```

如果有问题录屏，也一起上传：

```text
problem_video.mp4
problem_screenshot.jpg
```

---

## ChatGPT 审查时最需要的文件

优先级从高到低：

1. `HondataDash_full.bundle`
2. `HondataDash_audit_package.zip`
3. `DIFF_FROM_vX.X.X.patch`
4. 问题录屏
5. 问题截图
6. 实际安装 APK

---

## Claude Code 每次修改后的强制要求

每次完成修改后，必须输出以下内容：

```text
1. 当前 branch
2. 当前 HEAD commit
3. 当前 versionName / versionCode
4. 本轮修改文件列表
5. 是否修改了禁止修改项
6. grep 检查结果摘要
7. 是否已生成 Git Bundle
8. 是否已生成审查包 zip
```

示例：

```text
Branch: master
HEAD: abc1234 V2.6.3 semantic alpha fix
Version: 2.6.3 / 13
Modified files:
- MainActivity.java
- build.gradle
- README_CN.md

Forbidden files changed: No
Git bundle generated: audit_export/HondataDash_full.bundle
Audit package generated: audit_export/HondataDash_audit_package.zip
```

---

## 不允许的行为

Claude Code 不得：

* 只修改代码但不 commit；
* 只构建 APK 不生成 bundle；
* 生成 bundle 但不说明 HEAD；
* 修改蓝牙、协议、状态机后不说明；
* 删除 tag 或改写历史；
* 把 `.jks`、`.keystore`、`local.properties`、密码、token 打包进审查包；
* 在用户要求"精修"时大范围重构无关模块。

---

## 最终一句话

每次交付给 ChatGPT 审查时，必须做到：

```text
可以通过 Git Bundle 还原完整代码；
可以通过 AUDIT_MANIFEST.md 知道本轮改了什么；
可以通过 patch 看出相对基准版本的差异；
可以通过 grep 文件快速验证关键风险点。
```
