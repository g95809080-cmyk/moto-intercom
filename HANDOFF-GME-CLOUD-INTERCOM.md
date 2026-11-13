# MotoCom GME 云对讲落地交接

更新时间：2026-11-10  
交接对象：接手实施的下一个模型/工程师  
工作树：`C:\Users\kuma\Documents\摩托对讲机\MotoIntercomApp-gme`

> 2026-11-11 推进记录：Phase 0–5 的代码、测试 AWS 栈、线上控制面联调和 Phase 5 模拟器仪器测试已完成，详见第 14–18 节及 `docs/gme-android.md`。第 4–9 节保留为原始交接快照，其中旧静态令牌、直进房行为和临时文件清单已不代表当前代码。仍未完成 GME 双真机语音、蜂窝、头盔、长时和 300ms 真机回切验收，不能标记为“云端对讲可用”。

## 0. 先读这一段

这不是“是否要接 GME”的方案讨论，而是已经完成需求拷问后的实施基线。除非用户明确改口，不要重新讨论下面已经锁定的产品决策。

用户已明确取消以下流程要求，实施时不要再创建或等待：

- 不创建 Linear issue。
- 不执行 `motointercom-product-architect` 架构门禁。
- 不为这次工作新建独立分支。
- 不修改正式主工作区 `MotoIntercomApp`。

直接在当前实验工作树继续。除非用户明确要求，不要 commit、merge、force push 或发布。

## 1. 目标

给 MotoCom 增加腾讯 GME 云端对讲，形成两条链路：

1. 本地直连为主链路，继续使用现有 Wi-Fi Direct / LAN / WebRTC。
2. 本地直连成功建立后，如果发生非用户主动结束的传输故障，自动降级到 GME 云房。
3. 跨网络第一次连接走完整的“发起邀请 → 对方接受 → 自动进房”链路。
4. 首发产品仍然是一对一；数据模型预留 `members` 列表，但不实现多人行为和多人 UI。

腾讯 GME 承载语音媒体；AWS 只承载设备身份、配对关系、骑行会话、邀请、fallback 状态和短期票据签发。

## 2. 已锁定产品决策

### 2.1 链路与降级

- 本地直连是主链路。
- 只有“本地直连曾经成功建立、随后链路丢失”才允许自动 fallback。
- 首次见面没有本地直连时，不能自动 fallback；必须手动邀请。
- 用户主动结束、拒绝邀请、邀请超时、骑行会话已结束，都不得触发 fallback。
- 双方都可以提交 fallback intent；服务端幂等迁移，第一份合法请求生效。
- 首次降级允许 1–3 秒断音。
- 进入云端后，本地链路恢复并稳定 10 秒，允许自动回切本地。
- 回切的切换窗口目标 ≤300 毫秒，主观上不能出现明显断句。
- 回切失败时继续留在云端，下一次稳定窗口重试，最多 3 次。

### 2.2 邀请与骑行

- 邀请有效期 30 秒。
- 邀请阶段不取 GME 票、不进房、不产生 GME 费用。
- 对方接受后才创建/进入 GME 房间。
- 每次骑行一个随机 `roomId`；同一次骑行内允许断线、fallback 和回切复用；下一次骑行重新分配。
- 骑行会话在用户点“开始对讲”或接受邀请时显式创建。
- 任一方明确结束，或双方离线超过 10 分钟，骑行会话失效。
- App 被系统杀掉或崩溃不主动唤醒；10 分钟内重新打开可以手动继续本次骑行。
- 邀请方要求对方当前在线；离线直接提示，不排队。
- 不支持 FCM 冷启动推送；只要求 App 在前台或前台服务仍在运行。
- 正在本地或云端对讲时收到其他邀请，直接回复忙线并拒绝。
- 同一车友在同一骑行会话内重复邀请按幂等处理，不重复弹窗。

### 2.3 音频

- 首发只支持标准 Android 蓝牙 HFP/A2DP 耳机或头盔。
- 不实现 ASMAX 私有 BLE Opus 网关，不处理私有头盔协议。
- GME 房间类型固定为 `FLUENCY`。
- 云端使用 GME 标准采集和播放，不复制 `SendCustomAudioData` 自定义链路。
- 云端 VOX 使用 `ITMGAudioCtrl.GetMicLevel()` 采样，复用现有 `VoxGate` 的灵敏度、攻击、hangover 和噪声底逻辑。
- VOX 门控首先使用 `SetMicVolume(0/100)`；如果真机发现仍上行或产生爆音，再改用 `EnableAudioSend(false/true)`，产品行为不变。
- 静音优先于 VOX。
- 云端 RNNoise 不生效；UI 必须隐藏或明确标注，不能显示“已启用 RNNoise”。
- 手机来电、导航播报等音频焦点抢占沿用现有 `AudioRouteController` / 音频中断策略；来电期间暂停 GME 采集和播放但保留房间，恢复后继续。
- 允许蜂窝数据；提供“仅 Wi-Fi 时使用云端”开关和流量提示。

### 2.4 成本与发布

- 单次骑行云端硬上限 4 小时；3 小时 30 分提示，4 小时自动退出并允许手动延长。
- 不做每日硬上限；依赖 GME 用量告警和 AWS 费用告警。
- 单独构建开关控制云端入口，默认关闭。
- Beta 门槛：两台真机、其中一台纯蜂窝；邀请接受/拒绝/超时、本地断链 fallback、双向语音至少 10 分钟、锁屏、标准蓝牙头盔路由。
- 正式版门槛再增加弱网/隧道、反复进出 20 次、App 被杀重建、4 小时长时和 VOX 漏字/回声对比。

### 2.5 身份与隐私

