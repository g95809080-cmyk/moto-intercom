## Why

用户希望亲自试用 RNNoise，并与当前 WebRTC 降噪对比；提供独立安装的双人实验 Beta。

## What Changes

- 固定官方 RNNoise 代码与模型，通过 NDK/JNI 处理当前有效的发送 PCM。
- Beta 默认 RNNoise，可在停止摩声后切换 WebRTC；保留 AEC、AGC、VOX 与现有连接协议。
- 独立包名、测试证书、明确 Beta 版号、APK 与中文体验说明。

## Capabilities

### New Capabilities
- `rnnoise-beta`: 实时降噪对照、生命周期隔离与独立测试包。

### Modified Capabilities
无。

## Impact

Android 音频引擎、控制偏好与设置、启动 Intent、Gradle/NDK；无服务端、协议或四人分支改动。真实双机/蓝牙/道路听音留待用户体验。
