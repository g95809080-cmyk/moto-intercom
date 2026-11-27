# 群组电台 R4 验证记录与预览包

日期：2026-11-22  
基线提交：`7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`（`main` 未提交工作树）  
预览包标识：`com.kuma.motointercom.group.preview` / `2.2.0-r4.preview.2`（`versionCode = 5`；取代已废弃的 `2.2.0-r4.preview.1` / `versionCode = 4`）

## 结论

- R4 工程预览已完成：网络媒体从 R3 自建全互联 WebRTC/TURN/`/signals` 切换为火山引擎 RTC，单公共房间上限 50 人；`tools/group-cloud` 继续作为房间、审批、CAS、群主治理和 60 秒席位恢复的唯一控制面。
- 自动门禁全绿：Node 控制面 **11/11**、主线单测 **876/876**、群组预览单测 **203/203**、两套 Lint 均 **0 错误**；主线 APK、R4 预览 APK、预览仪器测试 APK 均构建成功。
- **单机真实火山 RTC 入房烟测通过**：在小米 13（`efcb9031`，Android 16 / API 36）上通过本地未追踪的 `tools/group-cloud/.local/config.json`（真实实时音视频 RTC 凭证，未写入源码或文档）完成加严版 `GroupPreviewR4DeviceSmokeTest`（`OK (1 test)` / `6.46s`），真实进入火山 RTC 房间（`state=0`、`MODE_IN_COMMUNICATION`）、`1173ms` 上报 `group_voice_ready`、UI 显示「火山引擎 RTC 语音已连接」，且邀请码/会话凭证/RTC Token 零泄露。
- **异地双机公网双向通话实测通过**：使用 `motocom-group-2.2.0-r4.preview.2-7432db04.apk`（`2.2.0-r4.preview.2`，`versionCode = 5`），经临时公网 HTTPS 隧道（`cloudflared` Quick Tunnel，实测完毕已关闭）对接本机 `127.0.0.1:8443` 控制面与真实火山引擎 RTC，完成异地两台真机联调；两台真机均成功接入控制面并自动进入火山 RTC 语音房间，双向外放对讲连通正常、音质清晰无问题。
- 只读 `motointercom-product-architect` 复审最终结论为 **`APPROVED`**，`P0 = 0`、`P1 = 0`。
- 预览包已升级至 `2.2.0-r4.preview.2`（`versionCode = 5`）并通过包体、ABI、签名和 16 KB 对齐核验；主线 `app-debug.apk` 不包含任何火山 RTC 媒体库。
- 首次 R4 预览验证覆盖单机 RTC 入房和异地双机外放对讲。后续公网、Token 和设备验证结果见本文末尾，R4 完整发布验收仍待完成。

## 只读架构门禁

最终输出：

```text
APPROVED / REQUEST CHANGES
APPROVED
P0
None
P1
None
Non-blocking
多设备物理验收仍未完成；RTC Token 仍可进一步收紧为纯音频权限。
Base SHA
7041d3c461fe6e4530d94e3cc7d30c72f48c85c6
Head SHA
WORKTREE on 7041d3c461fe6e4530d94e3cc7d30c72f48c85c6
Next gate allowed
进入 R4 多设备物理设备 Exit Criteria；不得宣称 R4 发布验收完成。
```

本轮真机联调与只读架构审查中关闭的 P0 / P1：

