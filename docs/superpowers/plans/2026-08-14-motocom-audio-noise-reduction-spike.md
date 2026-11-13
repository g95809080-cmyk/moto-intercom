# MotoCom 通话降噪原生处理插入点与 RNNoise Spike 计划

## 0. 文档信息

| 项目 | 内容 |
| --- | --- |
| 产品 | 摩声 MotoCom |
| 文档类型 | 音频降噪技术 Spike 设计 |
| 日期 | 2026-08-14 |
| 状态 | 待执行 |
| 关联 PRD | `docs/product/2026-08-14-motocom-call-noise-reduction-prd.md` |
| 最终验收 | 需求方本人 |

> 该 Spike 的目标是证明“音频确实经过了可控的捕获后处理”，并获得 APM/RNNoise 的真实数据。Spike 通过不等于产品发布通过；没有真实蓝牙头盔和手机时，物理听音项目必须保留为 `Not Run`。

## 1. 已确认的产品边界

- 第一版只保证蓝牙头盔麦克风路线。
- 只处理本地麦克风上行音频，不处理本地耳机下行播放音频。
- 正式目标场景为头盔闭合、普通道路风噪。
- 语音可懂度优先：安静场景不变差，目标风噪场景人工听感改善，语音不能吞字。
- `M0/BASELINE` 固定为当前代码原样，不在 Spike 中先改动硬件 AEC/NS、WebRTC APM、Opus 或音频路由行为。
- 首版不要求用户增加防风罩、改装麦克风或更换硬件。
- 测试阶段允许经过授权的本地录音；发布版本不得保存通话录音。
- RNNoise 只有在已验证的设备和音频路线启用，其他情况回退到 `BASELINE`。
- RNNoise 的正式启用条件由版本内静态设备/路线白名单决定；运行时性能判断只负责回退。
- 如果 WebRTC APM 已达到 PRD 验收条件，RNNoise 不进入产品化；Spike 可以停在 APM 结论。
- 允许在效果确实更好时 fork 或重编 WebRTC AAR，但不能未经 Spike 证明就扩大维护范围。

## 2. Spike 目标

### 2.1 必须回答的问题

1. 当前 `stream-webrtc-android:1.3.9` 是否有稳定的捕获音频后处理插入点？
2. `ExternalAudioProcessingFactory` / `AudioProcessingFactory` 是否能在 Android 真机创建、回调并释放？
3. 本地 1.3.9 中声明的 `AudioRecordDataCallback` 是否实际接入了 `WebRtcAudioRecord`？
4. 实际蓝牙头盔麦克风路线的采样率、声道数、帧大小和处理线程是什么？
5. 设备实际使用的 Bluetooth profile 是什么，能否稳定检测并记录？
6. RNNoise 是否能在目标帧大小内实时运行？
7. 自定义处理是否会破坏 AEC、Opus、静音、VOX、路由或媒体会话生命周期？
8. 如果自定义路径失败，是否能无感回退到现有 `BASELINE`？

### 2.2 不在 Spike 范围内

- 不替换 WebRTC、Opus、RTP 或现有音频路由。
- 不创建第二个 `AudioRecord` 或第二套 AEC。
- 不接入手机麦克风和 BLE Audio 的正式验收。
- 不处理本地耳机下行播放音频。
- 不做 DeepFilterNet 的默认集成。
- 不新增普通用户可见的降噪设置。
- 不把录音上传、保存到生产日志或加入发布版本。
- 正式包不保留 RNNoise 手动实验开关；实验开关只存在 Debug/内部测试包。
- 不以模拟器、JVM 或静态源码检查替代真实听音结论。

## 3. 当前事实与技术假设

### 3.1 已确认事实

- [RiderAudioEngine.kt](../../../app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L348) 使用 `JavaAudioDeviceModule`、硬件 AEC/NS 和 `SamplesReadyCallback`。
- 当前 `SamplesReadyCallback` 只在 WebRTC 已取得录音数据后做音量/VOX 观测，不能作为发送前修改入口。
- [AudioSessionController.kt](../../../app/src/main/java/com/kuma/motointercom/AudioSessionController.kt) 统一管理音频会话和路由生命周期。
- 本地 Gradle 缓存中的 1.3.9 `sources.jar` 含有 `ExternalAudioProcessingFactory` 和 `AudioProcessingFactory` 相关类。
- 同一份源码中 `AudioRecordDataCallback` 有 Builder 字段和 setter，但静态检查未发现它被传入 `WebRtcAudioRecord` 或实际调用。

### 3.2 待验证假设

- `ExternalAudioProcessingFactory` 可能提供比重编整套 WebRTC 更小的接入面，但需要 Android 运行时验证。
- RNNoise 官方模型/示例以 48 kHz、约 10 ms 帧为主要使用方式；蓝牙路线可能需要重采样。
- 自定义降噪应作为现有捕获链路中的后处理，不能绕过 AEC；确切相对顺序由 Spike 结果决定。
- 硬件 NS 与 WebRTC 软件 NS 的实际质量可能因 OEM 而异，不能从配置值推断效果。

