# RNNoise Beta 体验

这个包用来比较 RNNoise 与现有 WebRTC 降噪，基于双人 `main`。安装后名称为 **摩声 RNNoise Beta**，可以与现有摩声并排安装，数据各自保存；使用 Android debug 测试证书。

## 怎么试

1. 打开 Beta，完成权限和耳机准备，先使用默认的 RNNoise 对讲。
2. 在 **设置 → 降噪对比 · Beta** 查看当前选择。要比较 WebRTC，先回首页停止摩声，再关闭“启用 RNNoise”，重新启动。
3. 两种模式使用同一副耳机、相近环境、相同 VOX 设置，比较说话时的背景噪声、人声清楚程度、句首句尾是否完整、对讲延迟与持续使用的发热。
4. 需要诊断时可用已有日志导出功能；Beta 不录制或上传麦克风声音。

在线时开关禁用，选择会保存，在下一次启动生效。若设备录音格式无法满足 RNNoise，应用会停止并提示切回 WebRTC。

## 本次处理范围

- RNNoise 本地运行；开启时关闭 WebRTC 软件 NS，保留 AEC、AGC 与原来的 VOX/Opus。
- Beta 两模式都向 Android 请求 48 kHz 单声道 PCM16，每帧 480 samples。Android 负责硬件采样率适配；与正式版自动选择输入采样率的配置有所区别。
- RNNoise 位于采集回调、软件 APM 之前；软件 AEC 的实际表现仍需听音。
- 静音、VOX 关门、挂起/恢复或 producer/revision 更换后清除模型历史；开门时冷启动可能影响句首听感，体验时请留意。
- 日志 `Noise reduction=RNNoise/WebRTC` 表示当前source配置；RNNoise每处理1000个发送帧记录平均、最大耗时及超过10ms的帧数，不包含PCM。

## 版本和构建

正式版本字段保留原值。实验包身份也统一保存在 `version.properties`：`betaVersion`、`betaVersionCode`、`betaCommit`，Beta variant 输出 `betaVersion+短betaCommit`。本轮为 `1.3.0-beta.3+5e43840` / code11，功能提交为 `5e438402c8858571d897327e24a3060d0dfd6e5a`。code9/code10 的历史身份不复用；该构建只用于验证，未发布。

Beta.2 修复两个问题：Wi-Fi Direct 初始化期间，空闲断线广播会中断服务注册，导致重新扫描也找不到设备；SDK 静音填充推进缓冲区游标后，RNNoise 会把完整帧误判为空帧并停止对讲。捕获桥现在提供完整帧视图，SDK 原游标、格式检查、静音和 VOX 门控继续保留。

Beta.3 补齐 SDK 静音边界：保存本帧原始静音决定，回调处理后重新清零，避免模型历史输出重新写入静音帧。回调期间开启静音立即清零，取消静音从后续帧生效。本轮无手机，实机复测、人工听音、蓝牙及道路效果仍为待办；详见 `docs/verification/2026-10-23-kum-90.md`。

```powershell
$env:JAVA_HOME = 'F:/Android/jbr'
./gradlew.bat testDebugUnitTest testBetaUnitTest lintDebug lintBeta assembleBeta
./gradlew.bat -PtestBuildType=beta connectedBetaAndroidTest
```

需要 NDK `28.2.13676358`、CMake `3.22.1`，首次可用 SDK Manager 安装。官方代码和模型已随仓库固定，构建不会动态下载或替换模型。Windows中文路径问题可映射ASCII盘符后构建。APK位于 `app/build/outputs/apk/beta/`。

## 验证边界

自动检查验证模型确实处理PCM、生命周期/门控、构建和模拟器运行。双真实手机听音、普通蓝牙/SCO兼容性、道路强风效果、真实手机电量与温升仍需体验；模拟器耗时不代表手机性能。结果见KUM-88验证记录。