- 每台设备生成不可导出的 Android Keystore EC P-256 密钥。
- AWS 只保存设备公钥，不保存设备私钥。
- 本地配对时双方交换公钥，并对同一份 pairing transcript 签名；AWS 验证双方签名后才建立配对关系。
- 旧配对记录没有公钥和签名，标记为 `legacy`：本地继续可用，首次使用云端必须重新配对。
- 首次启用云端功能时显示完整隐私说明并记录时间戳，再允许 GME 初始化或自动降级。
- AWS 数据保留：
  - 设备公钥、配对关系：保留到解除配对。
  - 骑行元数据：7 天。
  - 邀请事件：24 小时。
  - 不保存音频、语音内容、AppKey、GME `authBuffer` 或 client token。

## 3. 目标架构

### 3.1 Android 分层

- `IntercomService` 继续作为前台服务和运行时所有权中心。
- 现有 `SessionOrchestrator` 继续管理 Wi-Fi Direct / LAN / WebRTC 本地链路。
- 新增 `CloudSessionCoordinator`，管理云端邀请、骑行会话、fallback、回切和 GME 生命周期。
- 不把 `CLOUD` 加入现有 `Transport` 枚举；GME 不是发现型传输。
- `GmeCloudSession` 保留为 GME SDK 适配器，不承担邀请和跨设备协商。
- 新增 `ControlPlaneClient`、`DeviceKeyStore`、`PairingProof`、`RideSessionStore`、`CloudVoxController`。

本地/云端切换时，由 `IntercomService` 统一处理音频所有权，不能让 WebRTC 和 GME 同时抢占麦克风。

### 3.2 AWS 资源

- 区域：`ap-southeast-2`
- 新合并栈名：`motocom-gme`
- 使用 CloudFormation/SAM 模板和部署脚本入库。
- 资源：
  - API Gateway HTTP API
  - API Gateway WebSocket API
  - Lambda
  - DynamoDB
  - Secrets Manager
  - CloudWatch Logs / Alarms

旧的 `motocom-gme-ticket` 栈是上一轮临时实验部署，后续由 `motocom-gme` 替换，不要继续把生产逻辑挂在旧栈上。

### 3.3 HTTP 命令 + WebSocket 事件

所有会改变状态的命令走签名 HTTP；WebSocket 只负责在线状态和事件推送。这样权限判断和幂等处理都集中在 HTTP Lambda，不依赖 WebSocket 连接生命周期。

建议契约：

```text
POST /v1/devices/register
POST /v1/pairs/register
POST /v1/rides/start
POST /v1/rides/{rideId}/invite
POST /v1/rides/{rideId}/invite/respond
POST /v1/rides/{rideId}/fallback
POST /v1/rides/{rideId}/ticket
POST /v1/rides/{rideId}/end
GET  /v1/rides/{rideId}
POST /v1/ws-token
```

WebSocket 事件至少包含：

```text
presence.changed
invite.created
invite.cancelled
invite.accepted
invite.declined
ride.cloud.ready
ride.cloud.failed
ride.switchback.requested
ride.switchback.result
ride.ended
```

事件信封：

```json
{
  "eventId": "uuid",
  "type": "invite.created",
  "rideId": "ride_xxx",
  "revision": 12,
  "occurredAt": 1760000000000,
  "payload": {}
}
```

客户端按 `eventId` 去重，按 `revision` 丢弃旧事件。

### 3.4 设备鉴权

签名串固定为：

```text
METHOD
PATH
SHA256(body)
X-Timestamp
X-Nonce
```

请求头：

```text
X-Device-Id
X-Timestamp
X-Nonce
X-Signature
```

- 时间窗 ±60 秒。
- nonce 在 DynamoDB 或等价的短 TTL 存储中保留 120 秒，防重放。
- Android 用 Keystore 私钥签名；AWS 用已登记公钥验签。
- `POST /v1/ws-token` 用签名 HTTP 换取 60 秒、单次使用的 WebSocket token；WebSocket 连接建立时把 `connectionId` 绑定到 `deviceId`。
- 生产路径禁止使用 APK 内嵌静态 client token。

### 3.5 配对登记

`pairing transcript` 至少包含：

```text
protocolVersion
pairId
deviceIdA
deviceIdB
publicKeyA
publicKeyB
createdAt
nonce
expiresAt
```

双方分别签名，`POST /v1/pairs/register` 同时提交 transcript、两份签名和公钥。AWS 检查双方签名、时间窗、deviceId 和公钥匹配后才创建 `Pairs` 记录。

没有双签的旧记录不能用于云端。

### 3.6 骑行会话与房号

`RideSession` 建议字段：

```text
rideId
pairId
members[]           // 预留多人；当前严格限制一个 peer
state
roomId
revision
createdAt
updatedAt
lastHeartbeatAt
endReason
expiresAt
```

状态建议：

```text
LOCAL_PENDING
LOCAL_ACTIVE
INVITE_PENDING
INVITE_ACCEPTED
CLOUD_PENDING
CLOUD_ACTIVE
SWITCHING_LOCAL
ENDED
```

规则：

- `roomId` 由服务端随机分配，不能按 deviceId 稳定派生。
- 邀请阶段只创建邀请记录和骑行元数据，不签发 GME 票据。
- 接受邀请或 fallback 被服务端确认后才允许取票。
- fallback intent 幂等；重复请求返回同一结果。
- 任一方主动 end，状态直接进入 `ENDED`。
- 双方最后心跳超过 10 分钟，状态进入 `ENDED`。

### 3.7 DynamoDB 建议