- **P0（跨线程陈旧 `Joined` 回调在终态后夺取媒体状态）**：`VolcRoomEventGate` 原在收到 `JoinFailed`/`ConnectionLost` 后仍接受迟到 `Joined` 且 `onConnectionStateChanged(7)` 绕过 Gate，叠加 Runtime 二次 `main.post` 可使旧 `Joined` 在关闭媒体后仍将单写者 `rtcJoined` 置为 `true` 并阻止重试。修复为三层防线：`VolcRoomEventGate` 终态保持吸收直至显式 `reset()` 且所有引擎回调统一经过 Gate；`GroupCloudRuntime` 引入单调 `rtcGeneration` 与对象身份绑定并在 `leaveRtc()` 立即失效旧排队回调；`GroupCloudSessionOrchestrator` 增加 RTC `attemptId` 与 `rtcJoining || rtcJoined` 复核。补齐 `VolcGroupRtcMediaStateTest`、`GroupCloudSessionOrchestratorTest` 与 Robolectric 主线程队列测试 `GroupCloudRuntimeRtcGenerationTest` 后复审关闭。
- **P1（真机启动 `NoClassDefFoundError: android.support.v4.content.ContextCompat`）**：火山 SDK 3.60 音频设备层在排除旧 `com.android.support` 后仍调用 `ContextCompat.checkSelfPermission(Context, String)`（经上游字节码全量扫描确认 9 个调用类仅调用该单一方法）。在 `app/src/groupPreview/java/android/support/v4/content/ContextCompat.java` 新增仅限 `groupPreview` 的最小兼容桥，避免引入完整 `support-compat` 冲突且不污染主线 APK。
- **P1（火山 Android SDK V3.60 契约对齐）**：修正远端静音按 V3.60 `streamId`（而非 `userId`）调用 `subscribeStreamAudio` / `setRemoteAudioPlaybackVolume`（通过 `VolcRemoteAudioMuteTracker` 兼容普通与音频选路回调）；将音频场景从 `DEFAULT` 修正为 V3.60 通话音量映射 `CHATROOM`（真机已确认 `setMode=3 / MODE_IN_COMMUNICATION`）；补齐 `startAudioCapture()` / `stopAudioCapture()` 生命周期与音频焦点丢失/恢复时成对的 `pauseAllSubscribedStreamAudio()` / `resumeAllSubscribedStreamAudio()`；对 3.60 同时触发的 `onRoomStateChangedWithReason` 与 `onRoomStateChanged` 做去重，并补充 `-1001`（非 RTC/WTN AppID）与 `-1080`/`-1070` 可见错误提示。
- **P1（初审房间状态码误读）**：修正 `onRoomStateChanged` 按 `0 = 成功、非 0 = 错误码` 处理，兼容 `onRoomStateChangedWithReason` 与 `onLeaveRoom`。

通过的边界：

- 网络模式保持单公共频道，`ownerId` 与 `hostDeviceId = null` 分离；没有领队/私聊媒体入口。
- 本地 4 人、主线 1:1 与 `stream-webrtc-android` 依赖未改动；火山 SDK 与 `ContextCompat` 最小桥只在 `groupPreview` 中。
- `AppKey`、OpenAPI AK/SK 只从服务端 `.local/config.json` 或环境变量读取，`.local/` 已被 `tools/group-cloud/.gitignore` 忽略。
- 客户端只接收 `{ appId, rtcRoomId, rtcUserId, rtcToken, expiresAtMs }`；Token、邀请码、Session 凭证未进入通知或结构化诊断。
- 只有本人 `ADMITTED` 且在线成员 `>= 2` 才创建 RTC；单人等待、待审批、回落到 1 人、移除、解散和断线恢复时退出 RTC。
- 释放路径执行 `leaveRoom -> destroy RTCRoom -> stopAudioCapture -> destroyRTCEngine`，并释放音频焦点；本地模式与 1:1 不创建 `RTCEngine`。

## 自动化验证

| 检查项 | 命令 / 工件 | 结果 |
|---|---|---|
| 云端控制面 | `node --test server.test.mjs` | **11/11 通过**（0 失败/跳过） |
| 主线单测 | `:app:testDebugUnitTest`（105 个 XML） | **876/876 通过**（0 失败/错误/跳过） |
| 群组预览单测 | `:app:testGroupPreviewUnitTest`（33 个 XML） | **203/203 通过**（0 失败/错误/跳过） |
| 主线 Lint | `:app:lintDebug` | **0 错误，89 警告** |
| 群组预览 Lint | `:app:lintGroupPreview` | **0 错误，96 警告** |
| APK 构建 | `:app:assembleDebug`、`:app:assembleGroupPreview` | 成功 |
| 仪器测试 APK | `:app:assembleGroupPreviewAndroidTest -PtestBuildType=groupPreview` | 成功 |
| 单机真机 R4 烟测 | 小米 13 `efcb9031`（Android 16），`GroupPreviewR4DeviceSmokeTest` | **1/1 通过**（`6.46s`，`group_voice_ready` `1173ms`，`state=0`，零泄露） |