## 4. 候选插入路径

### 路径 A：ExternalAudioProcessingFactory

优先尝试当前 AAR 暴露的 `ExternalAudioProcessingFactory`：

1. 用最小化的 pass-through 实现创建音频处理工厂。
2. 通过 `PeerConnectionFactory.Builder.setAudioProcessingFactory` 注入。
3. 记录初始化、每帧回调、释放和异常状态。
4. 确认媒体仍能正常发布，远端仍能听到原始语音。
5. 再把 RNNoise 替换到同一个回调位置。

如果 Java 工厂回调无法建立，立即记录失败阶段，不继续在上层猜测。

### 路径 B：AudioRecordDataCallback

只做验证，不作为默认方案：

- 检查 Builder setter 是否存在并能编译。
- 运行时计数确认是否收到真实音频帧。
- 如果仍未被 `WebRtcAudioRecord` 调用，标记为 1.3.9 未接通接口。
- 不在 `SamplesReadyCallback` 中强行改 ByteBuffer，因为当前回调时序已经晚于原生发送。

### 路径 C：重编 WebRTC AAR

仅在路径 A 不可用且 RNNoise 仍有明确收益时进入：

- 保留当前 WebRTC 版本、编解码参数和音频会话行为。
- 在原生 Audio Processing 或正式 capture post-processing 接口接入模块。
- 固定构建环境、源码版本、补丁、许可证和 NOTICE/PATENTS。
- 额外评估长期升级成本和 CI 构建时间。

## 5. 实验矩阵

### 5.1 模式

| 编号 | 模式 | 目的 |
| --- | --- | --- |
| M0 | 当前代码原样 `BASELINE` | 建立现状和回退行为 |
| M1 | 关闭硬件 NS，使用 WebRTC 软件 NS | 判断 OEM 硬件 NS 是否影响效果 |
| M2 | 自定义工厂 pass-through | 证明插入点、生命周期和路由无回归 |
| M3 | 自定义工厂 + RNNoise（隐藏开关） | 仅当 APM 未达标时测量 RNNoise 的实时性和音质收益 |
| M4 | 模拟初始化失败/处理超时 | 验证自动回退和通话连续性 |

M3 不得默认与高等级 WebRTC NS 叠加。若需要组合测试，必须单独记录处理链路，且不能用组合结果替代单模块 A/B。如果 M1/M2 已达到 PRD 的语音和风噪目标，直接停止 M3，不为“使用 RNNoise”而继续扩展处理链路。

### 5.2 最小验证顺序

1. 编译检查：AAR API、JNI/NDK 和依赖可构建。
2. 初始化检查：创建、启动、停止、销毁均无异常。
3. 帧检查：采样率、声道、PCM 格式、帧大小和回调线程可观测。
4. 传输检查：远端能听到 pass-through 语音，Opus/WebRTC 状态不变。
5. 路由检查：蓝牙头盔连接、断开、重连和通话结束不泄漏资源。
6. 性能检查：每帧处理耗时、P95、回调超时、CPU、温度和电量。
7. 故障检查：初始化失败、异常帧和处理超时都能回退到 M0。
8. 音质检查：阶段一用公开样本做预验证；有真实设备后，阶段二按 PRD 的头盔闭合 + 普通道路风噪条件做远端人工 A/B。

## 6. RNNoise 集成约束

- 使用 native C/C++ 和 JNI，不在 Java 音频回调中执行重计算。
- 为每个音频处理实例明确创建、重置和销毁边界，不能跨媒体会话复用脏状态。
- 处理前检查实际采样率；需要 48 kHz 时明确重采样位置和开销。
- 处理以约 10 ms 帧为单位，禁止无界缓冲。
- 不保存输入帧，不上传语音，不把调试 PCM 写入普通日志。
- RNNoise 实验模式默认关闭，只有在 APM 未达标、设备/路线兼容表命中且实验开关显式打开时才可启用。
- 正式版本使用静态设备/路线白名单；运行时只允许将不兼容或过载设备回退到 `BASELINE`，不能自动把新设备加入白名单。
- 正式包移除 RNNoise 实验开关；没有真实设备证据时只发布 `BASELINE`。
- 设备不兼容、采样率不支持、JNI 初始化失败或处理超时，统一回退到 M0。

## 7. 观测字段

每次媒体会话至少记录以下非音频内容：

```text
noise_mode
audio_route
sample_rate
channel_count
frame_size
hardware_aec_available
hardware_ns_available
processor_init_result
processor_frame_count
processor_overrun_count
processor_fallback_reason
```

不得记录：

- PCM 数据；
- 通话录音；
- 语音转写文本；
- 联系人、对端身份或电话内容。

## 8. 物理测试依赖与 Not Run 规则

当前没有真实测试设备、蓝牙头盔和风噪样本，因此可以先完成：

