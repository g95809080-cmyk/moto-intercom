## Boundaries

手动 mute 优先于 VOX；phone/focus suspend 保持独立 I/O 控制。每帧原始 PCM 的测量、设置 revision 判定与实际清零在同一 controls lock 内完成。闭麦不停止原始采集；关闭/挂起/异常时清零，过期帧不发布能量。PCM 不写入日志。

项目 `stream-webrtc-android:1.3.9` 的 native 对应 GetStream m125.4。其全 muted 会停止录音，encoding.active=false 会停止发送流；正对照可收到 PCM，而冷启动静音实验不能持续采集。不能套用 current upstream main 的行为。

1.3.9 Builder 公开 `setAudioRecordDataCallback`，但创建 recorder 时漏接。构建时通过 AGP ASM 只改三个 SDK Java 类：Builder 在 recorder 构造后绑定实例 callback；recorder 增加 callback field/helper；AudioRecordThread.run 在原 nativeDataIsRecorded 调用前执行 helper。JNI 名称、描述符、native .so 和版本保持原样。Builder/字段/原调用完整形状断言，版本 strictly 1.3.9，变化或重复修补使构建失败。

回调是 engine 实例闭包，无全局 native pointer registry。录音线程绝对索引读写实际 direct ByteBuffer，保持 position/limit；先算 RMS/VOX，再闭门原位清零。无第二个 AudioRecord，也不依赖 debug SamplesReady 的编码后副本。异常清零并只报告一次 engine 故障。

原生双 PeerConnection 测试经该 public callback 注入可检测的合成麦克风帧，验证闭门仍能测量、JNI 前同一帧为零、语音可重开、manual mute 优先、旧 revision 无效；测试合成音不作为真实风噪听感证据。

来源：[1.3.9 Builder](https://github.com/GetStream/webrtc-android/blob/1.3.9/stream-webrtc-android/src/main/java/org/webrtc/audio/JavaAudioDeviceModule.java)、[native 更新记录](https://github.com/GetStream/webrtc-android/commit/37b8867763b8b64f2050b3a5e959e1eef1b687d9)、[m125.4 AudioState](https://github.com/GetStream/webrtc/blob/m125.4/audio/audio_state.cc)。

默认50灵敏度保持8单位噪声余量；0–100分别13–3。闭门限仍高于已学背景，避免最高灵敏度永远 hangover。门限是工程能量值，不是真实 dB SPL。