## 单机真机实测记录（小米 13 `efcb9031`）

- 环境：小米 13（Android 16 / API 36）通过 `adb reverse tcp:8443 tcp:8443` 连接本机 `tools/group-cloud`（读取未追踪的 `.local/config.json` 真实实时音视频 RTC 凭证；未配置 OpenAPI AK/SK）。
- 测试范围：仅预授网络模式 4 项运行时权限（`RECORD_AUDIO`、`READ_PHONE_STATE`、`BLUETOOTH_CONNECT`、`POST_NOTIFICATIONS`，不授定位/近场 Wi-Fi 权限）；创建需审批房间 → 远端 HTTP 控制面客户端提交申请 → 群主批准至 `2/50` → 懒进房触发真实火山引擎 RTC 建连与入房 → UI 确认「火山引擎 RTC 语音已连接」→ 轮换 8 位邀请码 → 收起至前台通知并校验零邀请码泄露 → 恢复页面 → 群主原子转让并离房 → 远端成员晋升群主并解散房间 → 校验 `MotoComGroup` 诊断包含 `group_cloud_create`、`group_cloud_approval`、`group_voice_ready`、`group_cloud_owner_transfer`、`group_end` 且无任何邀请码/Token 泄露。
- 实测关键日志：
  - `group_cloud_rtc_connection_state state=3`、`group_cloud_rtc_room_state source=typed state=0 reason=0`、`group_voice_ready channel=PUBLIC elapsedMs=1173`。
  - 火山 SDK 原生日志确认 `SetAudioMode setMode=3`（`MODE_IN_COMMUNICATION`）、`joinRoom` 与 `publish` 信令应答成功、离房触发 `OnLeaveRoom`。
- 联调排障留档：使用「WTN传输网络」控制台下的应用时，Token 鉴权通过后 `joinRoom` 会返回 `404 AppID Illegal`（SDK `-1001`）且无房间模式开关；切换至「实时音视频 RTC」控制台应用后一次通过，已在 `tools/group-cloud/README.md` 与客户端错误文案中明确提示。

## 异地双机公网实测记录

- 产物与环境：两台异地真机均安装交付预览包 `motocom-group-2.2.0-r4.preview.2-7432db04.apk`（`2.2.0-r4.preview.2`，`versionCode = 5`），通过临时 Cloudflare Quick Tunnel（`cloudflared 2026.11.1` 提供临时公网 HTTPS 入口）转发至本机 `http://127.0.0.1:8443` `tools/group-cloud` 控制面，并对接真实火山引擎 RTC。
- 实测结果（通过）：用户实测确认两台异地真机均可正常连接公网控制面并自动进入火山 RTC 语音房间，双向外放对讲连通正常、音质清晰无问题。
- 环境收尾：实测结束后已关闭临时 `cloudflared` 隧道并确认无残留进程，原临时公网地址已失效；未改动代码库、Git 状态或凭证配置。
- 未覆盖边界：本次实测仅验证异地双机公网控制面接入、自动进房与双向外放人声互通；未覆盖 30 秒前 Token 续签（`updateToken`）、成员级本地静音与活跃发言人指示、开启「音频选路」后的 50 人并发与额度消耗、可选 `BanRoomUser`、蓝牙头盔 SCO 及系统来电焦点恢复。

## 开发期公网控制面常驻部署（纯 IP + 受信任 IP SSL 证书）

