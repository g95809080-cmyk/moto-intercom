# 群组电台 R5（双模版 `HYBRID`）实施记录

依据：[群组 PRD](../product/2026-11-17-motocom-group-radio-prd.md) 的 5.4、5.5、`FR-24`、9.2、9.3、10.2 与 11 节。

本轮在 `groupPreview` 变体与 `tools/group-cloud` 上实现 R5 双模（`HYBRID`）：4 人硬上限、云端业务权威、本地 Wi‑Fi Direct 优先、本地失联整组切云端（veRTC）、本地恢复整组切回、支持公共/领队/私聊三频道，并通过媒体静默屏障确保绝对零双路音频。主线 1:1 对讲、R1/R2 本地 4 人模式、R4 网络 50 人大群模式均保持独立与向后兼容。

## 基线与版本

- 主工作区：`MotoIntercomApp`，`main` 基线 `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`。
- 预览变体：`groupPreview`（`com.kuma.motointercom.group.preview`），升级为 `2.3.0-r5.preview.3`（`versionCode = 8`；落地 R5 本地优先自救、Route A 2.4GHz 预设静默直连、50 米探测与零断音预连切回闭环）。
- 主线 `version`、`versionCode`、`betaVersion` 与 `app/build.gradle.kts` 主线依赖保持不变。相对 `7041d3c46`，`app/src/main` 仅包含 R1 引入的共享音频平台改动（`DecodedAudioPlayout.kt` 成员静音、`RiderAudioEngine.kt` `RiderMediaMode.GROUP` 与 `onDisposed`）以及 R5 新增的 `onDisposeFailed` fail-closed 清理回调；同时恢复了 `RiderMediaMode.SINGLE` 下播放错误继续走引擎级 `audioDeviceFailure("playout", ...)` 的主线 1:1 行为。最终正式发布前仍需完成主线 1:1 真机回归。

## 架构边界

```text
Android groupPreview (HYBRID 4 人)
  ├─ GroupHybridSessionOrchestrator（单写者 + 媒体静默屏障）
  │    ├─ tools/group-cloud（唯一业务与传输权威：成员/角色/频道/私聊/审批/群主/CAS/整组切换）
  │    ├─ LOCAL 传输期：GroupNetworkRuntime + GroupAudio（stream-webrtc-android）
  │    │    └─ 本地 GO 只转发云端已准入成员链路，拒绝任何非云端准入设备
  │    └─ CLOUD 传输期：VolcGroupRtcMedia（按频道隔离 rtcRoomId + 懒进房）+ 50 米探测（高功率 BLE + 31s 低频 Wi-Fi RSSI）+ 仅控制面后台预连（零 GroupAudio）
  ├─ LOCAL 模式：原 GroupRuntime + GroupAudio（完全不变）
  └─ NETWORK 模式：原 GroupCloudRuntime + VolcGroupRtcMedia（完全不变）
```

核心不变量：

1. **云端唯一业务权威（PRD 5.5）**：成员准入、4 人硬上限、角色（`OWNER`/`RIDING_OFFICER`/`HEAD`/`TAIL`/`MEMBER`）、`riderIncludesFormation`、频道（`PUBLIC`/`RIDER`/`PRIVATE`）、私聊邀请、群主转让/移除/解散与当前传输模式（`LOCAL` / `CLOUD` + 单调 `transportEpoch`）均以云端为准；本地房主仅承载 GO 与本地链路，严禁接纳云端未准入设备。
2. **本地优先与断连宽限 + 整组切换（PRD 5.5 / FR-24）**：
   - 仅本地语音抖动（TCP 控制心跳正常）时，保持本地 WebRTC 原地 `RestartLink`（10 秒滑动窗口内最多 2 次），不切云端。
   - 本地 TCP 断开或 Wi-Fi 瞬断时，成员保留本地会话与缓存 Route A 凭据，优先在本地静默自救最多 3 次、总窗口 10 秒（T+0s / T+3s / T+6s；若 `p2p0` 未断先重试 TCP，否则经 Route A 重连 `p2p0`），期间不上报 `LOCAL_LINK_LOST`；房主侧单人本地掉线宽限期同步放宽至 13 秒（`LOCAL_PEER_DROP_GRACE_MS = 13_000L`）。
   - 无网/隧道护栏：当 `cloudOffline == true` 时，超过 10 秒也不放弃本地重连，持续在本地尝试接回直到回到距离内或恢复公网（受 60 秒云端席位保留期约束）。
   - 连续 3 次 / 10 秒本地自救失败且云端在线，或房主 GO 本身离线（`LOCAL_HOST_LOST`），才上报服务端整组切 `CLOUD`（veRTC），沿用 15s→30s→60s 指数退避，且切回 `LOCAL` 后需全体成员保持本地就绪满 60 秒（`localStableResetMs`）才清零退避计数。
   - 客户端严格区分单调时钟 `nowMs`（`groupNowMs`）与服务端墙钟 `wallNowMs`（`System.currentTimeMillis`），分别用于本地持续时长与服务端 `cooldownUntilMs`/`expiresAtMs` 比较。
