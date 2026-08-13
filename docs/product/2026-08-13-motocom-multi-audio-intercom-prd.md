# MotoCom 多音频共存与来电抢占 PRD

## 0. 文档信息

| 项目 | 内容 |
| --- | --- |
| 产品 | 摩声 MotoCom |
| 文档类型 | Android 多音频共存与来电抢占产品需求文档 |
| PRD 版本 | v1.0 |
| 日期 | 2026-08-13 |
| 需求状态 | 方案已确认，进入实现 |
| 实现状态 | P0/P1 代码已接入，待模拟器与真机验收 |
| 当前工程 | `C:\Users\kuma\Documents\摩托对讲机\MotoIntercomApp-release-v1` |

> 本 PRD 定义产品行为和验收边界。Android Audio Focus、电话状态和 Bluetooth 路由的具体接口以实现时的技术设计为准；任何“所有手机、所有第三方 App 都能强制调低”的表述均不属于本产品承诺。

## 1. 背景与问题

MotoCom 是骑行场景下的一对一实时对讲应用。用户在使用对讲时，通常还会同时使用导航和音乐；当手机有蜂窝来电时，电话音频应优先于对讲，且电话结束后应自动恢复对讲。

当前版本存在以下问题：

1. 没有统一的 Android Audio Focus 管理。
2. 没有蜂窝电话状态监听和来电中断状态。
3. 音频路由可能在真正媒体会话建立前就被激活。
4. WebRTC 播放、录音没有可供电话中断使用的统一暂停/恢复接口。
5. 关闭媒体上下文时，焦点和通信路由未必同步释放。

## 2. 产品目标

### 2.1 用户目标

- 对讲开始后，音乐尽量继续播放但自动降低；对讲语音保持清晰。
- 导航与对讲同时使用时，尽量保持两者可用，并明确平台限制。
- 导航、音乐和对讲同时使用时，优先保证对讲可懂度。
- 有蜂窝来电时，不把电话音频和对讲音频混在一起。
- 电话结束后，在用户仍保持对讲意图的前提下自动恢复对讲。
- 用户手动停止对讲后，任何迟到的焦点或电话回调都不能重新启动对讲。

### 2.2 工程目标

- 用一个协调器统一管理焦点、电话、路由和 WebRTC I/O。
- 将“临时中断”和“永久关闭”分开，避免来电中断破坏原有连接。
- 保留现有 signaling 和产品状态边界，不让音频中断改变连接业务状态。
- 建立可自动化的状态机测试和可重复的真机验收矩阵。

## 3. 非目标与平台边界

以下内容不属于本版本承诺：

- 通过 `setStreamVolume()`、`setStreamMute()` 或其他全局接口精确控制第三方 App 的音量。
- 保证所有导航 App 都会降低播报音量。
- 保证所有 Android 厂商、蓝牙芯片和头盔硬件都支持同时保持高质量音乐与通信麦克风。
- 录制、解析或控制蜂窝电话的通话内容。
- 绕过系统电话应用的音频权限或路由策略。
- 新增账号、社交、群组或多方会议能力。

前三类场景属于 **Audio Focus 协作能力**；第四类电话隔离属于 **MotoCom 自身音频 I/O 的硬隔离能力**。

## 4. 用户与核心场景

### 4.1 主要用户

- 骑行中使用 Android 手机和蓝牙头盔的用户。
- 需要一对一实时语音沟通，同时依赖导航或音乐的用户。
- 可能在前台、后台、锁屏和蓝牙设备反复连接/断开的用户。

### 4.2 场景清单

| 编号 | 场景 | 用户预期 |
| --- | --- | --- |
| S1 | 对讲 + 音乐 | 音乐继续播放并尽量降低，对讲清晰；对讲结束后音乐恢复 |
| S2 | 对讲 + 导航 | 对讲优先；导航播报按其自身 Audio Focus 策略处理 |
| S3 | 对讲 + 导航 + 音乐 | 对讲优先，音乐尽量降低；导航不保证被降低 |
| S4 | 来电响铃 | 立即停止 MotoCom 的录音和播放，电话音频独占 |
| S5 | 已接通电话 | 对讲保持挂起，不发送或播放对讲音频 |
| S6 | 拒接/通话结束 | 等待电话状态为空闲、焦点和路由可用后恢复对讲 |
| S7 | 其他 VoIP App 抢占 | 仅在通信类或瞬时焦点变化时挂起 MotoCom，不与对方 VoIP 音频混合 |
| S8 | 用户主动停止 | 释放所有 MotoCom 音频资源，不因迟到回调自动恢复 |

## 5. 产品决策

### 5.1 音频策略模式

首版只实现“共存模式”，不提供用户可切换的音频策略设置：