- 方案背景：因公网域名仍在等待 ICP 备案审批，开发联调期先采用自建公网服务器的「纯 IP + Let's Encrypt `shortlived` 受信任 IP SSL 证书」方案（免域名备案），后续备案完成后可直接切换域名证书。
- 服务端部署：`tools/group-cloud` 已部署至服务器 `/srv/group-cloud` 并由 `moto-group-cloud.service`（独立低权限用户 `groupcloud`）常驻托管于 `127.0.0.1:8443`（不直接暴露公网端口），凭证存于仅本机可读的 `.local/config.json`（`0600`，未入库）。
- TLS 与反代：通过 `acme.sh 3.1.7` 以 HTTP-01（`/.well-known/acme-challenge/`）签发 Let's Encrypt `shortlived`（EC-256，6 天有效期，经 `YE1 -> Root YE -> ISRG Root X2 -> ISRG Root X1` 交叉签名链）纯 IP 证书并配置 6 小时周期 ARI 自动续期与 `systemctl reload nginx`；Nginx 在 `443` 端口挂载 `/group-cloud/` 反向代理至 `127.0.0.1:8443/`（关闭缓冲、`proxy_read_timeout 60s`），既有 `/healthz`、`/api`、`/upload`、`/files/` 路由保持不变。
- 验收结果（2026-11-23）：本机严格 TLS 校验与小米 13 Android 原生系统根证书库（`/apex/com.android.conscrypt/cacerts`）均通过 `/group-cloud/healthz` 与既有 `/healthz`；公网 HTTPS 端到端自检完成建房、AccessToken v001 签发/续签、事件长轮询与离房结束全流程，未改动 Android 源码或预览包。本轮 R4 联调至此暂告段落，可回归主线推进 R5。

最终门禁命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testGroupPreviewUnitTest :app:lintDebug :app:lintGroupPreview :app:assembleDebug :app:assembleGroupPreview --console=plain --max-workers=2
.\gradlew.bat :app:assembleGroupPreviewAndroidTest -PtestBuildType=groupPreview --console=plain --max-workers=2
```

## APK 与包体审计

- `aapt2 dump badging`：包名 `com.kuma.motointercom.group.preview`，`versionName=2.2.0-r4.preview.2`，`versionCode=5`，`minSdk=23`，`targetSdk=36`。
- `apksigner verify`：v1/v2 签名有效；当前为 Android Debug 证书（SHA-256 `3c19cbe0fc21bc106f7ecc34571e221cc734f1b713e635fd867d357443983601`）。
- `zipalign -c -P 16 4`：通过。
- 三个 ABI 均包含 `libvolcenginertc.so` 与 `libc++_shared.so`；`arm64-v8a`、`armeabi-v7a` 另含 `libbytenn.so`。
- `x86_64` 的 `libbytenn.so` 缺失来自上游 `VolcEngineRTC-3.60.108.100.aar` 本身，不是本地裁剪误删；发布/真机目标是 ARM ABI。
- `libbytertc_videosr_extension.so`、视频锐化/降噪、BMF、H.265/VP8、FDK AAC、FFmpeg 音频扩展等已排除，预览包中匹配数为 0。
- 主线 `app-debug.apk`（`58,475,099` bytes）的 Volc/bytenn/ContextCompat 条目数为 `0`；主线包体未引入火山 RTC SDK。

## 交付产物

- 路径：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.2.0-r4.preview.2-7432db04.apk`
- 校验文件：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.2.0-r4.preview.2-7432db04.apk.sha256`
- 文件大小：`124,294,907` bytes（`118.54 MiB`）
- SHA-256：`7432db044a19af136660129a558d912bb7fee6717897b523d6ca46feaff3a076`
- 已废弃旧包：`output/motocom-group-2.2.0-r4.preview.1-b0cf446a.apk`（`versionCode = 4`，存在 `ContextCompat` 崩溃、V3.60 远端静音参数及跨线程陈旧回调缺陷，不再用于分发）。

## 11 月 23 日公网控制面复验（`2.3.0-r5.preview.4`）

本轮使用已更新的公网 HTTPS 控制面。部署后的服务日志记录到 8 个测试房创建和 8 个解散，房间逐一对应，服务端没有报告清扫或 RTC 吊销失败。Android 主线 881 项、群组预览 250 项及 Node 控制面 30 项测试重新执行，全部通过。

| 检查 | 实测结果 |
| --- | --- |
| 网络房容量 | 2.1 秒内准入 50 个不同身份，房主看到 50 个 `ADMITTED` 成员。第 51 个请求返回 `409 ROOM_FULL`。此项仅建立控制面会话。 |
| 审批与续签 | 使用同一请求凭证两次领取获批结果，得到相同成员会话。该会话可以为同一 RTC 房间和用户续签 Token。 |
| 掉线后重入 | 一名测试成员停止心跳后进入 `RESERVED`，在保留窗口内调用 `/resume` 回到 `ADMITTED`。从最后一次活动到恢复共 28.9 秒，成员代次递增，获得同一 RTC 房间的新 Token。 |

### 小米 13 跨 Token 到期点实测

手机运行 `com.kuma.motointercom.group.preview`（versionCode 9），机内 APK 的 SHA-256 与交付包一致。探针在独立网络房中以静音模式运行手机上的 `GroupCloudRuntime` 和真实火山 RTC SDK，另一个席位由 HTTP 客户端保持在线。

`GroupPreviewR4TokenRenewalTest#liveRtcRenewsBeforeExpiryAndStaysJoined` 连续两轮通过。下表以服务器签发的到期时间和手机墙钟计算剩余时间。

