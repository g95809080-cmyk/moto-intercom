# 当前工作区 Android 验证基线（2026-09-05）

## 范围与身份

- 工作区：`MotoIntercomApp-release-v1`，本地构建使用已有 `N:` 映射。
- 分支：`feat/multi-audio-intercom`。
- HEAD：`78d16b7bac433e89dce4540e9ab0a08f933c3ff3`。
- 验证对象：HEAD 加当前未提交、未跟踪的源代码；不能仅用 HEAD 重现本次结果。
- 源文件清单覆盖 Android 源码和测试、iOS、Gradle 配置及 wrapper，逐文件 SHA-256 见 evidence 目录。
- 验证前源清单 SHA-256：`579C27F7F6C861AED17AC1900A0A1745676B373E88D21AE2CA07FE5CC6CFB05D`。

## 当前改动整理

| 范围 | 当前工作区内容 | 后续收口关注点 |
| --- | --- | --- |
| Android 跨平台发现与网络准备 | BLE 扫描/广播/GATT、消息分片、能力交换、热点准备、LAN Presence 元数据、权限和 Service 接入 | Android↔Android 回归及 Android↔iPhone 实际建联 |
| 音频就绪与配对保存 | 远端音频帧、路由、电话中断及 generation 检查；UI 等待音频；AudioReady 后保存配对 | 双端真实声音、首帧超时、断线恢复与旧回调 |
| 音频与降噪实验 | 蓝牙 profile、诊断、外部处理保护、默认 BASELINE 的降噪配置 | 真实头盔/风噪听音；实验模式的自动化通过不等于降噪效果通过 |
| 测试 | Android 主实现有 26 个状态变更文件；单元/设备测试有 15 个状态变更文件（含未跟踪文件） | 本次执行单元测试；设备测试仅编译 APK |
| iOS 已提交基线 | Swift Package、协议 fixtures、网络/蓝牙/音频适配、SwiftUI 和 macOS CI 定义 | Package 未声明实际 WebRTC 依赖；缺少模块时使用 UnavailableWebRTCEngine；仍需 App target 和双端验收 |
| 文档与工具 | 产品/开发文档、KUM-46 证据、Rasen/Codex 配置更新，以及 tmp 产物 | 与产品代码分开梳理；保留历史证据的日期及适用版本 |

上述为改动范围梳理，不是逐行架构审查。没有修改应用行为，没有提交或发布。源码行为修复若后续发生，仍需遵守 AGENTS.md 的架构审查要求。

## 本次命令

```powershell
$env:JAVA_HOME = 'F:/Android/jbr'
Set-Location N:/
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --rerun-tasks --console=plain
```

使用已有依赖缓存离线运行，强制重跑任务，避免将历史测试输出误作本次结果。

## 本次结果

**PASS：BUILD SUCCESSFUL，退出码 0，耗时 2 分 30 秒，88 个任务全部执行。**

| 检查 | 结果 |
| --- | --- |
| testDebugUnitTest | 76 个 suite，585 项测试，0 failures / 0 errors / 0 skipped |
| lintDebug | 0 Error/Fatal，63 Warning |
| assembleDebug | PASS，57,598,743 bytes |
| assembleDebugAndroidTest | PASS，1,197,233 bytes；仅构建，未在设备执行 |
| 源文件一致性 | 验证前后逐文件哈希完全一致 |

应用 APK：`app/build/outputs/apk/debug/app-debug.apk`。

SHA-256：`B7FC0A08BC414AB47D03A3BA194A4962C7D36DEBB59A3986C1BDB18B3D1AD41B`。

测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

SHA-256：`50B397E2F040D38BE1E6E55FE91DC2EB5CBAFDD7DFF7FA8569C6596A5D502D25`。

Lint 警告分布：UnusedResources 22、ObsoleteSdkInt 11、InlinedApi 9、UseKtx 6、GradleDependency 5、IconLauncherShape 5、ButtonStyle 3、NestedWeights 1、OldTargetApi 1。另有编译期 deprecated API 提示。这里只记录现状，没有为消除非阻断警告扩展修改范围，也没有将警告全部认定为历史遗留。

本次无需修复源码。现有未提交变更原样保留，新增文件仅为验证文档与证据。

## 下一步

1. 以本报告的 HEAD + 源清单为 Android 本地验证基线；后续源码变化后再执行对应回归。
2. 在独立提交中收口 Android 跨平台/音频改动及测试，工具配置、产品文档和历史证据按用途分别整理；本次未执行提交。
3. 跨平台真机联调前，先补齐 iOS App target、实际 WebRTC 依赖和远端解码帧回调接入，再验证共同 LAN → BLE/热点引导 → 双向音频 → 中断/恢复。
4. 本地通过不替代架构审查、远程 CI 或真机验收。

## 验证边界

- 本次不包含 connectedDebugAndroidTest、模拟器交互、双真机、真实蓝牙音频、听音、射频、电量和温度验收。
- 本次不运行 iOS/Xcode，也不核查远程 CI、Linear 或 GitHub 发布状态。
- 历史 7 月物理验收和 8 月多音频测试只证明当时指定源码/APK，不能自动覆盖当前改动。
- 该基线用于下一轮回归和跨平台联调，不代表生产发布或跨平台互通已验收。

## 证据

目录：`evidence/2026-09-05-current-worktree/`。

- `git-status-before.txt`：验证前完整文件状态。
- `diff-numstat.txt`：已跟踪源码差异统计；未跟踪文件不计入该统计。
- `source-sha256-before.txt`：验证前源文件内容哈希。
- `android-gate.log` / `android-gate-exit.txt`：完整执行日志和退出码。
- `source-sha256-after.txt`：验证后源文件内容哈希，与 before 完全相同。
- `summary.json`：测试/Lint 计数、源码身份和 APK 哈希。
- `test-suites.csv` / `junit-results.zip`：本次测试汇总及原始 JUnit XML。
- `lint-results-debug.xml`：本次完整 Lint 结果。
