## Context

Base a72cea7，双人 1.x main；KUM-88。现有采集回调由 ASM 补丁在 nativeDataIsRecorded 前连接，已经在 audioControlLock → recordingOwner 内终检、执行 VOX 与清零。

## Goals / Non-Goals

实现可安装的 RNNoise 降噪对照 Beta。连接状态机、协议、AEC/AGC 和四人产品线保持现有契约。真实双机、SCO 和道路听音不以模拟器替代。

## Decisions

- 官方 RNNoise 70f1d256 + model_version 校验模型随源码固定，NDK/JNI 每引擎独占状态，成功处理不在回调分配原生堆内存。
- Beta 两模式统一向 AudioRecord 请求 48 kHz mono，由平台转换设备速率；回调必须实际为 PCM16、480 samples。不自写重采样器，不静默退回未经选定的算法。
- SettingsSwitchRow 复用现有控件。RNNoise 默认开；仅 Offline 可切换并持久化，经过 startIntent/onStartCommand 进入 initialAudioControls。每个 runtime 的 source 固定 NS 模式，RNNoise 开时 WebRTC 软件 NS 关闭，硬件 NS 仍关闭；保留 AEC/AGC。
- 保持 VOX 读取原始 PCM 能量；仅发送门打开时跑 RNNoise。静音/VOX关门不推进模型并清历史，恢复、revision改变和模式新建都重置。处理在 producer/revision 终检之后，使用控制锁→owner→processor顺序。释放不持锁做设备 join。
- 独立 Beta build type/package .rnnoise.beta，debug 测试签名；version.properties 的 betaVersion/betaVersionCode/betaCommit 标识本次源码，正式版本字段不变。

## Risks / Trade-offs

- RNNoise 在软件 APM 之前，软件 AEC 顺序与真实听感需实测；不声称是 AEC 后置处理。
- 请求 48k 不证明所有 SCO 支持。格式不符静音并报可恢复提示，停止后切 WebRTC；两模式与正式输入格式有所区别。
- VOX 重开时 RNNoise 冷启动有模型窗延迟；检验新声音是否吞字，需要真人听音。
- 只交付 Beta APK、校验和与独立 PR；不自动合并或发布正式版本。
