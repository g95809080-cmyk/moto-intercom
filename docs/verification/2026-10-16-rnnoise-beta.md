# KUM-88：RNNoise Beta 验证

本轮交付用于双人摩声的 RNNoise / WebRTC 对照。基于 `main`，独立包名和测试证书，可与现有摩声并排安装。真实手机听音、蓝牙/SCO、道路强风与温升未执行。

## 固定来源

- Base：`a72cea7c1ff1879a2b6175b17881d0686c22d9c4`。
- 功能 Source：`dc7a45d01fc837f795cdcbffa5bf7616a25e5e85`；其后的打包改动仅允许版本、文档和证据。
- Linear：[KUM-88](https://linear.app/kuma999/issue/KUM-88)。[Draft PR #61](https://github.com/g95809080-cmyk/moto-intercom/pull/61)。
- RNNoise：`70f1d256acd4b34a572f999a05c87bf00b67730d`，默认量化 DNN；源码、模型来源与完整校验见 `app/src/main/cpp/third_party/rnnoise/README.md`。
- 模型 C 文件 SHA256：`d6021b7697677c4d2274c912975e143765552b0e6f25500aa660fdb4a9849be5`，78,003,012 bytes；独立复核一致。

## 环境与复现

Windows / JDK 17（`F:/Android/jbr`）、NDK `28.2.13676358`、CMake `3.22.1`。中文工作区使用 `subst R:` 映射后执行 Gradle。CMake 保留 CMP0125 OLD，规避 Windows CMake 3.22.1 在编译器检查阶段崩溃。

```powershell
./gradlew.bat testDebugUnitTest testBetaUnitTest lintDebug lintBeta assembleBeta assembleDebugAndroidTest --console=plain
./gradlew.bat -PtestBuildType=beta '-Pandroid.testInstrumentationRunnerArguments.class=com.kuma.motointercom.RnNoiseInstrumentationTest,com.kuma.motointercom.RnNoiseEngineInstrumentationTest,com.kuma.motointercom.VoxTransmitInstrumentationTest,com.kuma.motointercom.VoxBackgroundInstrumentationTest,com.kuma.motointercom.NativeAudioIoGateInstrumentationTest,com.kuma.motointercom.RiderAudioEngineHotSessionTest' connectedBetaAndroidTest --console=plain
```

仪器验证使用本次启动的 API36 x86_64 AVD `emulator-5560`，目标为独立 Beta 包；没有安装或修改用户手机。arm64-v8a / armeabi-v7a 仅完成编译与 ELF 对齐核验，不计为设备运行通过。

## 结果

| 项目 | 结果 |
| --- | --- |
| Debug JVM | 96 suites / 806 tests / 0 failures / 0 errors / 0 skipped |
| Beta JVM | 96 suites / 806 tests / 0 failures / 0 errors / 0 skipped |
| Debug / Beta Lint | 各 0 errors / 71 warnings |
| 三 ABI Beta、Debug test APK 构建 | PASS |
| API36 Beta 仪器 | 6 类 / 21 tests / 0 failures / 0 errors / 0 skipped |
| 模型真实性 | 非零噪声 PCM 被实际修改并降低 RMS；非占位或静音实现 |

仪器分布：`RnNoiseInstrumentationTest` 8、`RnNoiseEngineInstrumentationTest` 3、`VoxTransmitInstrumentationTest` 2、`VoxBackgroundInstrumentationTest` 3、`NativeAudioIoGateInstrumentationTest` 3、`RiderAudioEngineHotSessionTest` 2。包含直连 ByteBuffer 边界、异常格式拒绝、完整历史 reset、process/close 互斥、采集所有权、热会话和真实 native PCM 静音/VOX 测试。

1000 个计时帧：avg **538.56 μs**，p50 **261 μs**，p95 **1298 μs**，max **25,858 μs**，**7/1000** 超过 10 ms。记录长尾，未把平均值当作无掉帧保证。原始计时见 `evidence/2026-10-16-rnnoise-beta/rnnoise-benchmark.txt`；完整仪器结果见 `native-results.xml`。

此前回归中发现的 producer 历史继承和 Beta 设置测试越出 viewport 已修复并重新验证。最终固定 Source 的独立架构复审无剩余 P0/P1；打包前 `betaCommit` 从 bootstrap Base 更新为获批功能 Source，正式版三个字段保持原值。

最终 APK：`MotoCom-RNNoise-1.3.0-beta.1+dc7a45d.apk`，**58,258,914 bytes**。包名 `com.kuma.motointercom.rnnoise.beta`，显示名“摩声 RNNoise Beta”，版本 `1.3.0-beta.1+dc7a45d` / code9，minSdk23、targetSdk36。使用 Android Debug 证书，APK v1/v2 签名验证通过；Beta 为 debuggable。没有调用分发证书或改动正式版本字段。

SHA256：`c0dd4afcf6ed6f456c3726c567dd0c3825b28270eab2420eade72e5113c58391`。`zipalign -c -P 16 4` 通过；APK 内三种 ABI 的 `libmoto_rnnoise.so` LOAD 对齐均为 `0x4000`，许可证 asset 存在。最终 APK 再次在该 AVD 安装并冷启动成功，PackageManager 返回正确版本。详细身份见 `apk-info.json`，签名见 `apk-signature.txt`。

## 独立架构复审

`motointercom-product-architect` 只读复审结论 **APPROVED**，P0 无、P1 无；固定 Base/Head 如上。复审者独立核验了 Debug/Beta XML、Lint、21 项仪器结果、负载日志及 320 dp 截图。下一门槛为元数据打包、签名/校验与 PR/CI；不合并 main 或发布正式版。

固定功能 Source 的 [GitHub CI run 38868719425](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38868719425) 两个 job 均 **success**：完整 Debug/Beta JVM、两种 Lint、Debug/Beta APK/test APK 构建，以及 API36 完整 Debug instrumentation。verify 日志 `BUILD SUCCESSFUL in 9m 48s`，instrumentation 日志 `BUILD SUCCESSFUL in 4m 16s`。

后续版本/证据提交的 CI 与最终交付状态通过 PR #61 和 KUM-88 的完成记录单独追踪，避免把上述固定 Source 运行冒充最终打包 Head 运行。

## 界面与使用契约

在 411 dp 和 320 dp 宽度检查了设置页，RNNoise 标签、当前模式、说明与开关完整可读。离线可切换，WebRTC 选择在强制停止/重开后保持，再切回 RNNoise；运行中开关的 UI 节点 `enabled=false`。Compose 回归同时检查回调、选择文字和在线禁用。截图见 `evidence/2026-10-16-rnnoise-beta/`。静态样式审计 0 findings；它不覆盖 Kotlin Compose，界面结论来自实际截图与交互。

RNNoise 实际处理发送 PCM，模型在静音/VOX 关门期间停止推进。重新发送、I/O revision 改变或采集 producer 更换后清空历史；同一 revision 更换线程的回归先暖机旧模型，再与 fresh 实例逐字节对照。失效 producer 在控制锁等待后重新终检，不能推进 detector 或模型。

## 验证范围

RNNoise 位于采集回调、软件 APM 之前；开启时停用 WebRTC 软件 NS，保留 AEC、AGC。两种 Beta 模式统一请求 48 kHz mono PCM16；实际格式不符会清零、报告错误并停止，用户可停止后选 WebRTC。自动测试不证明所有 SCO 设备都支持这一请求。

模拟器性能数值只描述该主机上的 JNI 模型处理，不包含 APM、编码、网络、播放或端到端延迟。冷启动、骑行强风下吞字/人声失真、双机回声、真实手机电量与温升仍需体验。