| 模式 | Android Focus 类型 | 产品行为 | 适用场景 |
| --- | --- | --- | --- |
| 共存模式（默认） | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` | 允许兼容的音乐/媒体 App 继续播放并降低 | 日常导航、音乐和对讲 |

产品文案必须使用“尽量降低/兼容应用会降低”，不得写成“所有应用强制降低”。
对讲始终保持产品优先级；导航未降低时可以继续原音量播报，不因此降低对讲音量。
严格优先模式属于后续评估范围，不得作为本版本的用户设置或验收条件。

### 5.2 电话优先级

电话状态优先级高于所有 MotoCom 音频状态：

```text
蜂窝电话 RINGING/OFFHOOK > MotoCom 对讲 > 音乐/导航共存策略
```

电话状态不为空闲时，MotoCom 不得恢复播放或录音，即使收到 Audio Focus 恢复回调也必须继续挂起。

## 6. 功能需求

### FR-01：对讲媒体会话的焦点申请

1. 只有真实媒体会话建立时才申请 Audio Focus；发现设备或等待连接时不得抢占音乐/导航。
2. 使用以下音频属性：

   ```kotlin
   AudioAttributes.Builder()
       .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
       .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
       .build()
   ```

3. 相同的 `AudioFocusRequest` 实例必须用于申请和释放。
4. 支持 delayed focus gain；焦点未获准前不得开启 WebRTC 播放和录音。
5. 只有 `AUDIOFOCUS_LOSS_TRANSIENT` 或通信类焦点变化触发临时挂起；普通永久焦点丢失不得被误判为电话状态。
6. 其他 VoIP App 不要求被识别出具体名称；检测到通信类焦点变化时，按电话优先级挂起 MotoCom。
7. 焦点恢复不能绕过电话状态和路由就绪检查。

### FR-02：音乐共存与恢复

1. 共存模式下，申请 `GAIN_TRANSIENT_MAY_DUCK`。
2. MotoCom 结束媒体会话或释放焦点后，由系统/外部 App 恢复其原有播放状态。
3. MotoCom 不保存、更改或写回第三方 App 的全局音量。
4. 对不遵守 Audio Focus 的应用，只记录“系统不支持自动降低”的诊断信息，不弹窗、不阻断对讲，也不得伪造成功状态。
5. 用户在对讲期间手动调整或暂停音乐时，MotoCom 不得在释放焦点后强行恢复旧状态。

### FR-03：导航共存

1. 导航 App 继续运行，不主动停止其导航进程。
2. 对讲语音必须使用通信/语音属性，尽量让系统音频策略区分对讲与导航。
3. 导航播报是否降低由导航 App 和系统 Audio Policy 决定；产品不承诺固定 dB 或固定百分比。
4. 导航未降低时，仍保持对讲优先；本版本不提供强制暂停导航的用户策略。

### FR-04：来电检测

1. Android 31 及以上使用 `TelephonyCallback.CallStateListener`。
2. Android 23–30 使用 `PhoneStateListener` 的 `LISTEN_CALL_STATE`。
3. 用户点击“启动对讲”时申请 `READ_PHONE_STATE`；权限被拒绝后禁止启动对讲，不进入降级对讲模式。
4. 任意一路电话进入 `RINGING` 或 `OFFHOOK` 即暂停对讲；实现需要覆盖设备可见的多 SIM、来电等待和订阅状态。
5. 服务销毁或音频运行时结束时注销回调。

### FR-05：来电时的对讲硬隔离

当电话状态为 `RINGING` 或 `OFFHOOK` 时，必须按以下顺序执行：

1. 停止 WebRTC 音频播放。
2. 停止 WebRTC 音频录音。
3. 静音音频模块，保留用户原有静音/VOX 状态。
4. 释放 MotoCom Audio Focus。
5. 清除 MotoCom 通信设备；旧系统停止 Bluetooth SCO。
6. 不设置 `MODE_IN_CALL`，不尝试接管电话路由。
7. 保留 signaling 和连接上下文，避免把临时中断误判为断线。
8. 不向远端发送“来电中断”状态；远端保持完全无感知，只表现为收不到本地对讲声音。

验收时，电话期间不得出现以下现象：

- 对方听到本地电话声音或系统回声。
- 本地同时听到电话和对讲远端声音。
- MotoCom 重新申请 Bluetooth SCO 或通信设备。
- 对讲状态被错误显示为“已结束”。

### FR-06：通话结束后的自动恢复

仅当以下条件全部满足时恢复完整对讲音频：

- 电话状态为 `IDLE`。
- 用户仍保持对讲意图。
- 媒体会话仍然有效。
- Audio Focus 已获准。
- 首选通信设备已重新连接并验证成功。

恢复顺序：

1. 重新申请 Audio Focus。
2. 恢复 `MODE_IN_COMMUNICATION` 和首选设备。
3. 等待路由确认。
4. 恢复音频模块的用户静音/VOX状态。
5. 开启 WebRTC 录音和播放。
6. 发布“对讲已恢复”状态。

3 秒是恢复目标时间，不是失败超时。焦点被其他应用占用、蓝牙设备尚未就绪时，保持 `RESUMING` 并持续等待，不循环强制抢占；只要最终恢复成功，就算本次恢复成功。
如果用户已经主动停止对讲，则恢复到待机状态，不重新开启录音和播放。
除最终异常外，不弹出恢复失败提示；最终异常只写诊断日志。

### FR-07：音频路由生命周期

音频路由接口需要区分以下操作：

- `setPreferred(selection)`：仅保存偏好，不立即抢占系统路由。
- `activate()`：在焦点获准且媒体存在时激活。
- `suspendForInterruption()`：临时释放通信设备，保留偏好。
- `resumePreferredRoute()`：电话/焦点恢复后重新选择并验证。
- `close()`：用户停止或服务销毁时彻底清理并恢复初始状态。

临时电话中断不得直接调用终止性的 `reset()`，也不得在电话仍在进行时恢复初始 `MODE` 或扬声器状态。

### FR-08：状态与用户反馈

新增以下音频中断状态，不改变现有连接业务状态：

| 状态 | 用户可见文案 |
| --- | --- |
| `NORMAL` | 对讲正常 |
| `PHONE_RINGING` | 来电中，对讲已暂停 |
| `PHONE_ACTIVE` | 通话中，对讲已暂停 |
| `FOCUS_LOST` | 音频被其他应用占用 |
| `RESUMING` | 正在恢复对讲音频 |
| `ROUTE_UNAVAILABLE` | 蓝牙/通信设备暂不可用 |

状态必须来自真实回调和路由验证，不能仅根据按钮点击推算。
以上状态只在本地 App 和前台通知中展示，不向远端发布电话中断状态。电话暂停期间，后台通知显示“对讲已暂停”；异常恢复失败只写日志。

## 7. 状态机与并发要求

协调器维护以下事实：

```text
desiredIntercomActive
phoneState: IDLE / RINGING / OFFHOOK
focusState: NONE / GRANTED / DELAYED / LOST
routeState: INACTIVE / REQUESTED / READY / LOST
effectiveState: IDLE / ACTIVE / PHONE_SUSPENDED / FOCUS_SUSPENDED / RESUMING
```

所有焦点、电话、路由和 WebRTC 状态转换必须串行化。每次媒体会话或恢复尝试生成递增 token；异步回调携带旧 token 时直接丢弃，防止以下竞态：

- 用户停止后，迟到的 `AUDIOFOCUS_GAIN` 又启动音频。
- 电话结束后，旧的路由失败回调覆盖新的成功状态。
- 蓝牙断开重连期间，旧的 SCO 回调误恢复对讲。

## 8. 权限与兼容性

### 8.1 权限

- 现有 `RECORD_AUDIO`、Bluetooth 相关权限继续保留。
- 增加 `READ_PHONE_STATE`，并在支持蜂窝电话的设备上动态申请。
- 仅在用户点击启动对讲时申请电话权限。
- 权限被拒绝时禁止启动对讲，不提供“来电隔离能力降级后继续对讲”的路径。
- 权限说明不增加独立的首次启动弹窗；在用户点击启动时进行必要的系统申请和简短上下文说明。

### 8.2 系统版本

- API 23–30：使用旧版电话监听和 Bluetooth SCO/扬声器路由。
- API 31+：使用 `TelephonyCallback`、`setCommunicationDevice()` 和 `AudioDeviceCallback`。
- Android 12+ 的系统 duck/fade 行为不能替代应用自己的 Focus 回调处理。

### 8.3 Bluetooth

代码兼容目标覆盖经典 HFP/SCO 和 BLE Audio；本版本不做具体头盔型号认证，只有手头设备可用时才执行对应实测。若头盔设备本身支持导航混音或电话优先，应优先接入厂商协议；Android Audio Focus 无法控制头盔内部混音策略。

## 9. 验收标准

### 9.1 自动化验收

- 状态 reducer 覆盖响铃、接听、拒接、通话结束、焦点延迟、焦点丢失、路由丢失和用户停止。
- 验证电话非 `IDLE` 时不会调用 WebRTC `setAudioPlayout(true)` 或 `setAudioRecording(true)`。
- 验证同一焦点请求对象被用于 request/abandon。
- 验证旧 token 回调不会恢复已停止的媒体会话。
- 验证不调用全局音量修改接口。

### 9.2 真机验收

自动化阶段优先覆盖 API 29、30、31、34、36 的模拟器；实体设备阶段至少覆盖手头的小米 6 和小米 13。其他机型不属于本版本认证范围。每台进入实体测试的设备测试：

1. 对讲 + 音乐。
2. 对讲 + 导航。
3. 对讲 + 导航 + 音乐。
4. 来电响铃、接听、拒接、通话结束。
5. Bluetooth 经典 HFP、BLE Audio（若硬件支持）。
6. 锁屏、后台、来电等待、蓝牙断开/重连。

自动化阶段优先检查 WebRTC 播放/录音状态、焦点回调和路由状态；如果 ADB 流程耗时过长，实体设备阶段改为人工逐步操作。最终实体验收仍需人工听音；模拟器或 JVM 测试不能证明物理串音率。

### 9.3 必须满足的硬条件

- 电话响铃或通话期间，MotoCom 无录音、无播放、无通信路由请求。
- 电话结束后，满足恢复条件时自动恢复对讲。
- 用户主动停止后不自动恢复。
- 外部音乐/导航恢复由系统和原应用完成，MotoCom 不篡改全局音量。
- 设备不支持某项共存能力时，允许继续对讲，只写诊断日志，不弹出额外提示。

## 10. 监控与诊断

记录但不上传音频内容的诊断事件：

- `focus_request(granted|delayed|failed)`
- `focus_change(loss|transient_loss|gain)`
- `phone_state(ringing|offhook|idle)`
- `route(requested|ready|lost|cleared)`
- `intercom_audio(suspended|resumed)`
- `resume_blocked(reason)`

日志不得包含电话内容、录音数据、联系人信息或第三方 App 的隐私数据。

## 11. 风险与降级策略

| 风险 | 影响 | 降级策略 |
| --- | --- | --- |
| 第三方 App 不遵守 Audio Focus | 音乐/导航不降低 | 继续对讲，仅记录“当前音乐/导航应用不支持自动降低” |
| 导航使用语音内容策略 | 导航不会自动 duck | 不承诺固定音量；继续保持对讲优先 |
| 用户拒绝电话权限 | 无法可靠识别所有蜂窝来电 | 禁止启动对讲，要求用户授予权限 |
| Bluetooth profile 切换 | 音质或路由变化 | 保留首选路由，等待设备验证后恢复 |
| OEM 修改 Audio Policy | 不同设备行为不一致 | 设备矩阵验收和运行时诊断 |
| 异步回调竞态 | 错误恢复或串音 | 单线程协调器 + generation/token |

## 12. 实施拆分建议

### P0：电话硬隔离与恢复

- 协调器、电话监听、WebRTC I/O suspend/resume、路由释放/恢复。
- 完成来电/通话/结束电话状态机和自动化测试。

### P1：音乐/导航 Audio Focus 共存

- 共存模式 `MAY_DUCK`。
- 焦点延迟、通信类焦点丢失、焦点恢复和兼容性提示。

### P2：设备适配与体验优化

- 经典 Bluetooth 与 BLE Audio 适配。
- 厂商设备兼容性矩阵。
- 运行时诊断；本版本不增加音频策略设置页。

## 13. 成功指标

- 真实设备电话测试中，人工听音确认电话期间无对讲音频串入；自动化只检查 WebRTC 播放/录音状态，不以此证明物理串音率。
- 电话结束后，在通信设备可用且焦点获准的情况下，对讲最终恢复完整音频；3 秒为目标时间，不作为失败超时。
- 用户停止后误恢复率为 0。
- 支持 Audio Focus 的音乐 App 在共存模式下收到 `MAY_DUCK` 请求；不遵守的 App 允许继续对讲，仅记录当前应用不支持自动降低。
- 不支持的设备行为只写诊断日志，不出现“状态显示成功但实际无声”的伪成功。

## 14. 发布确认

最终产品验收和发布确认人：需求方本人。

## 15. 参考资料

- [Android Audio Focus 指南](https://developer.android.com/media/optimize/audio-focus)
- [AudioFocusRequest API](https://developer.android.com/reference/android/media/AudioFocusRequest)
- [AudioAttributes API](https://developer.android.com/reference/android/media/AudioAttributes)
- [AudioManager API](https://developer.android.com/reference/android/media/AudioManager)
- [TelephonyCallback.CallStateListener](https://developer.android.com/reference/android/telephony/TelephonyCallback.CallStateListener)
- [PhoneStateListener](https://developer.android.com/reference/android/telephony/PhoneStateListener)
- [WebRTC AppRTC Audio Manager](https://chromium.googlesource.com/external/webrtc/+/refs/heads/master/examples/androidapp/src/org/appspot/apprtc/AppRTCAudioManager.java)
- [Linphone Android 文档](https://wiki.linphone.org/xwiki/wiki/public/view/Lib/Getting%20started/Android/)
- [Android BLE Audio 概览](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview)
