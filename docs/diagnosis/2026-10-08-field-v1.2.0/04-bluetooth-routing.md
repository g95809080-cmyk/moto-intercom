# 4｜已连接蓝牙仍外放排查

日期：2026-10-08。来源：项目独立只读排查会话 01a11922-2d0e-72b0-9c61-dbd24deb1990。

只读定位完成。**最可能的根因是：首次蓝牙通话选路失败后，v1.2.0 转为外放，却缺少主动恢复蓝牙的完整流程。**“听筒→蓝牙”会替换当前路由并重新申请蓝牙，因此能恢复。代码已经确认这些缺口；故障手机具体触发了哪一条，仍需当次日志验证。

基线已核准：`v1.2.0` 标签指向 `d479ec1dec5c4c197785d69b88d0872a366946cb`，实际发布 APK 源码为 `408bfbe6ee871b6c5d2aeba55aa641ae9f9ad0bb`，两者 `app` 代码无差异，版本为 **1.2.0/code3**。比较对象固定为 `0f5e7b778ee0b945725c9506055d6137a7faf546`，对应 **1.3.0+aee3b3f/code4**。旧版尚未使用 `version.properties`。[v1.2 发布来源](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/docs/verification/2026-09-12-release.md#L3)，[指定 main 版本](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/version.properties#L1-L3)。

**关键调用链与代码证据**

启动服务时先建立音频引擎、路由控制器和协调器；接受连接、打开媒体会话后，才申请焦点、暂停音频 I/O、选择首选设备。默认首选为蓝牙，但保存过的选择会覆盖默认值。路由报告就绪后，协调器恢复 I/O。[旧版 AudioSessionController.kt:48–75、176–202](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioSessionController.kt#L48-L75)，[CommunicationAudioCoordinator.kt:481–495](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/CommunicationAudioCoordinator.kt#L481-L495)。

1. **Android 12 及以上：失败后重试的是扬声器。——代码证据确认**

   `setCommunicationDevice()` 被拒，或暂时找不到 SCO/BLE 通信设备时，立即回退外放。随后三次、间隔 250ms 的恢复尝试都在申请**扬声器**。`modernFallbackActive=true` 又阻止非蓝牙通信设备变化回调重新选蓝牙。蓝牙恢复主要依赖设备新增等外部事件；已经连接的耳机未必再产生新增事件。

   即使蓝牙请求被接受，1.5 秒后的检查也只查询、记录状态；未生效时没有继续申请或明确结束等待。[旧版 AudioRouteController.kt:364–433](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L364-L433)，[扬声器恢复逻辑:485–570](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L485-L570)。

   **待真机验证：**首次请求是否确实被拒或缺少通信设备，以及之后是否没有可触发恢复的回调。Android 官方要求等待实际通信设备生效，并在错误时清除请求后重新申请。[官方选路说明](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager#Set-communication-device)。

2. **Android 6–11：初始 SCO 断开广播可能提前取消连接。——处理缺口确认，时序待验证**

   旧版先注册 SCO 广播，再调用 `startBluetoothSco()`。收到 `DISCONNECTED` 就回退外放，执行 `stopBluetoothSco()`，没有区分初始 sticky 广播与本次连接失败，也未处理 `CONNECTING` 阶段。

   官方说明该广播是 sticky，SCO 建立可能耗时数秒。因此，初始断开状态若在启动请求之后被处理，就可能把尚未完成的 SCO 请求停止。[旧版广播处理:57–92](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L57-L92)，[注册与启动:177–189](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L177-L189)，[Android SCO 接口说明](https://developer.android.com/reference/android/media/AudioManager#startBluetoothSco())。

3. **外放也会报告“路由就绪”。——代码证据确认**

   扬声器回退成功后同样调用 `onRouteReady()`，协调器随即恢复 WebRTC 音频。于是可以出现“连接成功、有声音，但从手机外放”。连接状态不能证明蓝牙通话路由已经生效。[旧版回退完成:573–585](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L573-L585)，[恢复 I/O:403–413](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/CommunicationAudioCoordinator.kt#L403-L413)。

**手动切换为何有效**

Android 12+ 切听筒会先替换 `communicationDevice`，随后切蓝牙重新申请 SCO/BLE 设备；旧系统则先停止 SCO、关闭外放，再启动 SCO。每次选择都会增加路由代次，使旧选择的延迟任务失效。[旧版选择及听筒逻辑:113–221](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/AudioRouteController.kt#L113-L221)。

代码也允许再次点击已经选中的蓝牙，服务仍会重新申请路由。因此，“只有先切听筒才能恢复”需要做对照验证：若直接重选蓝牙也有效，重点是补自动重试；若只有两步切换有效，则还要检查系统残留路由或 OEM 行为。[旧版设置点击:394–406](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SettingsScreen.kt#L394-L406)，[服务转发:428–434](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomService.kt#L428-L434)。

**WebRTC 与 main 差异**

未找到“WebRTC 默认管理器抢走蓝牙路由”的直接证据。v1.2 显式创建并注入 `JavaAudioDeviceModule`，输出使用 `USAGE_VOICE_COMMUNICATION`；现有 1.3.9 库的相关 Java 类中未查到修改模式、扬声器或蓝牙选路的调用。原生层及 OEM 音频策略仍需运行日志确认。[旧版 RiderAudioEngine.kt:352–375](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt#L352-L375)。

| 项目 | v1.2.0 | 指定 main |
|---|---|---|
| 默认偏好、WebRTC 依赖 | 蓝牙；1.3.9 | 相同 |
| 路由变化后恢复 | 依赖既有设备/焦点回调 | 新增路由失效后重新申请 |
| 音频就绪判据 | 路由回调、连接状态 | 增加实际路由、原生 I/O、RTP 证据 |
| 首次失败后外放、初始 SCO 断开处理 | 存在上述缺口 | 核心逻辑仍保留 |

main 的新增恢复连接见 [AudioSessionController.kt:183–192](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/app/src/main/java/com/kuma/motointercom/AudioSessionController.kt#L183-L192)。已有 v1.3 真机记录使用“一端 OPPO Enco X3、一端手机外放”，不足以证明双方耳机自动选路已通过。[已有验证记录:32](https://github.com/g95809080-cmyk/moto-intercom/blob/0f5e7b778ee0b945725c9506055d6137a7faf546/docs/verification/2026-09-20-kum-57-field-intercom.md#L32)。

**最小修复建议，尚未实施**

- 旧 SCO 路径区分初始 sticky 状态、连接中和本次失败，等待连接完成或超时，避免初始 `DISCONNECTED` 立即停掉请求。
- 现代路径补充有上限的蓝牙重试和实际目标设备确认；暂时回退外放后仍保留恢复蓝牙的机会。
- 补覆盖这两条时序的测试。现有测试主要模拟设备新增和注入回调，不能替代真实 SCO 建立过程。

**后续真机验证与缺失证据**

在实际故障设备、v1.2.0 上复现：耳机提前连接，双方建立通话；保留未操作时的外放状态，再分别对照“直接重选蓝牙”和“听筒→蓝牙”，并交换连接发起方。

两端应同步采集从连接前到恢复后的 `logcat`，重点保留 `AudioRouteController`、`ModernAudioRoute`、`RiderAudioEngine`、WebRTC 及系统音频日志；在外放和恢复后各采一份 `dumpsys audio`、`dumpsys media.audio_flinger`。记录 SDK/OEM、耳机型号、首选路由、实际通信设备、选路返回值、SCO 新旧状态、点击时间和实际听音结果。

目前仍缺**当次 v1.2 故障日志、对端手机 SDK、耳机通信 profile 状态，以及直接重选蓝牙的对照结果**。v1.2 发布记录明确未执行实际蓝牙耳机验收，已有自动化通过不能补足这些证据。[验证边界:39](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/docs/verification/2026-09-12-release.md#L39)。

本会话未修改文件、分支或应用数据，未运行构建、安装或重启。

