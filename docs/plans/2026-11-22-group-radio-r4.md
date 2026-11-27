# 群组电台 R4 实施记录

依据：[群组 PRD](../product/2026-11-17-motocom-group-radio-prd.md) 的 `FR-17`、`FR-19` 至 `FR-23` 与 9.3 安全约束。

本轮把 R3 的“Android 全互联 WebRTC + coturn + `/signals` 中继”替换为“火山引擎 RTC（veRTC）+ `tools/group-cloud` 权威控制面”，网络单公共房间容量从 4 人提升到 50 人。本地 Wi-Fi Direct 4 人群组、R1/R2 能力、主线 1:1 对讲和现有 `stream-webrtc-android` 媒体路线保持不变。

## 基线

- 主工作区：`MotoIntercomApp`，`main` 基线 `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`。
- 预览变体：`groupPreview`，包名 `com.kuma.motointercom.group.preview`，版本 `2.2.0-r4.preview.2`（`versionCode = 5`）。
- 主线 `version`、`versionCode`、`betaVersion` 与主线依赖不变。
- 真实火山引擎 RTC `AppID` / `AppKey` 仅写入本地未追踪的 `tools/group-cloud/.local/config.json`（严格 gitignore），用于单机真机联调，不进入源码、文档或客户端产物。

## 架构边界

```text
Android groupPreview
  ├─ GroupCloudSessionOrchestrator（单写者、产品状态）
  │    ├─ tools/group-cloud：房间/成员/审批/群主/CAS/恢复
  │    └─ RTC 效果边界：JoinRtc / LeaveRtc / Token / Mute / Route
  ├─ VolcGroupRtcMedia：RTCEngine + RTCRoom
  └─ 本地模式：原 GroupAudio + stream-webrtc-android（完全独立）
```

- 业务群主 `ownerId` 与本地宿主 `hostDeviceId` 保持分离；网络房间固定 `hostDeviceId = null`。
- 网络模式只暴露单公共频道，不创建领队或私聊频道。
- 控制面不再接收或转发 SDP/ICE，不签发 TURN 凭证。
- 媒体创建采用懒加载：只有本人 `ADMITTED` 且在线成员 `>= 2` 才创建 `RTCEngine` / `RTCRoom`。

## 服务端

| 模块 | 实现 |
|---|---|
| `tools/group-cloud/volc-rtc.mjs` | 零依赖 AccessToken v001 二进制编码、HMAC-SHA256 签名、发布/订阅权限和 Token 生命周期 |
| `tools/group-cloud/volc-openapi.mjs` | 火山 OpenAPI v4 签名与 `BanRoomUser` 请求；可选 AK/SK 未配置时跳过 |
| `tools/group-cloud/room-store.mjs` | 50 席上限；邀请/审批/CAS/群主转让/解散/60 秒 `RESERVED`；移除、转让离房、解散触发可选 RTC Ban 钩子 |
| `tools/group-cloud/server.mjs` | `POST /v1/rooms/:roomId/rtc-token` 签发/续签；`healthz` 暴露 `maxMembers=50` 与 `audioRoutingRequired=true`；移除 `/signals`、`/confirm-link`、`/restart-link`、`/turn-credentials` |
| `tools/group-cloud/dev-config.mjs` | 生成空 `volcAppId` / `volcAppKey` 与可选 OpenAPI AK/SK 本地配置 |

安全边界：

- `AppKey`、OpenAPI AK/SK 只从 `.local/config.json` 或环境变量读取，`.local/` 严格 gitignore。
- 客户端只收到 `{ appId, rtcRoomId, rtcUserId, rtcToken, expiresAtMs }`，不接触 AppKey。
- 续签必须持有效 `sessionToken`；吊销房间访问后不能继续签发。
- `BanRoomUser` 配置后，移除成员使用 `ForbiddenInterval` 阻止短时重返，解散房间对整房封禁；未配置时控制面仍立即吊销 bearer 访问。

## Android 媒体与状态