| 公网实测 | 总用时 | 距旧 Token 到期 | 新到期点延后 | SDK 返回码 | 应用入房次数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 首轮 | 607.723 秒 | 发起调用时 29.863 秒 | 570 秒 | 0 | 1 |
| 最终探针复跑 | 605.540 秒 | SDK 返回时 29.310 秒 | 571 秒 | 0 | 1 |

首轮从启动到语音就绪为 1.102 秒。最终探针复跑中，在同一次 SDK 调用的起止标记之间读到唯一一条 `group_cloud_rtc_token_update code=0`。两轮跨过旧到期点后，手机均报告 `JOINED`。此次采集的是连接状态，双向听感需第二个真实媒体端验证。

最终探针经 `motointercom-product-architect` 只读复核通过，P0、P1 均为 0。Base 和 Head 为未提交工作树的基线 `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`。

复验时在群组测试 APK 的 AndroidJUnitRunner 中选择上述方法，并传入 `-e rtcRenewalUrl https://110.41.77.64/group-cloud`。省略该参数会跳过这项公网测试。

## 仍待完成的物理设备门禁

1. **双机进阶交互验收**。异地双机外放对讲已有早期 R4 实测记录，`preview.4` 已完成上述单机自动续签。仍需验证续签期间的双向听感、成员级本地静音（`subscribeStreamAudio(streamId, false)`）和活跃发言人指示。
2. **50 人媒体并发与音频选路**。公网控制面已实测 50 个可见席位和第 51 人的拒绝结果。仍需多个真实发布端验证超过 5 路音频时的选路、活跃发言人下行、成员级静音及 50 人媒体并发。
3. **强踢与恢复**。公网控制面已验证成员保留及 60 秒窗口内的恢复和重新签发。仍需用真实媒体客户端验证被移除成员停止收听，以及手机断网后恢复通话的表现。
4. **额度、蓝牙与道路稳定性（NOT RUN）**：实测 10,000 分钟免费额度消耗与单人退房待机护栏、蓝牙头盔 SCO 切换、系统来电焦点暂停/恢复、跨 Wi-Fi/蜂窝 NAT 与长时骑行稳定性。
5. R1 四台本地真机、R2 多设备回归和 R5 双模仍不在本轮完成范围内。
