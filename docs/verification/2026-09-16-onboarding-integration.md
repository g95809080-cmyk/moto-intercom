# v1.2.0 首次引导合入跨平台工作区

## 结论与范围

已将 `MotoIntercomApp-onboarding` 的已发布引导历史合入 `MotoIntercomApp-release-v1` / `feat/multi-audio-intercom`。合入起点为 `0a67901e0513e5797facf70265bbb64cca6a80cc`，引导来源为 `d479ec1`；通过 fast-forward 保留原提交历史，再恢复目标原有全部未提交内容。

本报告验证的是合并后的提交 **加目标原有 WIP**，不是只检出某个提交就能复现的干净版本。现有多音频协调、跨平台网络准备、音频就绪与 iOS 工作均保留；没有推送或发布新的安装包。

## 原工作区保全与审查

操作前：80 个已修改路径、8 个删除路径、423 个未跟踪文件；其中 Android 为 26 个修改文件及 15 个新增文件。部分修改仅是换行差异。暂存区原本为空。

完整快照位于相邻目录 `../output/onboarding-integration-backup-20260916-152356/`，含 900 个路径的清单、逐文件 SHA-256、原始文件副本、tracked/index patch 和完整状态。另保留恢复用 stash：`00c77ef2cca9a6b95cab794592f61c95d78c1315`，名称 `safety: pre-onboarding integration 2026-09-16`。stash 已 apply，未 drop；不要在当前工作区重复 apply。

| 原工作内容 | 本次审查与保留方式 |
| --- | --- |
| BLE 广播/扫描/GATT、网络准备、LAN/P2P 元数据与能力协商 | 引导仅调用现有启动/扫描意图，不访问 coordinator、不选取或认证 peer；相关原始文件逐字节保留 |
| 状态机、SessionOrchestrator、Service、V2 信令 | 产品状态单写者、生命周期、握手身份及过期回调处理未被引导覆盖；原 WIP 逐字节保留 |
| 音频就绪、电话中断、多音频协调、降噪实验 | 保留当前远端帧/本地路由/epoch/中断门控；引导等待 Connected + audioReady，不将媒体连接当作真实音频就绪 |
| Android 权限与 MainActivity | 保留蓝牙核心权限、audioReady listener、启动时权限重检；仅叠加 OnboardingPreferences 注入 |
| MainUiPolicy | 保留目标完整文件：已有等效 audioReady 显示实现及跨平台蓝牙提示 |
| iOS | 35 个原始路径逐字节一致；未修改 Swift、Package 或协议 fixtures |
| 原测试、产品/开发文档、Rasen/Codex 配置 | 保留原内容和原删除状态；原 WIP 仍未提交 |

900 个原始路径中，893 个逐字节一致或仍保持删除；7 个变化路径均属于引导范围：README、版本配置、DiscoverScreen、HomeScreen、MainActivity、MainScreen、strings.xml。`MainUiPolicy.kt` 与快照完全一致。原未跟踪文件均保留，证据见 `preservation.json` 与外部完整快照。

## 提交与文件移植结果

| 来源 | 处理 |
| --- | --- |
| `1c5c9fc` 引导及 v1.2.0 | 引导实现、锚点、资源、测试、产品说明及版本 3 / 1.2.0 一并合入 |
| `408bfbe` 合并 origin/main | 与第一父提交树内容一致，无新增源码变化；保留其历史关系 |
| `d479ec1` 发布验证 | 保留为 9 月 12 日历史证据，不把历史发布/设备结果冒充本次验收 |

目标 HEAD 是来源 HEAD 的祖先，因此选择 `git merge --ff-only d479ec1`，无需重新 cherry-pick。恢复 WIP 时只有 `MainUiPolicy.kt` 的等效音频等待实现产生内容冲突；保留目标版本后消解。`MainActivity.kt` 合并双方改动，Gradle 原始内容仅更新版本号。合并后没有未解决冲突，暂存区恢复为空。

必要适配仅涉及四条引导说明：明确蓝牙也用于发现与连接准备、属于启动所需权限、双方开启 Wi-Fi/蓝牙，以及按系统提示加入临时网络。新增组合回归覆盖：蓝牙拒绝 → 权限请求 → 系统设置入口 → 仅 scan/connect 仍不能启动 → advertise 也授予后允许一次真实启动意图，且不提前完成教学。

## 自动化验证

使用 Windows JDK `F:/Android/jbr`，临时 ASCII 工作区映射 `N:`，离线依赖缓存：

```powershell
$env:JAVA_HOME='F:/Android/jbr'
Set-Location N:/
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --rerun-tasks --console=plain
# 适配文案和新增回归后重新运行：
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain
```

首轮 2 分 52 秒，88 个任务全部执行；最终轮 1 分 16 秒，19 个执行、69 个 up-to-date，测试任务重新执行。

| 检查 | 最终结果 |
| --- | --- |
| 单元 / Robolectric / Compose 测试 | 77 suites，601 tests，0 failures / errors / skipped |
| OnboardingTest | 9 项通过，含新增跨平台蓝牙授权回归 |
| lintDebug | 0 Error / Fatal，75 Warning |
| assembleDebug | 通过 |
| assembleDebugAndroidTest | 通过，仅构建设备测试 APK |
| git diff --check / 冲突索引 | 通过 / 无未解决冲突 |
| 源码清单 | 282 个文件，验证后逐项哈希一致 |

源码清单 SHA-256：`7800f4a9b6d778d84a3f54502a4f43261332936ec7b822c2cad86df84a04d0d9`。

应用 APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `69f2502a933d60fe9b9ba95cba2499265b05e923d6f1cc32777b981a7e330135`。

设备测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，SHA-256 `50b397e2f040d38be1e6e55fe91dc2eb5cbafdd7dff7fa8569c6596a5d502d25`。

原始日志、JUnit XML 压缩包、Lint XML、源码及保全清单位于 [本次证据目录](evidence/2026-09-16-onboarding-integration/summary.json)。本次未运行模拟器交互、connected instrumentation、iOS/Xcode、双真机听音或远程 CI；本地自动化通过不代表跨平台真机验收完成。

## 架构审查

独立只读 reviewer 使用 `motointercom-product-architect` Skill 审查引导移植与原跨平台工作兼容性；最终审查记录见同目录 `evidence/2026-09-16-onboarding-integration/architecture-review.md`。