```text
Devices
  PK: DEVICE#<deviceId>
  publicKey, keyVersion, createdAt, lastSeenAt, revokedAt

Pairs
  PK: PAIR#<pairId>
  members, publicKeys, transcript, signatures, status, createdAt

Rides
  PK: RIDE#<rideId>
  pairId, members, state, roomId, revision, timestamps, endReason, TTL
  GSI: pairId + updatedAt

Invitations
  PK: INVITE#<inviteId>
  rideId, fromDeviceId, toDeviceId, state, expiresAt, TTL
  GSI: rideId + createdAt

Connections
  PK: CONN#<connectionId>
  deviceId, connectedAt, lastHeartbeatAt, TTL
  GSI: deviceId + lastHeartbeatAt
```

邀请业务过期时间是 30 秒；邀请事件/审计记录可保留 24 小时。骑行元数据保留 7 天。

### 3.8 GME

- GME AppID：`1400802321`
- 旧文档里的 `1400802275`、`1400662375` 不是当前部署实际使用的 AppID。
- AppKey 只存在 AWS Secrets Manager，不进入 APK、不进入仓库、不进入日志。
- Secret 名称：
  - `motocom/gme/app-key`
  - `motocom/gme/client-token`
  - `motocom/gme/room-salt`
- 每次进房或重连由 Lambda 生成短期 `authBuffer`，TTL 300 秒。
- `openId` 使用设备公钥稳定派生，例如 `moto_<sha256-base64url-prefix>`；不要使用可变昵称或聊天昵称。
- 云端流程：

```text
取票
→ ITMGContext.Init
→ EnterRoom(roomId, FLUENCY, authBuffer)
→ SetAudioRole(ANCHOR)
→ EnableMic(true)
→ EnableSpeaker(true)
→ 33ms Poll
```

退出：

```text
EnableMic(false)
→ EnableSpeaker(false)
→ ExitRoom
→ Uninit
```

## 4. 当前工作树状态

- 路径：`C:\Users\kuma\Documents\摩托对讲机\MotoIntercomApp-gme`
- 分支：`feat/gme-cloud-intercom`
- HEAD：`b1636f5`
- 远端基点：`origin/main`
- 正式主工作区 `MotoIntercomApp` 未修改；它当前仍处于 `main` 且落后 `origin/main` 19 个提交。

当前有未提交改动：

```text
M .gitignore
M app/build.gradle.kts
M app/src/main/java/com/kuma/motointercom/HomeScreen.kt
M app/src/main/java/com/kuma/motointercom/IntercomService.kt
M app/src/main/java/com/kuma/motointercom/MainActivity.kt
M app/src/main/java/com/kuma/motointercom/MainScreen.kt
?? app/src/main/java/com/kuma/motointercom/CloudIntercomState.kt
?? app/src/main/java/com/kuma/motointercom/GmeCloudSession.kt
?? app/src/main/java/com/kuma/motointercom/GmeConfig.kt
?? app/src/main/java/com/kuma/motointercom/GmeTicketClient.kt
?? app/src/test/java/com/kuma/motointercom/CloudIntercomStateTest.kt
?? docs/gme-android.md
?? scripts/gme/
```

根目录还存在上一轮验证临时文件，不要提交：

```text
current.db
pairings.db
pairings.db-*
tmp-home.png
tmp-window*.xml
```

上一轮测试生成的 `G:\app\hs_err_pid82508.log` 也应清理，不属于源码。

## 5. 当前代码能用的部分

以下代码已经存在并通过启动验证，可以作为 GME SDK 适配器和 UI 参考，但不要把它当成完整会话架构：

- `GmeCloudSession.kt`
  - GME `Init`
  - `EnterRoom`
  - `SetAudioRole(ANCHOR)`
  - `EnableMic` / `EnableSpeaker`
  - 33ms `Poll`
  - 事件回调、退房、超时
- `GmeTicketClient.kt`
  - 从 HTTP 票据接口取票
  - 解析 `appId`、`roomId`、`openId`、`authBuffer`、`expiresAt`
  - 错误提示
- `CloudIntercomState.kt`
  - 当前独立会话状态
  - UI 展示文本和动作状态
- `IntercomService` 中的云端入口
  - 前台服务类型 `MICROPHONE`
  - 云端和本地互斥
  - 进程级所有权和音频资源释放
- `MainActivity` / `MainScreen` / `HomeScreen`
  - 云端卡片
  - 已配对车友选择
  - 云端状态展示
  - 静音转发

## 6. 当前实现必须改掉的地方

不要在这些旧行为上继续堆功能：

1. 当前点击车友立即取票进房，没有邀请阶段；必须拆成邀请意图和接受后建云房。
2. 当前 `roomId` 接近固定配对派生，不符合“每次骑行随机房号”。
3. 当前票据 API 只传 `localDeviceId + peerDeviceId`，没有 `rideId`、会话状态和签名设备身份。
4. 当前使用 APK 内静态 `gme.client.token`，生产路径必须改成设备私钥签名。
5. 当前 `GmeCloudSession` 独立启动，不感知本地会话、fallback、回切和骑行状态。
6. 当前没有 WebSocket 控制面，没有在线 presence、邀请、接受/拒绝、busy 和 fallback 事件。
7. 当前等待对方进房没有超时，必须加 60 秒无 peer 自动退房。
8. 当前准备阶段静音会丢失：未进房时 `setMuted` 没有保存状态，进房后仍会 `EnableMic(true)`。
9. 当前未配置票据服务时云端卡片不可点击，说明弹窗不可达；应允许点击只显示说明。
10. 当前本地对讲进行中云端卡片仍可能先打开车友选择，之后才被服务拒绝；应在卡片层直接禁用并显示原因。
11. 当前云端 VOX/RNNoise 状态没有真实接入；RNNoise 在云通道不生效。
12. 当前音频源仍显示“手机音频（云端）”，没有复用完整 `AudioRouteController` 路由策略。
13. 当前没有首次云端隐私同意时间戳。
14. 当前没有 App 被杀后 10 分钟内手动继续骑行的恢复入口。
15. 当前没有真正的双真机跨网络验收证据。

