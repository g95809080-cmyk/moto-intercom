# 3｜通话降噪排查

日期：2026-10-08。来源：项目独立只读排查会话 01a11922-25b1-7170-aa11-8a90ac53ca23。

**v1.2.0 确实请求了降噪，但没有证据证明故障骑行时降噪实际有效。** 最优先核查的是：硬件 NS 被选中后，软件 NS 被替代，而该硬件处理在头盔输入下效果不足或未成功启用。实际录音仍来自手机麦克风、持续风声误触发 VOX，也是候选原因，均需真机验证。

本次只读检查了历史源码、发布 APK、已有测试和日志，没有修改文件或分支，也没有构建、安装或重启应用。

**版本基线已核对。**

| 项目 | 故障版本 v1.2.0 | 指定 main |
|---|---|---|
| 提交 | tag 指向 `d479ec1dec5c4c197785d69b88d0872a366946cb` | `0f5e7b778ee0b945725c9506055d6137a7faf546` |
| APK 构建源码 | `408bfbe6ee871b6c5d2aeba55aa641ae9f9ad0bb`；与 tag 的 `app` 内容一致 | 功能批准提交为 `aee3b3f` |
| 版本 | `1.2.0`，versionCode `3` | `1.3.0+aee3b3f`，versionCode `4` |