- AAR API 和 JNI 构建验证；
- 单元测试和状态/回退测试；
- 静态链路检查；
- 合成音频的帧处理和性能测试；
- 公开风噪语音样本的算法预验证；该结果不能作为本产品发布证据；
- 录音权限、路由和资源释放的自动化验证。

以下项目在获得真实设备前必须标为 `Not Run`：

- 蓝牙头盔麦克风实际采样率和回调验证；
- 头盔闭合 + 普通道路风噪人工听音；
- 远端语音可懂度 A/B；
- 30 分钟真实通话功耗、温度和路由稳定性。

Spike 报告不得把自动化通过写成“降噪效果通过”。

如果尚未取得真实蓝牙头盔和手机，正式包只允许启用 `BASELINE`；RNNoise 或其他增强模式必须保持关闭，并在发布记录中标记为 `Not Run`。

## 9. Spike 通过标准

### 9.1 技术通过

- [ ] 至少一条原生插入路径能在真实媒体会话中接收到捕获帧。
- [ ] pass-through 模式不改变连接、Opus、AEC、静音、VOX 和路由语义。
- [ ] 每帧处理耗时 P95 不超过 10 ms/10 ms 帧。
- [ ] 初始化失败、超时和异常帧能回退到 `BASELINE`。
- [ ] 没有第二个 `AudioRecord` 或第二套音频会话。
- [ ] 处理实例在通话结束后完全释放。
- [ ] 许可证、NOTICE/PATENTS 和构建依赖有记录。

### 9.2 产品通过

- [ ] 头盔闭合 + 普通道路风噪下，人工听感比 M0 改善。
- [ ] 安静场景听感不比 M0 差。
- [ ] 语音没有吞字、明显机器人音、爆音或持续断续。
- [ ] RNNoise 只在已验证设备/路线启用。
- [ ] 无真实设备时，上述项目保持 `Not Run`，不作发布通过结论。

## 10. 结果分流

| 结果 | 后续动作 |
| --- | --- |
| APM 已达到 PRD 验收条件 | 停止 RNNoise 产品化，使用 APM 作为默认方案 |
| 路径 A 稳定，RNNoise 有收益 | 进入受限设备/路线试用，默认仍保留 M0 回退 |
| 路径 A 稳定，RNNoise 无明显收益 | 停止 RNNoise 默认化，继续调 WebRTC APM 和物理边界 |
| 路径 A 不稳定，但效果收益明确 | 评估路径 C 重编 AAR，先做维护成本评审 |
| 所有软件方案均无法满足目标 | 将强风噪标记为首版边界，不扩大本 PRD 硬件范围 |
| 没有真实设备证据 | 保留代码/自动化结果，产品状态为 `Not Run` |

## 11. Spike 产物

完成后应产出：

1. 插入路径结论：A、B 或 C，以及失败原因。
2. 真实帧格式和采样率记录。
3. Bluetooth profile 检测结果和静态白名单设计。
4. M0-M4 的性能和稳定性结果。
5. APM 是否已达到 PRD 验收条件，以及 RNNoise 是否值得继续的结论。
6. 设备/路线兼容表。
7. 物理测试 `PASS` / `FAIL` / `Not Run` 清单。
8. 许可证和构建依赖清单。
9. 对 PRD 默认模式、发布边界和下一步实现范围的修订建议。

## 12. 当前实现记录（2026-08-14）

已完成代码与自动化验证：

- `AudioNoiseReductionConfig` 默认 `BASELINE`；实验模式只有 Debug 构建且显式内部 opt-in 才能进入。
- `RiderAudioEngine` 保持单一 WebRTC 音频平台和单一采集链路，新增 1.3.9 `ExternalAudioProcessingFactory` pass-through Spike 插入点。
- 实验初始化或音频轨创建失败时，清理实验资源并重建完整 `BASELINE`；外部处理异常或单帧超时后永久旁路实验处理，不结束通话、不改路由。
- 关闭顺序固定为停止录音/播放、关闭 in-flight 外部处理、释放 PeerConnection/音频资源、销毁外部工厂；重复 close 有 instrumentation 覆盖。
- 诊断只记录非音频元数据：可用/实际路由、硬件能力与请求状态、采样率、声道、帧大小、callback/external P95、超时和回退原因；不保存 PCM 或通话录音。
- 新增 JVM 单测、AndroidTest Spike；Debug/Release assemble 和 Release lint 通过。

仍为 `Not Run`，不得写成降噪效果通过：

- 真实 Bluetooth 头盔上的 `initialize/process/reset` 回调与 in-flight close 运行证据；
- 头盔闭合 + 普通道路风噪的远端人工 A/B、安静场景不变差和语音不吞字；
- 30 分钟真实通话的 CPU、温度、电量、路由稳定性；
- RNNoise/其他增强算法，因为当前尚未证明 WebRTC APM 不达标。

在上述真机证据完成前，正式包只启用 `BASELINE`。