- `GroupRtcMedia` 是 JVM 可 Mock 的端口接口；`VolcGroupRtcMedia` 使用 SDK V3.60 的 `RTCEngine.createRTCEngine` / `RTCRoom`，不使用旧 `RTCVideo`。
- 房间配置为 `ChannelProfile.CHANNEL_PROFILE_COMMUNICATION`、自动发布音频、关闭视频、`isAutoSubscribeAudio=true`。对照火山 Android SDK V3.60 官方文档，旧版 `AUDIO_SCENARIO_COMMUNICATION` 在 V3.60 中映射为 `AudioScenarioType.CHATROOM`（已在小米 13 真机确认进入 `MODE_IN_COMMUNICATION` / `setMode=3`），并配合 `startAudioCapture()` / `stopAudioCapture()` 管理本地采集生命周期。
- 路由使用 `setDefaultAudioRoute` / `setAudioRoute`，回调 `onAudioRouteChanged` 同步 UI；支持蓝牙、听筒/有线耳机、扬声器。
- 入房前申请 `USAGE_VOICE_COMMUNICATION` 音频焦点；焦点丢失时暂停采集、成对调用 `pauseAllSubscribedStreamAudio()` 并将播放音量归零，焦点恢复后按原静音状态恢复、调用 `resumeAllSubscribedStreamAudio()`、重新同步远端静音集合并重新应用当前路由。
- `enableAudioPropertiesReport` 根据 `onRemoteAudioPropertiesReport` 的 VAD 计算活跃发言人；开启音频选路后该回调只上报远端活跃用户。
- `onRoomStateChanged` 按 V3.60 语义处理 `0 = 加入成功、非 0 = 错误码`，同时兼容 `onRoomStateChangedWithReason`，并通过 `VolcRoomEventGate` 对 V3.60 同时触发的新旧终态回调去重；`onLeaveRoom`、`-1001`（如误用 WTN 非 RTC AppID）、音频发布上限 `-1080` 和订阅上限 `-1070` 都会转成可见状态，不静默降级。
- 成员级本地静音按 V3.60 流模型调用 `subscribeStreamAudio(streamId, false)` 与 `setRemoteAudioPlaybackVolume(streamId, 0)`；`VolcRemoteAudioMuteTracker` 从 `onUserPublishStreamAudio`、`onAudioSubscribeStateChanged` 和音频选路下的 `onRemoteAudioPropertiesReport` 收集 `userId -> streamId` 映射，引擎重建或新流到达时立即补齐屏蔽。
- 生命周期严格按 `leaveRoom -> destroy RTCRoom -> stopAudioCapture -> destroyRTCEngine`。正常离房、被移除、群主解散、Token 不可用、成员回落到 1 人时都退出 RTC。
- `onTokenWillExpire` 或到期前 30 秒触发 `RefreshRtcToken`，成功后 `updateToken`；刷新失败保留房间状态并自动重试。
- 本地模式、1:1 主线和 R1/R2 永不创建 `RTCEngine`。

## 50 人、音频选路与额度护栏

- 服务端默认 `MAX_MEMBERS=50`，允许实例配置 2–50；提交与审批两处都做原子容量校验。
- 大群 AppID 必须创建在 **火山引擎「实时音视频 RTC」控制台**（`https://console.volcengine.com/rtc/listRTC`，切勿误用「WTN传输网络」控制台，否则进房报 `404 AppID Illegal` / `-1001`），并在「实时音视频 RTC → 功能配置 → 房间模式」开启「音频选路」。默认房间虽可容纳 50 个可见用户，但发布与单用户订阅各只有 30 路上限；音频选路扩展到 1,000 路，并只向客户端下发活跃发言者。
- 不开启音频选路时，SDK 的 `-1080` / `-1070` 会显示明确配置提示，不静默降级。
- 计费按每个在房用户的停留时长累计。客户端只在已入群且在线人数 `>= 2` 时进房；单人等待、待审批、房间回落到 1 人时退出，避免 10,000 分钟免费额度空转。

## UI

- 网络容量统一显示 `X/50 人`，成员达到 12 人后折叠，群主、活跃发言人、自己优先排序。
- 待审批队列默认展示 3 条，可展开全部；群主转让列表在 50 人规模下滚动选择。
- 网络页显示 RTC 待机/连接/失败状态、活跃发言人和“语音层：火山引擎 RTC · 音频选路 · 单公共频道”。
- 48dp+ 触控目标保持；邀请码只对群主展示，通知、日志和诊断不包含邀请码或 Token。

## 构建与包体隔离

- `settings.gradle.kts` 增加 `https://artifact.bytedance.com/repository/Volcengine/`。
- 仅 `groupPreviewImplementation` 引入 `com.volcengine:VolcEngineRTC:3.60.108.100`。
- 排除火山 SDK 传递的旧 `com.android.support` 与 `android.arch.*`，避免和 AndroidX 命名空间冲突。
- 真机验证发现火山 SDK 3.60 音频设备层仍通过字节码调用旧版 `android.support.v4.content.ContextCompat.checkSelfPermission(Context, String)`（经全量字节码扫描确认上游 9 个调用类仅使用该单一方法）。在 `app/src/groupPreview/java/android/support/v4/content/ContextCompat.java` 提供仅限 `groupPreview` 变体的最小兼容桥，避免引入完整 `support-compat` 与 AndroidX 冲突，且不污染主线 APK。
- `packaging.jniLibs.excludes` 移除视频超分、锐化、降噪、特效、H.265/VP8、FDK AAC、FFmpeg 音频等非音频主链路插件。
- `arm64-v8a` / `armeabi-v7a` 保留 `libvolcenginertc.so`、`libbytenn.so`；上游 V3.60 AAR 的 `x86_64` 本身不含 `libbytenn.so`，无法从其他 ABI 安全补齐。三个 ABI 均随预览变体打包完整 `libc++_shared.so`；主线 APK 不包含任何 Volc 媒体库。

## 测试与验收边界

- Node 控制面测试覆盖 Token 二进制布局、OpenAPI 签名、50 席、续签、Ban 钩子、CAS、60 秒恢复、TLS 与非信令契约。
- Android `groupPreview` 单测覆盖 Token 解析/刷新、RTC 回调错误码、>=2 懒进房、1 人退房、待审批不进房、路由、成员级静音、群主规则、50 席快照、UI 和真实 Kotlin ↔ Node HTTP 流程。
- 仪器测试源码覆盖本地 R2 回归与 R4 网络烟测，后者需要在设备侧启动带真实火山凭证的 `tools/group-cloud`。
- 50 人真实并发、不同网络 NAT、真实音频选路下行质量、免费额度消耗、蓝牙 SCO 与来电恢复仍属于真机/公网验收，不以单机自动测试代替。