## 7. AWS 当前实际状态

账号/profile：

```text
AWS profile: agent-toolkit
Account: 401921623615
Region: ap-southeast-2
```

旧实验栈：

```text
Stack: motocom-gme-ticket
Status: CREATE_COMPLETE
ApiId: z7hruen3ha
ApiName: motocom-gme-ticket-api
Endpoint: https://z7hruen3ha.execute-api.ap-southeast-2.amazonaws.com
Ticket: https://z7hruen3ha.execute-api.ap-southeast-2.amazonaws.com/prod/v1/gme/ticket
Lambda: motocom-gme-ticket-ticket
Runtime: nodejs22.x
Handler: index.handler
Memory: 128 MB
Timeout: 10s
Ticket TTL: 300s
```

已验证行为：

- 正常 POST 票据接口返回 `200`。
- 返回字段包含 `appId`、`roomId`、`openId`、`authBuffer`、`expiresAt`。
- 正常 `authBuffer` 约 88 字节。
- 错误 client token 返回 `401`。
- 模拟器端完成过取票、GME 进房、音频启停和退房。

不要输出或粘贴 AppKey、client token、`authBuffer`。上一次调试输出中出现过 AppKey 的迹象，正式上线前应轮换 AppKey，并同步更新 Secrets Manager 和 GME 控制台。

旧栈源码目前不在仓库里。下一个模型应使用只读方式导出 CloudFormation 模板再整理到新栈，不要把密钥带进模板：

```powershell
$env:AWS_PROFILE='agent-toolkit'
aws cloudformation get-template `
  --stack-name motocom-gme-ticket `
  --region ap-southeast-2
```

## 8. 当前验证结果

完整 Gradle 门禁已通过：

```powershell
$env:JAVA_HOME='F:\Android\jbr'
& 'G:\gradlew.bat' :app:testDebugUnitTest :app:lintDebug :app:assembleDebug `
  --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。

Debug APK：

```text
C:\Users\kuma\Documents\摩托对讲机\MotoIntercomApp-gme\app\build\outputs\apk\debug\app-debug.apk
```

环境注意：

- 项目路径含中文，直接在真实路径跑测试曾出现全量 `ClassNotFoundException`。
- 当前可用映射：

```powershell
subst G: "C:\Users\kuma\Documents\摩托对讲机\MotoIntercomApp-gme"
```