3. **Android 10+ 免弹窗静默直连（路线 A）与 50 米两阶段预连切回（`CLOUD -> LOCAL`）**：
   - 服务端向 `HYBRID` 房间的 `ADMITTED` 成员按阶段最小暴露路线 A 预设凭据（`localP2pSsid` 格式 `DIRECT-mc-xxxxxxxx`、`localP2pPassphrase` 16 位随机口令、`localControlPort = 8899`、房主上报的 `localHostAddress` 与 `localFrequencyMhz`）：`LOCAL` 阶段全员可见；`CLOUD` 探测阶段（`IDLE`/`PROBING`）仅向本地房主下发 `localP2pPassphrase` 启动 2.4GHz 仅控制面 GO 信标，普通成员仅见 `localP2pSsid` 用于扫描真实 Wi-Fi RSSI（`localCode` 为 `null`）；仅当进入 `PRECONNECTING` 时才向全体已准入成员下发完整预设凭据与轮换后的 `localCode`；成员被移除（含 `LOCAL` 阶段移除非房主成员时立即推进 `transportEpoch` 并保持 `active = LOCAL` 零 RTC 签发）或预连中止时立即轮换所有路线 A 凭据与 `localCode`。
   - 房主使用 Android 10+ `WifiP2pConfig.Builder` 指定 `networkName`、`passphrase`、`enablePersistentMode(false)`，在携带已知频点时锁定 `setGroupOperatingFrequency`、否则使用 `GROUP_OWNER_BAND_2GHZ`（二者严格互斥）创建 2.4GHz GO 并上报真实频点与地址；成员使用 `WifiP2pManager.connect`（首轮直连优先精确频点、第 2 次起自救退回 `GROUP_OWNER_BAND_2GHZ` 全信道扫描防旧频点锁死）通过独立 `p2p0` 网卡免弹窗静默接入，TCP Socket 显式绑定 `p2p0` 本地 IPv4 而不干扰蜂窝或主 Wi-Fi；客户端 `close()` 取消挂起 `connect()` 时通过回调 fencing 与 `confirmAbsent()` 确认无组或自动 `removeGroup()`，杜绝 ROM 不回调原 `connect()` 导致的 P2P 租约泄漏；API < 29 保留既有 BLE + `GroupWifiClient` 兼容路径。
   - 50 米探测采用高功率 BLE（`ADVERTISE_TX_POWER_HIGH` / `SCAN_MODE_BALANCED`）+ 31 秒低频 Wi-Fi 扫描（规避 Android 2 分钟 4 次限频，仅在 `groupPreview` 清单放开 API 33+ 精细定位），要求成员 Wi-Fi RSSI `>= -75 dBm` 且全员信号稳定保持 12 秒（`localSignalStableMs`）才进入 `PRECONNECTING`；无精细定位权限时显式降级为高功率 BLE（端侧校验 BLE RSSI `>= -75 dBm` 后上报 `localReachable = true` 且 `localRssiDbm = null`，服务端同样强制校验退避冷却、12 秒稳定期与全员 `localControlReady = true`）。
   - 后台静默预连（`PRECONNECTING`）期间 `VolcGroupRtcMedia` 通话全程不中断，客户端仅建立控制面 `p2p0` + PAKE TCP 心跳（严禁创建 `GroupAudio`）。仅当全体在线已准入成员均上报 `localControlReady = true` 时，服务端在 per-room 锁内同步 `await BanRoomUser` 吊销云端 RTC 并切回 `LOCAL`（附带 `handoverFromEpoch`）；客户端先执行 fail-closed `DRAINING_CLOUD` 排空火山 RTC，确认 `rtc == null` 后再将控制面 `preconnectNetwork` 原子移交为 `localNetwork` 并立即在已就绪 TCP 上拉起本地 WebRTC（缓存 `DRAINING_CLOUD` 期间提前到达的 `Roster` 防时序竞态）。任一成员预连失败（`preconnectFailed = true`）或 20 秒超时则静默中止并推进退避冷却，云端通话不中断。
4. **绝对零双路音频（PRD 9.2 / 10.2）**：
   - `LOCAL -> CLOUD`：先停止本地网络并关闭 `GroupAudio`，仅当 `GroupAudio.close()` 无异常且 `RiderAudioEngine.onDisposed`（`localAudioLeases` 清空）及本地网络释放均确认完成后，才允许创建 `VolcGroupRtcMedia` 并加入 RTC；若任意清理步骤失败或触发 `onDisposeFailed`，立即进入 `BARRIER_FAILED` 终止会话并保持 `GroupRuntimeOwnership` 锁定，绝不启动云端音频。
   - `CLOUD -> LOCAL`：同步执行 `leaveRoom -> destroy RTCRoom -> stopAudioCapture -> destroyRTCEngine -> abandon audioFocus`（通过 `releaseVolcRtcResources` / `runAllCleanupSteps` 全步骤执行并向上传播聚合异常）并递增 `rtcGeneration`；若 `VolcGroupRtcMedia.close()` 抛出异常，立即进入 `BARRIER_FAILED` 终止会话、立即清理任何控制面 `preconnectNetwork` 并锁定所有权，绝不启动 `GroupAudio`。
   - 运行时断言强制校验：创建 `GroupAudio` 或 `PromotePreconnectToLocal` 时 `rtc == null` 且未发生云端清理失败；创建 `VolcGroupRtcMedia` 时 `localAudio == null && localAudioLeases.isEmpty() && localNetwork == null` 且未发生本地清理失败；控制面 `preconnectNetwork` 永不创建 `GroupAudio` 且受独立 `drainingPreconnectDone` 释放栅栏保护。
