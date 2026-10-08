# 2｜VOX 灵敏度与自动开麦排查

日期：2026-10-08。来源：项目独立只读排查会话 01a11922-20e5-7253-abfd-0e32b78383a1。

最可能的问题是：**VOX 状态机在运行，但发送门控没有真正生效；高噪声下，灵敏度变化又会被自适应阈值覆盖。** 前者有很强的静态证据，仍需 v1.2.0 接收端录音确认；后者可以直接从旧版公式证明。

本会话只做读取与分析，未修改代码、文档、分支或应用数据，未运行构建、操作手机或给其他会话发消息。

**版本基线已核对。** `v1.2.0` tag 指向 `d479ec1dec5c4c197785d69b88d0872a366946cb`；正式 APK 的源码提交为 `408bfbe6ee871b6c5d2aeba55aa641ae9f9ad0bb`，两者差异只有发布文档和证据，应用代码相同。旧版为 `1.2.0 / versionCode 3`。指定 main `0f5e7b7` 为 `1.3.0 / code 4`，实际版本名带 `+aee3b3f` 后缀。以下故障分析均以 v1.2.0 为准。[发布来源记录](<C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/docs/verification/2026-09-12-release.md:3>)

1. **发送门控是首要嫌疑。**

   **代码证据确认：** v1.2.0 创建本地音轨时一直 `setEnabled(true)`，初始闭门、后续 VOX 翻转和手动静音都通过本地音轨 `setVolume(0/1)` 应用。没有针对 VOX 清零发送 PCM 或切换其他发送门控。[旧版 RiderAudioEngine.kt:418–427、502–510](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L418)

   上游 JNI 将这个调用转发到 `AudioSource.SetVolume`；上游 `LocalAudioSource` 没有重写它，接口默认实现为空。这高度指向本地发送端 API 使用错误。[JNI 实现](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/jni/pc/audio_track.cc)、[本地音源](https://webrtc.googlesource.com/src/+/refs/heads/main/pc/local_audio_source.h)、[接口默认实现](https://webrtc.googlesource.com/src/+/refs/heads/main/api/media_stream_interface.h)

   **有待真机验证：** 目前尚未建立所用 `stream-webrtc-android:1.3.9` 原生二进制与确切源码提交的对应关系，也没有本次骑行接收端录音。因此可以确认应用的门控调用方式，但不能把“该 APK 实际持续发送”写成实测结论。手动静音共用此路径，也应一起验证。

2. **滑块保存和下发链路已接通，但阈值会吞掉档位差异。**

   **代码证据确认：** 调用链为：

   `SettingsScreen.kt:495–507` → `MainActivity.kt:419–430` 保存并下发 → `IntercomService.kt:553–595` 增加控制 revision → `AudioSessionController.kt:26–29` → `RiderAudioEngine.kt:122–145` 重建 Gate。

   设置使用 SharedPreferences 同步 `commit()`，保存失败会提示；滑块范围为 0–100、11 个档位。静态检查没有发现控件未接线的问题。[旧版保存入口](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/MainActivity.kt#L419)

   实际开麦阈值为：

   `max(40 + (50 − sensitivity) × 0.24, noiseFloor + 8)`

   固定同一噪声底时，静态公式计算如下；数值是工程能量值，**不是实际声压级 SPL**。

   | 噪声底 | 灵敏度 0 | 灵敏度 50 | 灵敏度 100 |
   |---:|---:|---:|---:|
   | 20 | 52 | 40 | 28 |
   | 32 | 52 | 40 | 40 |
   | 44 | 52 | 52 | 52 |
   | 55 | 63 | 63 | 63 |

   噪声底达到 44 后，全范围滑块都可能得到相同阈值；达到 32 时，中、高灵敏度已经相同。[VoxGate.kt:25–54、135–147](<C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/app/src/main/java/com/kuma/motointercom/VoxGate.kt:25>)，该文件在 v1.2 与指定 main 完全一致。

   **有待真机验证：** 本次骑行的噪声底是否进入上述区间，还缺对应日志。

3. **校准和迟滞会进一步削弱使用感受。**

   **代码证据确认：** 每次灵敏度变化都会重建 Gate，重新进行 500ms 校准；校准期间不能开麦，而且讲话也会参与噪声底学习。正常开麦需要连续越阈值至少 25ms。

   噪声底只在 `LISTENING` 更新。开麦后，持续高于关闭阈值的风声可以维持 `OPEN`，或不断延长 `HANGOVER`；700ms 并不是强制关闭上限。检测依据只有整体 RMS 能量，没有区分人声和风噪。[VoxGate.kt:47–108](<C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/app/src/main/java/com/kuma/motointercom/VoxGate.kt:47>)

   **有待真机验证：** 用户遇到的是始终开门、始终闭门，还是状态翻转但声音不变，需要同期状态和接收音频区分。

4. **界面、输入电平和实际发送音频尚未形成闭环证据。**

   **代码证据确认：** 旧版按 PCM16 小端样本计算 RMS，再转成 `dBFS + 90`。界面状态来自 Gate 决策；日志中的 `volume` 也是期望值，没有等待 RTC 应用确认，更没有测量门控后的发送音频。[旧版 RiderAudioEngine.kt:440–561](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L440)

   蓝牙状态依据通信设备和 SCO 路由回调；应用没有读取实际 `AudioRecord` 输入设备来证明 PCM 来自头盔麦克风。[旧版 ModernAudioRoute.kt:31–88](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/ModernAudioRoute.kt#L31)

   **有待真机验证：** 头盔与手机输入是否正确、不同输入的增益及降噪是否导致阈值失配。界面显示蓝牙或 `OPEN`，不能单独证明头盔人声已发送。

**与 main 的差异。** `VoxGate`、设置存储和控制映射完全未变；RMS 计算、VOX 判定、日志和本地 `setVolume` 应用函数也未变。main 新增群组会话、`AudioIoGate`、录音/播放/PCM 活动证据及路由改动，但这些不能作为 VOX 已修复的证据。旧版 `applyCurrentTrackVolume` 在第 502 行，main 相同实现位于[第 555 行](<C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt:555>)。

**已有日志和测试的证明范围。** 6 月 29 日日志确实记录了 `LISTENING → OPEN → HANGOVER → LISTENING`；另一份约两分钟窗口中，VOX 记录一直处于 `OPEN/HANGOVER`，期望音量为 1。这说明历史状态机曾工作，也曾长时间不闭门。[状态翻转日志](<C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/logs/20260629_091919_vox/9688fa60_acceptance_vox_logcat.txt:366>)、[长时间开门日志](<C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/logs/20260629_091919_vox/efcb9031_final_vox_10_30_60_logcat.txt:2>)

这些日志早于 v1.2 发布，没有版本/SHA 标记，不能作为本次骑行或 v1.2 灵敏度验收证据。`VoxGateTest` 验证纯状态和低噪声阈值；UI 测试验证回调；控制器测试使用 `FakeEngine`。它们没有证明真实 WebRTC 发送被静音。[VoxGateTest](<C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/app/src/test/java/com/kuma/motointercom/VoxGateTest.kt:9>)、[旧版控制器测试:100–110](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/test/java/com/kuma/motointercom/AudioSessionControllerTest.kt#L100)

**最小修复建议：**

- 优先替换发送门控，要求闭门时发送音频被抑制，同时原始 PCM 仍持续提供给 VOX 检测器。
- 当前 `SamplesReadyCallback` 获取的是提交给 native **之后的副本**，直接清零 `samples.data` 无效；直接用 ADM microphone mute 又会让该回调收到零值，遮掉重新开麦所需的信号。修复需要选择有效的发送门控或提交 native 前的 PCM 门控位置。[固定 1.3.9 捕获实现:128–154](https://raw.githubusercontent.com/GetStream/webrtc-android/1.3.9/stream-webrtc-android/src/main/java/org/webrtc/audio/WebRtcAudioRecord.java)
- 再修灵敏度与噪声底的组合方式，使高噪声下仍有可解释的档位差异；调节灵敏度时保留噪声估计，避免每档重做校准。
- 补上控制 revision、实际输入设备、门控应用结果及接收端音频验证，验收不能只看状态名称。

**后续真机验证步骤与应采集证据：**

1. 核对两端均为 v1.2.0/code3，保存 APK 身份、手机/系统和头盔型号。先用手机输入完成双向听音，再单独测头盔输入。
2. 固定声源和距离，依次测灵敏度 0、50、100；每档停止拖动后等待至少 2 秒，记录讲话、安静和背景噪声。接收端重点确认：发送端显示 `LISTENING / volume=0` 时，是否仍能听到测试声音。
3. 沉默 10、30、60 秒后重新讲话，检查能否恢复开麦；再加入持续风噪，检查是否长期停留 `OPEN/HANGOVER`。静态测试和实际骑行结果分别记录。
4. 两端同步采集 `RiderAudioEngine`、`WebRtcAudioRecordExternal`、路由/音频焦点日志、系统录音配置及接收端录音。至少保留 `energy/noise/open/close/state`、灵敏度、静音状态、时间和实际输入设备。修复试验版还需采集原始及门控后能量、PCM 回调连续性。RTP 包计数本身不能证明语音被抑制。

旧版 VOX 诊断直接写 Android Log，未接入应用页面日志回调；只保存页面日志不足以完成这次定位。

目前最关键的证据缺口是：**v1.2.0 本次骑行同期日志、实际输入设备、三个灵敏度档位的阈值记录，以及闭门期间接收端的真实音频。**