- 从 `G:\` 运行 Gradle，使用 `--no-daemon` 更稳。
- 全量测试需要约 2 GB 测试堆；机器物理内存 16 GB，若同时运行模拟器、IDE 和 Gradle daemon，可能因为提交内存不足导致测试进程启动失败。
- 失败时先 `G:\gradlew.bat --stop`，必要时关闭无窗口测试模拟器（`adb -s emulator-5554 emu kill`），再重跑。
- 之前测试失败过，但原因是机器提交内存耗尽和测试执行器启动问题，不是云通道业务断言失败；释放内存后完整门禁通过。

## 9. 已知未验证项

- 两台真机双向语音。
- 一台纯蜂窝、另一台 Wi-Fi 的跨网络通话。
- 弱网、隧道、地下车库、电梯。
- 锁屏和长时间后台。
- 标准蓝牙头盔 HFP/SCO 路由、回声、延迟和音质。
- App 被系统杀掉后的恢复。
- GME 房间内已有成员时的事件回放。
- GME 计费粒度、免费额度口径和账号额度告警。
- 通知栏“停止/接受”动作。
- VOX 在 GME `GetMicLevel` 尺度上的灵敏度校准。

## 10. 实施顺序

### Phase 0：整理当前实验代码

- 保留现有 GME SDK 适配器。
- 拆出 `CloudSessionCoordinator` 接口。
- 移除生产路径中的静态 client token。
- 修正当前 UI 互斥、未配置提示、准备阶段静音、无 peer 超时。
- 建立本地/云端统一的 `RideSession` 领域模型。

### Phase 1：AWS 控制面 MVP

- 建立 `motocom-gme` CloudFormation/SAM 栈。
- 实现设备公钥、配对双签、骑行、邀请、fallback、票据和 WebSocket 事件。
- 设备签名、时间窗、nonce 防重放。
- DynamoDB TTL 和 CloudWatch 结构化日志。
- 用脚本化集成测试验证幂等和权限。

### Phase 2：Android 控制面接入

- Android Keystore 设备密钥。
- 配对公钥交换和 AWS 登记。
- `ControlPlaneClient`、WebSocket 在线状态、事件去重。
- 前台邀请弹窗和前台服务通知。
- 邀请接受/拒绝/超时和忙线。

### Phase 3：云端会话与音频

- 接受后取票进房。
- 本地断链 fallback。
- GME `GetMicLevel` VOX 适配器。
- 音频焦点、蓝牙路由、静音。
- 60 秒无 peer 退房。

### Phase 4：回切与恢复

- 本地 shadow probe、10 秒稳定判定。
- 回切 ≤300ms 的音频 arbitration。
- 回切失败留在云端并重试 3 次。
- App 被杀后 10 分钟手动恢复。

### Phase 5：验收

- 先完成 Beta 门槛。
- 再扩展弱网、反复进出、长时、VOX 和蓝牙真实骑行测试。
- 未完成双真机验收前不要对外宣称“云端对讲可用”。

## 11. 实施红线

- AppKey、client token、`authBuffer` 不得进入 Git、日志、截图或最终回答。
- 不保存音频和语音内容。
- 不使用 FCM，不实现冷启动推送。
- 不实现 ASMAX 私有 BLE Opus 网关。
- 不把 `CLOUD` 加入现有 `Transport` 枚举。
- 不在邀请阶段取 GME 票或进房。
- 不在用户主动结束时追进云房。
- 不使用按 deviceId 固定派生的房号。
- 不实现多人产品行为，只预留 `members` 数据结构。
- 不把“模拟器进房成功”当成双真机验收。
- 不修改正式主工作区 `MotoIntercomApp`，除非用户明确切换工作区。

## 12. 参考文件

- 逆向报告：`C:\Users\kuma\Desktop\新建文件夹\摩托HUD数据网络对讲-逆向分析.md`
- 历史云通道方案：`C:\Users\kuma\Desktop\新建文件夹\MotoCom-云通道实施方案-定稿.md`
- 当前 GME 说明：`C:\Users\kuma\Documents\摩托对讲机\MotoIntercomApp-gme\docs\gme-android.md`
- 当前 GME 适配器：`app/src/main/java/com/kuma/motointercom/GmeCloudSession.kt`
- 当前票据客户端：`app/src/main/java/com/kuma/motointercom/GmeTicketClient.kt`
- 当前服务：`app/src/main/java/com/kuma/motointercom/IntercomService.kt`
- 当前首页 UI：`app/src/main/java/com/kuma/motointercom/HomeScreen.kt`
- 当前页面协调：`app/src/main/java/com/kuma/motointercom/MainScreen.kt`
- GME SDK：`app/libs/gme/`（已被 `.gitignore` 忽略，不提交二进制）

## 13. 给下一个模型的执行指令

1. 先读本文件、当前 `GmeCloudSession.kt`、`IntercomService.kt` 和 `docs/gme-android.md`。
2. 不要重新拷问已锁定决策；用户明确要求直接落地。
3. 不要创建 Linear issue、不跑架构门禁、不新建分支。
4. 不要动 `MotoIntercomApp` 主工作区。
5. 先做 Phase 0/1，再动 Android 行为。
6. 每次改动后跑对应测试；最终至少跑完整 Gradle 门禁。
7. 涉及 AWS 的命令使用 `agent-toolkit` profile 和 `ap-southeast-2`。
8. 不输出密钥；不把旧实验票据接口当最终生产接口。
9. 没有双真机验收前，状态只能标记为“实验实现”，不能标记为“已完成”。

## 14. 2026-11-10 推进结果

继续使用 `feat/gme-cloud-intercom`，HEAD 仍为 `b1636f5`，未新建分支、未提交、未合并、未部署，正式工作区未修改。

### Phase 0

- 增加 `RideSession`、`ApprovedCloudRide`、`CloudSessionCoordinator` 和签名 HTTP 传输边界。
- 删除 APK 静态 client token 和旧票据 URL 构建字段；新构建开关 `gme.enabled` 默认关闭。
- 删除点击旧配对即取票进房的服务和 UI 路径。实验开关开启时，未配置说明可以点击查看；本地对讲时在卡片层禁用。
- GME 适配器改为只接收已批准骑行，校验取票结果的骑行、房间、revision 和对端身份。
- 准备阶段静音保留到进房；60 秒无车友退出；云端截止时间使用单调时钟执行；关闭期间不会因迟到的进房回调重新开麦。
- 新增服务入口 `beginApprovedCloudIntercom`，供后续协调器在接受邀请后调用；销毁服务也要等待 SDK `Uninit` 完成才释放进程音频所有权。
- 票据的字符串表示和取票失败日志不包含认证数据。

### Phase 1 本地实现

- 新代码：`backend/gme-control-plane/`。
- 新栈模板：`infrastructure/gme/template.yaml`，目标栈 `motocom-gme`；复用现有 AppKey Secret，不读取旧 client-token / room-salt。
- 实现设备 EC P-256 验签、双签配对、解除配对、随机骑行房号、30 秒邀请、接受/拒绝/忙线、双方确认本地建立后的幂等降级、短期票据、结束、快照、心跳和 WebSocket 单次票据。
- 实现事务 outbox、Streams 推送、过期清理、云端时长提示/暂停/手动延长以及日志、失败队列和告警定义。
- 使用单张 DynamoDB 表保持设备占用、骑行、邀请和事件原子提交，不拆成无法原子协调的多个独立存储。
- 设备每次重新登记生成 `registrationId`，HTTP 业务事务、WebSocket 票据与连接都绑定登记代次。
- 解除配对保留无公钥的短时撤销标记，拒绝尚未过期的旧双签；心跳清除已被 TTL 删除的连接引用。
- 旧实验栈只读结构保存在 `docs/gme-legacy-stack-structure.json`，没有导出 Secret 值。签票辅助算法从旧 Lambda 的无密钥代码恢复，并核对腾讯示例。

### 验证与产物

- 内存模式和真实 DynamoDB Local 模式各 19 项测试通过，覆盖签名、nonce、双签、并发、邀请截止、时长限制和身份更换竞态。
- 只读审查发现的 4 项问题全部修复，并通过独立定向复核；没有新增 P1 发现。
- `cfn-lint`、AWS 只读 `ValidateTemplate`、Lambda ZIP 导入和 HTTP 路由冒烟通过。没有创建或更新云资源。
- 完整 Android 单测、Lint 和 Debug APK 门禁执行，最终计数、产物摘要和验证状态见 `docs/gme-validation-2026-11-10.json`。
- 曾发现增量 APK 仍含旧令牌字符串；清理应用构建目录、禁用构建缓存后重建，最终 APK 与 Lambda ZIP 通过本机已知旧凭据扫描。`scripts/gme/verify-artifacts.py` 可重复运行，不输出凭据值。
- Lambda ZIP：`backend/gme-control-plane/.build/control-plane.zip`；Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。两者都只是本地构建产物。
- 已清理原始交接中的数据库副本、截图、窗口 XML 和崩溃临时日志。

### Phase 0/1 当时的后续计划（已由第 15 节更新）

1. 实现 Android Keystore 设备密钥、公钥与 pairing transcript 交换、云端配对登记及隐私同意时间戳。旧 Room 配对记录继续仅用于本地，不得直接授权云端。
2. 实现 `SignedControlPlaneTransport` 和 `CloudSessionCoordinator`，接上 WebSocket 邀请、前台弹窗、通知栏动作、忙线和快照恢复；接受后才调用 `beginApprovedCloudIntercom`。
3. 再接真实本地断链降级、GME VOX、音频焦点和标准蓝牙路由，随后实现 Phase 4 回切与手动恢复。
4. 云端部署、GME AppKey 轮换、费用/用量告警和通知订阅尚未执行。模板校验不等于 AWS 运行联调；本轮未运行 cfn-guard，也未进行双真机或蜂窝验收。

当前不能标记“云端对讲已可用”。完整 HTTP 字段、签名规范、事件相同 revision 的处理、数据保留和运行命令以 `docs/gme-control-plane.md` 为准。

## 15. 2026-11-10 Phase 2 Android 接入

继续在本实验工作树和原分支推进，未提交、未合并、未部署；没有修改正式主工作区。

### 已落地的实验代码

- `DeviceKeyStore` 使用 Android Keystore EC P-256，只输出公钥和签名。云端隐私同意版本、时间戳、开关、仅 Wi-Fi 设置独立保存。
- `CloudPairingExchange` 在已接受且媒体已建立的本地选定通道交换随机挑战、公钥和 transcript 双签。Hello 能力协商阻止向旧版本发送新增消息；普通旧配对和自动重连不会直接升级为云端授权。
- 新明确本地配对只有双方已有隐私同意时才自动交换证明；旧配对通过设置中的显式重新配对及对方确认完成。完整证明独立于 Room 保存，最长 5 分钟内联网登记，超过时限需要重新配对。
- `ControlPlaneClient` 实现原始正文签名，`CloudControlSocket` 接收 WebSocket 事件。事件按 ID 去重，只丢更低 revision；签名快照修复丢失通知。设备签名和存储在 IO，Runtime/Exchange/协调器状态在主线程串行执行。
- `DefaultCloudSessionCoordinator` 实现在线邀请、接受/拒绝/忙线、30 秒超时、结束、手动恢复和显式延长。接受前不取票，批准后才调用服务内 GME SDK 适配器。重建进程不会自动进房。
- 前台弹窗、已有前台服务的通知动作和过期处理已接入。通知正文只打开提示；动作核对当前 nonce，接受返回可见 Activity 检查麦克风权限。接受和恢复会进入 started Service 生命周期，避免退后台解绑后服务被销毁。
- 取消、解除配对、迟到签名/HTTP 响应和网络策略变化使用代次与持久命令约束。结束队列只清除匹配骑行，旧登记失败不能删除新证明。离线撤销不妨碍本地解除配对。
- 后端补充未登记配对的撤销标记，绑定请求方公钥摘要，阻止先取消后迟到提交双签的竞态；无关设备不能阻止合法配对。
- `ws` 仅作为本地合同测试开发依赖，Lambda 打包在独立目录安装生产依赖，不携带测试服务器。

### 验证边界

本地双 Android JVM 客户端对真实 Node HTTP/WebSocket 控制面运行签名、配对登记、邀请、接受、取票、重建和手动恢复；GME 票据签发与音频宿主使用测试桩。这证明控制面契约互通，不证明腾讯云实际语音已连通。

新增回归覆盖双签篡改/会话绑定、旧配对确认、取消迟到响应、同 revision 事件、邀请时限、网络签名期间策略变化、过期连接、持久撤销、通知快照顺序和服务生命周期。Keystore 仪器测试与完整门禁的最终数量、产物摘要见 `docs/gme-validation-2026-11-10.json`。

完整 Gradle 与模拟器并行曾耗尽 Windows 可提交内存，已改为串行：Gradle 使用 `--max-workers=1` 和 1536m 构建 JVM，再启动模拟器执行仪器测试。不要将这种环境退出记录成产品测试通过。

### Phase 2 当时的后续计划（Phase 3 结果见第 16 节）

1. Phase 3：接入真实本地断链分类、云端媒体仲裁、GME VOX、音频焦点和标准蓝牙路由。当前云端持续开麦，支持手动静音，界面已隐藏不适用于 GME 的本地 VOX/RNNoise 状态。
2. Phase 4：本地 shadow probe、稳定判定和回切；手动恢复及延长已有控制面/界面入口，真实 GME 恢复和回切仍需验证。
3. AWS 新栈部署、费用/用量告警、通知订阅和 GME AppKey 轮换仍未执行。当前构建的 GME 开关保持默认关闭，没有配置新栈端点。
4. 尚未进行双真机、蜂窝和头盔蓝牙验收。继续标记“实验实现”，不得标记“云端对讲可用”。

## 16. 2026-11-10 Phase 3 降级与云端音频

继续在原实验工作树实施；没有提交、合并、发布或部署。交付 APK 的 `GME_ENABLED` 仍为 false，新控制面与 WebSocket 端点仍未配置。

### 已落地

- 只在本地媒体真正连接成功后建立 local lease 并登记骑行；两端分别提交本地成功确认，后端复用同一 ride/room。原生短暂断连沿用现有自愈窗口，选定通道关闭等确认故障才发送一次 fallback。首次连接失败、拒绝、用户结束、已结束骑行和迟到旧连接均不能给本端自动进房授权。
- 部分登记成功后的网络失败保留同一骑行重试。新 lease 不重新授权本地已结束、服务端尚未完成结束的旧 ride；等待旧结束后重试新骑行。ENDED 快照和旧持久化尾部使用匹配 ride 与代次保护。
- Service 等待本地 WebRTC 原生释放、Wi-Fi 清理和路由恢复后才交出全局所有权并启动 GME。云端退出也等待 SDK 和路由释放；清理失败继续阻止第二套音频抢占。取消等待会清除本次启动占位，旧完成回调不能启动新房间。
- 使用腾讯 Android SDK 标准采集/播放、`FLUENCY`、`ANCHOR`，不创建自定义 PCM/BLE 通道。SDK 适配器与测试替身通过 `GmeEngine` 分开。
- `CloudVoxController` 每 20ms 采样 `GetMicLevel`，复用 `VoxGate`，通过 `SetMicVolume(0/100)` 门控；静音优先，旧设置 revision 和迟到重连不能重新开麦。云端显示实际 VOX 状态，隐藏 RNNoise 和不支持的听筒选项。
- `CloudAudioSessionController` 复用本地焦点、电话和标准耳机路由策略。初始化前与进房/重连后等待路由确认；来电/焦点抢占暂停 SDK 采集播放并保留房间，恢复后重新验证。延迟路由恢复重新读取电话状态，避免覆盖等待期间的新来电。
- 60 秒无对端自动退出；云端截止时间在电话中断期间仍运行，到期前 30 分钟提示，4 小时后需手动延长。电话暂停会冻结进房网络超时预算。
- 主动完整停止在有媒体 owner 时先走现有 `DisconnectRequested → TERMINATING → send/flush`，最多等待 1 秒后清理；同步撤销本端 lease，并保留 started Service 直至持久结束工作完成。若网络无法送达断开消息，仍依赖控制面结束传播，对端不能凭空获知尚未收到的操作。

### 验证与真实设备限制

完整结果、测试数量、Lint、产物摘要和 Android 仪器测试证据以 `docs/gme-validation-2026-11-10.json` 为准。新增测试覆盖 SDK 采样/门控、静音、电话暂停、重连、超时、释放屏障、交接取消和跨骑行竞态；双端真实本地 HTTP/WebSocket 合同覆盖同一次本地骑行降级及释放前不取票。GME SDK 时序测试使用替身，不能作为实际腾讯语音验收。

Android 路由仪器测试使用静默音频目标，只验证系统焦点、扬声器路由和恢复；不测试真实 GME 麦克风或头盔。腾讯 SDK 的 0–100 音量分数未与本地 PCM dB 标度校准，零软件增益下采样是否独立、是否完全不上行、漏字/爆音/回声仍需真机测量。当前 JAR 隐藏底层 Uninit 返回值，Java 成功不能替代原生设备释放验收。依据与官方链接见 `docs/gme-android.md`。

### Phase 3 当时的后续计划（Phase 4 结果见第 17 节）

1. Phase 4：本地 shadow probe、10 秒稳定判定、回切音频仲裁与最多 3 次失败重试；当前保持云端，不自动回切。
2. 新栈运行联调、GME AppKey 轮换、费用/用量告警及通知订阅仍未执行。部署与发布需要单独明确指令。
3. 两台真机、一台纯蜂窝、标准蓝牙头盔与 10 分钟双向语音仍未验收；继续标记“实验实现”。

## 17. 2026-11-10 Phase 4 稳定回切与恢复

继续使用本实验工作树与原分支，未提交、合并、部署或发布；正式主工作区未修改。构建开关继续默认关闭。

### 已落地

- `LocalShadowProbe` 独立使用现有 LAN / Wi-Fi Direct 发现与 V2 信令，跟踪 Wi-Fi 出现、IP 改变和默认网络条件；网络探测不启动普通本地运行时，不争取第二份音频/焦点所有权。失败传输退避，旧连接回调受 attempt/adapter 代次约束，所有原生关闭屏障均被保留。
- `ShadowChannel` 经能力协商使用逐帧签名与双方新挑战，绑定配对、骑行、运行会话、传输和连接；3 秒认证超时，严格序号和普通信令用途隔离。预建 WebRTC 的录音、播放保持关闭。
- 新签名接口 `/local-probe`、`/local-switch` 由服务端计算双方相同探测连续稳定 10 秒；新鲜度 3 秒，切换截止 8 秒，每次骑行累计最多 3 次尝试。双方准备后 ARMED，再通过本地 READY / GO 启用媒体；双方 COMPLETE 后才提交本地状态。
- `RideAudioArbiter` 保留原焦点/来电/路由宿主；GME 暂停并验证 SDK 开关和系统活动停止后，才把采集播放授权交给本地。系统活动证据需要 API 26，更旧系统继续云端。GME 在本地确认成功前保留房间和上下文，失败可恢复同房云端。
- 本地原生 PCM、播放和双向 RTP 必须在同一新授权窗口内产生新证据；静音也需证明新 RTP，因此仅回切会话关闭 Opus DTX，保留静音/VOX 门控。停止屏障等待全部解码播放租约与旧录音线程，失败不会假报已释放。Wi-Fi Direct 清理失败也保留失败结果。
- 300ms 是单次本地启用与媒体证据的共同预算；超时关闭探测重试和本地原生音频，等待释放后恢复云端。成功提交后退出 GME，服务端停止云端时间累计；同次骑行回切后再断链仍复用 room。腾讯实际计费以 SDK 退房和账单为准。
- 重建和 60 秒无对端均只提供手动继续，10 分钟有效期由服务端检查；未完成切换先撤销，不自动开麦。结束、取消、关闭云端和解除配对覆盖云端及已回切的本地媒体，旧异步尾部不能覆盖新骑行。

### 验证与边界

最终测试数量、构建、Lint、API 36 原生 WebRTC 仪器测试及产物摘要见 `docs/gme-validation-2026-11-10.json`。签名 HTTP/WebSocket 合同使用真实本地 Node 控制面和实际 10 秒稳定窗口，GME 票据与宿主仍为测试桩。音频切换的原生停止/互斥同时有 JVM 故障时序测试及模拟器 WebRTC 证据；没有进行实际 GME 双端通话。

300ms 代码预算不等于真机端到端 SLA 已验收。后续仍需新栈运行联调、双真机（至少一台纯蜂窝）、标准蓝牙头盔、10 分钟双向语音、来电/锁屏/杀进程恢复、回切断音、重复切换和长时验收。部署、密钥轮换、告警订阅与发布仍需单独明确指令。

## 18. 2026-11-11 Phase 5 测试栈与运行验收

继续使用 `feat/gme-cloud-intercom` 实验工作树，HEAD 为 `b1636f5`。未提交、未合并、未发布，正式主工作区未修改。Phase 5 只部署并验证测试栈，没有做生产部署。

### AWS 测试栈

- 栈 `motocom-gme` 位于 `ap-southeast-2`，账号 `401921623615`，当前状态 `UPDATE_COMPLETE`。DynamoDB 表为 `motocom-gme-Records-3XZEA6Q1EA01`。
- HTTP 基址为 `https://1nzpbifxo6.execute-api.ap-southeast-2.amazonaws.com`，WebSocket 为 `wss://0v8zmyfkuk.execute-api.ap-southeast-2.amazonaws.com/live`。
- 4 个 CloudWatch 告警当前均为 `OK`，告警动作已连接到 `motocom-gme-AlertsTopic-SxRLOE6Pkrxh`。SNS 尚无邮箱订阅，因为本轮没有明确的接收邮箱。
- `explore-aws-budget` 已配置实际费用 80% 和预测费用 100% 告警，并订阅 SNS。腾讯 GME 用量告警仍需在腾讯控制台配置。
- 首轮部署暴露测试账号总并发只有 10 的问题；移除 Lambda 的 10 个预留并发并清理回滚残留后部署成功。变更集现使用时间戳名称，支持重试。