5. **频道隔离与额度护栏**：
   - `LOCAL` 传输期沿用 R2 同频道点对点 WebRTC 隔离，且服务端严禁签发任何云端 RTC Token（响应 `rtc = null`、`rtcRoomId = null`，`POST /rtc-token` 返回 `409 TRANSPORT_NOT_CLOUD`）。
   - `CLOUD` 传输期每个频道按传输代际映射独立 `rtcRoomId`（`_e<epoch>_pub` / `_e<epoch>_rider` / `_e<epoch>_p_<id>`），服务端签发频道级 AccessToken v001，仅当当前频道在线成员 `>= 2` 时懒进房；客户端仅在快照携带非空 `rtcRoomId` 且缓存 Token 的 `rtcRoomId` 与其严格相等时才允许加入或续签，若 `CLOUD` 快照缺失 `rtcRoomId` 则立即清空缓存、拒绝入房并在已连 RTC 时触发 `LeaveRtc`。
   - **强制 OpenAPI 权威吊销（fail-closed）**：`tools/group-cloud` 启动时强制要求配置火山 OpenAPI AK/SK（缺失直接拒绝启动，移除静默 `skipped` 旁路与任何后台异步吊销队列）。所有显式变更（成员移除、离开、房间解散、频道切换、进入/关闭私聊、角色降级移出领队频道、`CLOUD -> LOCAL` 切回）及异步 `sweep()` 路径（成员断线 `ADMITTED -> RESERVED` 含 `PUBLIC`/`RIDER`/`PRIVATE` 活跃凭证、非群主 `RESERVED` 60 秒恢复超时移除、群主 `RESERVED` 60 秒恢复超时解散整房）均在 per-room 串行锁内同步 `await` `BanRoomUser`（封禁时长覆盖旧 Token 剩余有效期）；OpenAPI 延迟或失败期间绝不提交状态、不推进 `revision`、不广播，且拒绝已超时席位 `/resume` 或签发新凭证（`502 RTC_REVOCATION_FAILED`）。成员在 60 秒保留期内 `/resume`、经授权返回原频道或解除限制重新准入时通过 `UpdateBanRoomUserRule` 解封。
6. **短时蜂窝抖动保护**：处于 `LOCAL` 传输期且本地语音已建立时，若云端长轮询发生短暂网络中断，保持本地直连语音不中断并在后台自动 `/resume` 恢复云端同步；恢复后立即以云端权威快照对齐。
7. **安全与隐私（PRD 9.3）**：6 位数字云端邀请码、6 位本地 PAKE 码、Route A P2P SSID/口令、会话令牌与 RTC Token 均不写入日志或通知；`localCode` 仅在 `active == LOCAL` 或 `PRECONNECTING` 时向云端 `ADMITTED` 成员下发，并在每次切回本地或预连中止时轮换。

## 服务端扩展（`tools/group-cloud`）

- `POST /v1/rooms` 支持 `mode: "NETWORK" | "HYBRID"`（默认 `"NETWORK"`，向后兼容 R4）。`HYBRID` 硬限制 `maxMembers = 4`。
- 仅对 `HYBRID` 开放的端点（`NETWORK` 调用返回 `400 UNSUPPORTED_IN_MODE`）：
  - `POST /v1/rooms/:roomId/roles`
  - `POST /v1/rooms/:roomId/rider-policy`
  - `POST /v1/rooms/:roomId/channel`
  - `POST /v1/rooms/:roomId/private-invites`
  - `POST /v1/rooms/:roomId/private-invites/:inviteId/(accept|decline|cancel)`
  - `POST /v1/rooms/:roomId/private/close`
  - `POST /v1/rooms/:roomId/transport/switch`
  - `POST /v1/rooms/:roomId/transport/local-status`
- 服务端全部房间路由与异步 `sweep()` / `sweepRoom()` 统一经 per-room 串行锁与 `_authenticateUnlocked` 执行（长轮询在释放锁后等待），同步完成 OpenAPI 吊销后再提交私聊清理、席位保留/超时移除、整房解散及 `localHostDeviceId` 失联自动切云。

## 验收计划

- Node 控制面单测：`node --test server.test.mjs`（覆盖全部既有 R4 用例 + 新增 R5 双模用例）。
- Android 自动化测试：主线 `:app:testDebugUnitTest`、群组预览 `:app:testGroupPreviewUnitTest`（新增双模状态机、Robolectric 主线程互斥屏障、真实 HTTP 端到端与 UI/Service 测试）、两套 Lint 与主线/预览 APK 包体隔离审计。
- 只读 `motointercom-product-architect` 架构审查门禁，P0/P1 清零后产出 R5 预览包与验证记录。
