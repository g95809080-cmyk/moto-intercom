# 6｜自动重连失效排查

日期：2026-10-08。来源：项目独立只读排查会话 01a11922-40c3-78a2-b727-ede5cc7de035。

**v1.2.0 有自动重连实现，但存在“恢复结束后不再追连”和“进入恢复却没有实际重建连接”的路径。** 长时间失联优先怀疑前者；短时间断音优先检查 Wi-Fi Direct 快速恢复路径。代码已确认这些条件存在，尚不能认定此次骑行实际命中了哪一条。

基线已核对：`v1.2.0` tag 指向 `d479ec1`，发布构建源码为 `408bfbe`，两者 `app` 内容一致；版本为 **1.2.0 / versionCode 3**。指定 main `0f5e7b7` 的应用版本为 **1.3.0+aee3b3f / code 4**。以下旧版行号均来自 `git show v1.2.0`。[旧版版本配置](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/build.gradle.kts#L15-L16)、[发布源码记录](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/docs/verification/2026-09-12-release.md#L3)

1. **连续三次失败后，原车友恢复意图被结束——代码证据确认，最能解释长时间掉线后不重连。**

   每轮恢复有 10 秒截止时间；后两轮采用固定 1.5 秒退避，清理和退避消耗该轮预算。第三次终态失败进入 `Resetting`，清理完成后进入 `Discovering`，没有创建第四次原目标恢复。全部超时时，约 30 秒就会结束恢复，实际时长受失败类型和调度影响。

   回到发现状态后，自动发起连接只选择 `isPreferred` 的车友。**普通“已配对”车友不满足这个条件**，所以重新靠近后可以被发现，却没有本机继续追连原车友的保证。这符合当时 KUM-34“重置后回到空闲发现”的合同，属于骑行恢复策略的缺口。[失败计数与重置：Coordinator 2646–2733](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingControlCoordinator.kt#L2646-L2733)、[重置完成：StateMachine 435–441](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomStateMachine.kt#L435-L441)、[优先车友条件：17–31](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/PreferredRiderAutoConnectPolicy.kt#L17-L31)

2. **仅 WebRTC 断开、P2P 组仍存在时，首轮恢复可能空转——条件分支已确认，现场命中待真机验证。**

   WebRTC `DISCONNECTED/FAILED/CLOSED` 会创建新 attempt，并直接打开原传输。Wi-Fi Direct 的 `connect()` 可以返回 `true`，但 `connectTarget()` 遇到已有 `SIGNALING_READY` 状态就直接返回；已连接设备也不满足 `AVAILABLE` 条件。此时没有为新 attempt 建立新的控制通道。

   新 attempt 又使旧媒体回调失效，因此旧 WebRTC 自行恢复不能直接完成新恢复尝试。没有可用 LAN 备选、也没有后续物理断线事件时，首轮可能等到 10 秒超时才进入下一轮。[恢复入口：Coordinator 501–695](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingControlCoordinator.kt#L501-L695)、[适配器返回与跳过：Tunnel 236–260、390–456](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt#L236-L456)

3. **对端应用重启后，旧目标锁无法接纳新会话——代码证据确认，是否发生过重启待验证。**

   恢复保留完整 `TargetLock`，包含原设备 ID 和原 `remoteSessionId`。同一手机重新启动对讲会生成新 session；发现匹配和 HELLO 校验都会拒绝这个新 session。代码没有在恢复中重新验证同一设备并创建新目标锁的流程。[保留目标锁：Coordinator 657–664](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingControlCoordinator.kt#L657-L664)、[HELLO 校验：SessionV2 444–458](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingSessionV2.kt#L444-L458)

4. **物理断线通知没有直接启动恢复；开关也没有覆盖所有入口——代码证据确认。**

   Wi-Fi Direct 的 `onDisconnected` 回调仅发布“信号丢失”提示，没有派发恢复事件，恢复启动依赖后续 WebRTC 或信令失败通知。另外，普通 `connectionLost()` 检查自动重连开关，媒体所属控制通道的 `ChannelClosed` 和 `SignalingSendFailed` 却直接调用恢复函数，绕过开关。后者解释的是开关行为不一致，不能单独解释“开启后不重连”。[物理断线回调：Service 909–911](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomService.kt#L909-L911)、[开关判断：Coordinator 569–586](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingControlCoordinator.kt#L569-L586)、[控制通道入口：1636–1752](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingControlCoordinator.kt#L1636-L1752)

设置调用链已经接通：设置页 → `MainActivity.saveAutomaticReconnectSetting()` 保存偏好 → Service → `AutomaticReconnectChanged` → Coordinator。开启开关本身只更新字段，不会立即恢复已结束的连接；关闭开关也不取消正在进行的恢复。主动本地断开、有效的对端 `DISCONNECT` 会结束当前 attempt 并回到发现状态；完整停止会取消恢复和调度。双端同时恢复已有按请求键选定唯一胜方的处理，本次未找到足以认定冲突处理失效的证据。[设置保存：Activity 444–451](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/MainActivity.kt#L444-L451)、[主动断开：Coordinator 1493–1588](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingControlCoordinator.kt#L1493-L1588)

前后台方面，Activity 退到后台只解绑监听，Service 保留运行，代码没有主动取消重连；Service 使用 `START_NOT_STICKY`，被销毁后不会自动恢复原运行状态。是否被系统结束，需要进程日志证明。[Activity 245–257](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/MainActivity.kt#L245-L257)、[Service 311–376](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomService.kt#L311-L376)

**指定 main 没有解决上述路径。** Coordinator、StateMachine、SessionOrchestrator、目标锁、恢复清理协调器和优先连接策略均与 v1.2 相同。main 增加了启动权限、位置检查、音频就绪及群组逻辑，但仍保留重置时 `nextAttempt=null`、物理断线只更新提示、`SIGNALING_READY` 跳过连接的行为。[main 重置路径：1974–1978](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/app/src/main/java/com/kuma/motointercom/IntercomService.kt#L1974-L1978)、[main 适配器判断：411](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt#L411)

最小修复建议是：

- 修通仅媒体失效时的首轮恢复：为新 attempt 重新验证已有 P2P 组并建立新信令，正确清理旧媒体绑定。
- 统一意外断线入口，全部经过主动断开判断、开关判断和当前会话校验。
- 若要满足骑行长失联恢复，保留原车友的恢复意图，重置后继续低频追连。这需要调整原 KUM-34 行为合同；对端 session 改变时，应重新验证并创建新 attempt。

后续真机验证建议按下表执行；本次未操作手机：

| 场景 | 应核对的结果 |
|---|---|
| 非优先车友失联 5 秒、20 秒、45 秒后返回 | 首次恢复时间；三轮失败、重置后是否继续原目标 |
| 保留 P2P 组，仅中断媒体 | 新 attempt 是否实际建立新信令，是否空等首轮截止 |
| 对端停止并重新启动应用 | 新旧 session ID、目标锁拒绝及重新接纳路径 |
| 自动重连关闭后分别中断媒体、关闭 TCP | 各断线入口是否一致遵守开关 |
| 主动断开、锁屏、后台分别测试 | 主动断开不进入恢复；后台进程和恢复调度是否仍存活 |

双端应保存 APK 身份、机型/系统、开关与优先车友状态，以及带时间戳的状态迁移、断线来源、`runtimeSessionId`、`attemptId`、完整目标锁、传输计划、截止时间、失败计数、清理完成时间和 HELLO 拒绝原因；同时采集 `MotoComP2P`、WebRTC、系统 Wi-Fi P2P 与进程退出日志。

**目前缺少本次 v1.2 骑行的双端日志、准确失联时长和对端进程状态。** 主目录现存日志主要来自 6—7 月，不能绑定此次故障。旧版后台解绑后，`publishLog()` 也无法写入页面日志，回到前台看不到记录不足以证明没有尝试恢复。现有时序测试使用虚拟时钟和注入通道；7 月历史验收记录过一次恢复通过，但 v1.2 发布未执行真实射频骑行验收。本次完成只读定位与报告，未运行构建或自动修复。[日志入口：Service 2379–2380](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomService.kt#L2379-L2380)、[时序测试：71–117](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/androidTest/java/com/kuma/motointercom/RecoveryTimingInstrumentationTest.kt#L71-L117)、[v1.2 验证范围](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/docs/releases/v1.2.0.md#L37)