### 线上控制面联调

`scripts/gme/smoke-control-plane.mjs` 已对真实测试栈通过：两台临时 EC P-256 设备完成登记和双签配对，建立两个真实 WebSocket；观察到 `invite.created`、`invite.accepted` 和 `ride.cloud.ready`；接受后两端取得同 Ride、同房间、同 AppID 的 GME 票据并进入 `CLOUD_ACTIVE`；另一条骑行完成双方 `local-established` 和同房 fallback；结束骑行及撤销配对成功。输出未打印私钥、WebSocket token 或 `authBuffer`。

这证明 AWS HTTP/WebSocket、鉴权、状态事务、票据签发和事件推送已经真实工作。GME 是否可听、延迟、回声、路由和计费仍属于腾讯媒体链路验收，不能用此项代替。

### Phase 5 Android 构建与模拟器

- 未入库的 `local.properties` 已启用 `gme.enabled=true`，并配置上面的测试栈 HTTP/WebSocket 地址；旧 `gme.ticket.url` 和静态 `gme.client.token` 已移除。
- API 36 AVD 上完整执行 53 项 instrumentation，失败 0、错误 0、跳过 2。报告位于 `app/build/reports/androidTests/connected/debug/index.html`。
- 模拟器已安装并冷启动 APK；首次引导、云端隐私说明、同意后启用云端入口、未配对车友提示均正常，没有 `AndroidRuntime` 崩溃。
- 仪器测试执行时必须从 `G:\` 映射运行 Gradle。直接使用中文绝对路径会在 CMake 配置阶段产生 `MalformedJsonException`；从映射盘运行后三套 ABI 均成功构建。这是构建工具路径问题，不是产品测试失败。
- 当前通用 Phase 5 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，83,398,138 字节，SHA256 `6574afcd1061550b3d33b31c470510abbc9802961dc4b4064123839838e0e2e3`；已通过本机已知旧凭据扫描。

### 仍缺的 Beta 与正式版门槛

1. 重新连接至少两台真机，其中一台仅走蜂窝数据；当前 `adb devices` 只有 `emulator-5568`。
2. 在腾讯 GME 媒体链路完成 10 分钟双向语音，验证邀请接受、拒绝、超时、忙线，以及标准 HFP/A2DP 头盔采音和播放。
3. 验证本地断链 fallback、实际回切 ≤300ms、无双采集/双播放、静音和 VOX 开关门、漏字、爆音与回声。
4. 验证锁屏、来电抢占、杀进程后手动恢复、弱网/隧道、20 次进出和 4 小时上限。
5. 提供 SNS 邮件接收地址，在腾讯控制台配置 GME 用量告警，并在生产发布前轮换 AppKey。

继续标记为“实验实现”；只有真机 Beta 门槛通过后才能对用户宣称云端对讲可用。提交、合并、发布和生产部署仍需后续明确指令。
