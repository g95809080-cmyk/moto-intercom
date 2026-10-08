# 1｜多应用音频兼容排查

日期：2026-10-08。来源：项目独立只读排查会话 01a11922-1aa0-79b1-892f-ced442f9e382。

**若故障发生时使用蓝牙头盔，最可能的原因是持续占用 SCO 通信链路，导致音乐、导航的 A2DP 输出被系统挂起。** v1.2.0 同时存在焦点丢失处理和通信模式释放问题。代码路径已确认，但目前没有这次骑行的焦点栈与路由日志，尚不能确定手机实际命中了哪条路径。

本次仅完成只读定位，没有修改项目或运行构建、安装及真机测试。

版本基线已核对：`v1.2.0` tag 指向 `d479ec1dec5c4c197785d69b88d0872a366946cb`；发布 APK 源码为 `408bfbe6ee871b6c5d2aeba55aa641ae9f9ad0bb`，两者之间只有文档与证据变化，版本为 `1.2.0 / versionCode 3`。指定 main `0f5e7b778ee0b945725c9506055d6137a7faf546` 为 v1.3.0。以下故障证据均先取自 v1.2.0。[发布源码记录](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/docs/verification/2026-09-12-release.md#L3)、[main 版本来源](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/version.properties#L1)。

**代码证据确认的关键问题如下。**

1. **蓝牙对讲选择的是通信通道，没有实现第三方媒体混音。**  
   API 31+ 设置 `MODE_IN_COMMUNICATION`，选择 SCO 或 BLE Headset，且优先 SCO；旧系统直接启动 SCO。A2DP 在代码中只参与设备名称读取。AOSP Android 9、13 均存在 SCO 请求导致 A2DP 输出暂停的路径，因此可能出现“播放器进度继续，头盔里没有声音”。具体手机是否暂停、改路由或支持混音，仍需实测。[v1.2 路由:372](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L372)、[通信设备选择:49](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/ModernAudioRoute.kt#L49)、[旧 SCO:604](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L604)、[AOSP 音频策略](https://android.googlesource.com/platform/frameworks/av/+/android-13.0.0_r1/services/audiopolicy/managerdefault/AudioPolicyManager.cpp#6512)。

2. **永久焦点丢失被忽略，通信模式没有随媒体结束释放。**  
   `onAudioFocusChanged()` 没有 `AUDIOFOCUS_LOSS` 分支；其他应用取得长期焦点后，对讲仍可保持 `NORMAL`、WebRTC I/O 和通信路由。单测甚至明确要求这一行为。另一方面，媒体结束调用 `suspendForInterruption(restoreMode=false)`，只清除设备/SCO，没有撤销通信模式请求；恢复初始模式发生在整个音频运行时关闭时。这可能影响断开后仍在线的音乐与导航。[焦点处理:437](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/CommunicationAudioCoordinator.kt#L437)、[固化该行为的测试:192](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/test/java/com/kuma/motointercom/CommunicationAudioCoordinatorTest.kt#L192)、[媒体结束:372](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/CommunicationAudioCoordinator.kt#L372)、[路由释放:150](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L150)。

3. **临时焦点丢失后的自动恢复存在断点。**  
   收到 `LOSS_TRANSIENT` 后，`suspendForFocus()` 主动 `abandon()`，随后只等待 `GAIN`，没有安排恢复。释放请求会退出系统焦点栈，正常自动回授链路因此被切断。现有测试手动补发 `GAIN`，没有验证真实焦点栈。这更容易导致导航/其他应用中断后对讲不恢复，与“其他应用无声”需分别验证。[暂停路径:469](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/CommunicationAudioCoordinator.kt#L469)、[手动恢复测试:143](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/test/java/com/kuma/motointercom/CommunicationAudioCoordinatorTest.kt#L143)、[Android 焦点规则](https://developer.android.com/media/optimize/audio-focus#responding-to-an-audio-focus-change)。

焦点与 WebRTC 初始化已有正确的基础配置：申请 `GAIN_TRANSIENT_MAY_DUCK`，焦点请求和 Java ADM 都使用 `VOICE_COMMUNICATION / SPEECH`。调用链为 `IntercomManager.start → openMediaSession → beginMediaSession → 申请焦点 → 激活通信路由 → onRouteReady → resumeAudio`。静音和 VOX 关闭只调整本地发送轨道音量，**不会释放焦点或 SCO**。[焦点属性:77](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/CommunicationAudioCoordinator.kt#L77)、[会话入口:38](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomManager.kt#L38)、[WebRTC 属性:352](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L352)、[VOX 应用:502](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L502)。

蜂窝电话已有监听与恢复链路：响铃/接通时挂起，`IDLE` 后重新申请焦点并等待路由确认。v1.2 的原生暂停/恢复任务异步执行，仍需验证中断边界；不能用这些调用证明实际电话期间无串音。[电话与恢复:417](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/CommunicationAudioCoordinator.kt#L417)、[WebRTC I/O:148](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L148)。

**与指定 main 的差异不能视为共存问题已经修复。**

| 项目 | main 相对 v1.2 的变化 |
|---|---|
| 焦点与蓝牙策略 | MAY_DUCK、忽略永久丢失、临时丢失后 abandon、通信模式释放方式均保留；共存 PRD 与上述焦点测试内容未变。 |
| WebRTC | 新增群组会话、原生 I/O 证据及带 revision 的 `AudioIoGate`，减少旧任务覆盖新中断的风险。[main I/O 闸门:159](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L159)。 |
| 路由恢复 | 新增路由失效后重新申请焦点；条件只检查媒体有效和电话 IDLE，没有检查焦点中断状态。静态上可形成临时中断后立即回抢焦点的路径，需单独真机验证。这是 main 新增风险。[失效通知:59](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L59)、[重新申请:402](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/app/src/main/java/com/kuma/motointercom/CommunicationAudioCoordinator.kt#L402)。 |

PRD 要求音乐尽量继续、导航可用，并要求电话隔离与自动恢复。但历史共存验收只检查合成客户端收到降低音量回调，没有播放真实高德、网易云，也没有覆盖 SCO 下的可听性；报告明确将这些留给真机。v1.2 发布验收也未执行实际蓝牙耳机测试。[PRD 目标:33](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/docs/product/2026-08-13-motocom-multi-audio-intercom-prd.md#L33)、[验收缺口:40](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/docs/testing/2026-08-14-multi-audio-no-device-test-report.md#L40)、[发布边界:37](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/docs/releases/v1.2.0.md#L37)。

建议的最小修复顺序是：

- 补齐焦点状态：永久丢失进入明确的焦点中断状态；临时丢失保留正常回授链路；延迟获准时等待回调，避免周期性抢焦点。同步修正现有单测。
- 媒体结束或无焦点时，明确释放本应用的通信路由与模式请求；电话中断单独处理。main 的路由重试必须遵守同一焦点授权状态。
- 在上述修复后验证持续 SCO 的共存能力。若设备确实不能同时输出媒体，需要选择支持混音的路由或采集方案；单改焦点常量无法补足硬件路由能力。

后续真机验证应先使用实际 v1.2.0，按同一顺序分别测试手机外放和故障头盔：

1. 音乐先播再连接对讲；对讲先连接再播放音乐；分别触发高德播报，再测试三者同时运行。
2. 每次观察播放器是否暂停、进度是否继续、声音是否转到手机；比较静音、VOX 关闭、断开车友但保持在线、完整停止后的变化。
3. 验证响铃、拒接、接听/挂断，以及一次其他 VoIP 中断；检查对讲最终恢复和用户停止后无误恢复。

各阶段应采集持续 `logcat`，以及 `dumpsys audio`、`media.audio_policy`、`media.audio_flinger`、`media_session`、`bluetooth_manager`；电话测试补充 `telephony.registry`。重点保留焦点拥有者/变化、模式拥有者、SCO/A2DP 状态、实际输出设备、播放器状态与音量、WebRTC 原生录放启停，并标记操作时间。

尚缺的关键证据是：故障手机和系统版本、头盔型号及 profile、高德/网易云版本、实际 APK 身份、无声时播放器状态，以及本次骑行的系统音频快照。现有保存记录不足以把上述静态路径升级为这次骑行的确定根因。