v1.2 的版本当时写在 Gradle L15–16，尚无 `version.properties`。[旧版构建配置](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/build.gradle.kts#L11-L18)、[发布验证 L3、L19、L30–31](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/docs/verification/2026-09-12-release.md#L3)。

本地 [MotoCom-v1.2.0.apk](<C:/Users/kuma/Documents/摩托对讲机/output/MotoCom-v1.2.0.apk>) 的 SHA-256 与发布记录完全一致：`34E2ACCFDA3497D168959C0F3C88C476CAC46A514913C3D51E262D1265B37F3E`。其四种 ABI 的原生库均与缓存中的 `stream-webrtc-android:1.3.9` 相同；实际依赖不是目录中另一个旧 Google AAR。

关键调用链是：

`IntercomService.startAudioSession → AudioSessionController.start → RiderAudioEngine → JavaAudioDeviceModule → AudioRecord → 平台音效 / WebRTC APM → Opus → 对端`

入口见 v1.2 的 [IntercomService L799–856](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomService.kt#L799-L856)、[AudioSessionController L166–202](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioSessionController.kt#L166-L202)。

| 核查项 | 代码证据确认 | 实际启用、效果仍待确认 |
|---|---|---|
| 降噪 NS | 硬件 NS 请求为 `true`，正确的 `googNoiseSuppression=true` 也存在 | 当前录音 session 的 NS 是否创建成功、开启并有效 |
| 回声消除 AEC | 硬件 AEC 和 `googEchoCancellation` 均开启 | AEC 的启用不能证明风噪被抑制 |
| 自动增益 AGC | `googAutoGainControl=true`；应用没有自行创建 Android `AutomaticGainControl` 或读取其状态 | 原生最终配置，以及增益是否放大残余噪声 |
| 录音源 | 应用未覆盖库默认值；本地反编译确认默认为 `7`，即 `VOICE_COMMUNICATION` | 该录音实际使用手机还是头盔麦克风 |
| VOX | 根据 PCM 能量把发送音量切成 `0/1` | 风声是否导致持续开门；开门后没有应用自定义的风噪处理 |

配置依据为 v1.2 [RiderAudioEngine L352–414](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L352-L414)。录音默认值也与依赖 [JavaAudioDeviceModule L90–96](https://github.com/GetStream/webrtc-android/blob/1.3.9/stream-webrtc-android/src/main/java/org/webrtc/audio/JavaAudioDeviceModule.java#L90-L96) 一致。

**硬件降噪的失败回退存在值得验证的窗口。** 依赖中的 `setNS(true)` 先记录启用意图；之后才在录音 session 上创建 `NoiseSuppressor`、调用 `setEnabled`。创建或开启失败只写日志。应用没有接收实际音效失败结果并切换软件 NS 的逻辑。[依赖 WebRtcAudioEffects L78–159](https://github.com/GetStream/webrtc-android/blob/1.3.9/stream-webrtc-android/src/main/java/org/webrtc/audio/WebRtcAudioEffects.java#L78-L159)。

上游原生链路在硬件 NS 启用请求成功后关闭软件 NS；本地发布库也包含对应日志标记。这支持上述候选机制，但还不能证明故障骑行时走了该分支。[WebRTC 原生选择规则](https://webrtc.googlesource.com/src/+/fb0dca6c055cbf9e43af665d3c437eba6f43372e/media/engine/webrtc_voice_engine.cc)。

旧版还有 `googNoiseSupression`、`googNoisesuppression2` 等非标准键；本包原生字符串包含正确键，不包含这些键。它们没有构成更强降噪的证据。由于正确键已经存在，**修正拼写本身不足以解释或解决全部问题**。

**蓝牙界面状态没有确认实际麦克风。** Android 12+ 选择 SCO/BLE 通话设备，旧系统使用 SCO；服务收到路由回调后发布“当前音频源：蓝牙耳机”。应用没有把活动 `AudioRecord` 的实际输入设备作为这条状态的依据。因此头盔输入是否真正接管，需要录音配置日志确认。[v1.2 ModernAudioRoute L49–89](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/ModernAudioRoute.kt#L49-L89)、[服务状态 L803–806](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomService.kt#L803-L806)。

**VOX 对持续风声存在误开条件。** 它按 RMS 能量判断；持续高于关闭阈值就会保持发送，没有语音与风声分类。调整 VOX 灵敏度改变的是开门门限。相关能量值是 `dBFS + 固定偏移`，不能当作真实声压或降噪量。[v1.2 VoxGate L37–115](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/VoxGate.kt#L37-L115)、[RiderAudioEngine L440–508、L559–560](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L440-L508)。

指定 main 保留了相同的降噪约束、硬件优先策略、VOX 算法和 Opus 设置。新增录音、播放、PCM、RTP 就绪证据用于确认音频流动，没有增加 NS 效果验证。[main 音频配置 L383–464](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L383-L464)、[RiderMediaEvidence L15–25](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/app/src/main/java/com/kuma/motointercom/RiderMediaEvidence.kt#L15-L25)。现有差异不能证明 v1.3 已解决这项问题。

已有六月日志中，NS 显示开启，但同一 session 使用 `TYPE_BUILTIN_MIC`。[历史日志 L86–102](<C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/logs/20260627_114547/9688fa60_connection_filtered.txt:86>)。它早于 v1.2 发布，只能作为历史状态证据。v1.2 发布记录也明确未执行实际蓝牙耳机、双真机听音验收；现有初始化和资源复用测试没有测量降噪效果。

建议后续采用三个最小改动方向，本会话没有实施：

1. 先补齐实际输入设备、录音 session、音效启用结果与最终 APM 配置的诊断证据。
2. 基于 v1.2 做受控 A/B：仅将硬件 NS 改为 `false`，保留正确的软件 NS 请求，其余配置一致，比较头盔输入下的效果。
3. 根据证据处理硬件失败回退、路由确认或 VOX 误开；先不要同时改 AGC、AEC 和编码参数。

真机验证应先固定 APK、手机、头盔型号、固件和麦克风位置，双向分别记录“安静讲话、纯风声、风声中讲话”。确认录音输入确实为头盔，再比较硬件与软件 NS；VOX 开关另做一组测试，并在接收端保存听音样本。

应采集完整时间戳日志、`AudioRecordingConfigurations`、`dumpsys audio`、`dumpsys media.audio_policy`、`dumpsys media.audio_flinger`，重点关联同一 session 的实际输入、NS/AEC 创建及开启结果、VOX 状态。原生诊断日志若可用，再记录软件 NS 是否被关闭及 APM 最终配置。**目前最缺的是故障骑行当时的设备信息、完整日志和接收端声音样本；尚不能判断属于未启用、路由异常，还是已启用但风噪抑制不足。**

