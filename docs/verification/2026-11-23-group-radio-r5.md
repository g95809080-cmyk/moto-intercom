# 群组电台 R5（双模版 `HYBRID`）验证记录与预览包

日期：2026-11-23  
基线提交：`7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`（`main` 未提交工作树）  
预览包标识：`com.kuma.motointercom.group.preview` / `2.3.0-r5.preview.2`（`versionCode = 7`，应用名 `摩声群组 R5 预览`；替代已安装旧版 `2.3.0-r5.preview.1` / `versionCode = 6`）

## 结论

- R5 双模（`HYBRID`）工程预览与三轮只读架构审查已全部通过：4 人硬上限、云端控制面唯一权威、本地 Wi-Fi Direct 优先、本地失联整组切云（veRTC）、全员邻近恢复整组切回本地、公共/领队/私聊三频道隔离、15→30→60 秒退避（需连续 60 秒本地就绪稳定窗口清零）、双向 fail-closed 媒体静默屏障（零双路音频）以及全路径同步 OpenAPI 权威吊销均已落地并经自动化验证。
- 自动化门禁全绿：Node 控制面 **21/21**、主线单测 **879/879**、群组预览单测 **221/221**、`:app:lintDebug` 与 `:app:lintGroupPreview` 均 **0 错误、0 警告**；主线 `app-debug.apk`（`volc_matches = 0`）与 R5 预览 `app-groupPreview.apk` 均构建并通过包体、ABI、签名和 16 KB 对齐核验。
- 只读 `motointercom-product-architect` 第三轮复审最终结论为 **`APPROVED`**，`P0 = 0`、`P1 = 0`。
- **未完成退出门禁（NOT RUN）**：本轮仅完成自动化单测、模拟 HTTP/OpenAPI 端到端测试、Robolectric 屏障测试、Lint 与产包核验，尚未执行真机多设备物理验收与主线 1:1 真机回归；尚不视为 R5 正式发布验收完成。

## 只读架构门禁

最终输出（第三轮 `r5_architect_review_v3`）：

```text
APPROVED / REQUEST CHANGES
APPROVED
P0
无。
P1
无。
Non-blocking
已只读核对指定源码、回归测试、文档与 Manifest；未重跑构建或测试。未发现新增死锁、竞态、fail-open 或回归。
Base SHA
7041d3c461fe6e4530d94e3cc7d30c72f48c85c6
Head SHA
7041d3c461fe6e4530d94e3cc7d30c72f48c85c6（含未提交工作树）
Next gate allowed
是。
```

三轮只读审查关闭的全部 P0 / P1 缺陷：

- **首轮关闭（4 P0 + 3 P1）**：
  - `HYBRID/LOCAL` 零 RTC 签发：建房、直入/审批、恢复、频道/私聊操作与切回 `LOCAL` 全部返回 `rtc = null` / `rtcRoomId = null`，`POST /rtc-token` 返回 `409 TRANSPORT_NOT_CLOUD`；Android 侧补齐 `optNullableString` 修复 JSON `null` 转 `"null"` 问题。
  - 强制 OpenAPI 权威吊销（显式写操作）：移除 `volc-openapi.mjs` 静默 `skipped` 旁路，启动期强制要求 OpenAPI AK/SK；引入 per-room 串行锁与传输代际隔离 `rtcRoomId`（`_e<epoch>_pub` / `_rider` / `_p_<id>`），移除/离开/解散/群主转让/角色降级/频道切换/私聊开关/`CLOUD -> LOCAL` 均在提交状态前同步 `await BanRoomUser`，授权重入通过 `UpdateBanRoomUserRule` 解封。
  - 双向 fail-closed 媒体清理屏障：`VolcGroupRtcMedia.releaseVolcRtcResources` 全步骤执行并聚合抛错；`RiderAudioEngine` 增加 `onDisposeFailed`，仅清理成功时触发 `onDisposed`，清理失败进入 `HybridMediaPhase.BARRIER_FAILED` 并锁定 `GroupRuntimeOwnership`，绝不启动对侧媒体；同时恢复 `RiderMediaMode.SINGLE` 播放错误走引擎级 `audioDeviceFailure("playout", ...)` 的主线 1:1 行为。
  - 退避稳定窗口：`room-store.mjs` 引入 `localStableResetMs = 60_000` 与 `localStableSinceMs`，需全体在线已准入成员连续保持本地就绪满 60 秒才清零 `fallbackStreak`。
- **第二轮 → 第三轮关闭（2 P0 + 2 P1）**：
  - **P0-1（`HYBRID/CLOUD` 缺失 `rtcRoomId` 严禁复用旧 Token）**：`GroupHybridSessionOrchestrator.kt` 要求 `CLOUD` 下 `payload.rtcRoomId` 非空且严格相等；缺失时清空 `rtcToken` 与 `rtcRefreshInFlight`，拒绝 `JoinRtc`/`RefreshRtcToken`，忽略陈旧/不匹配 `RtcTokenRefreshed`，并在已处于 `CLOUD_ACTIVE` 时立即触发 `LeaveRtc`。
  - **P0-2（异步 `sweep()` 全路径同步 fail-closed 吊销）**：彻底删除 `_enqueueBackgroundRevocation`、`pendingRevocations`、`flushPendingRevocations`；拆分 `_sweepGlobalExpired` / `_sweepRoomUnlocked` / `sweepRoom` / `sweep` 与 `_authenticateUnlocked` / `authenticate`；`ADMITTED -> RESERVED`（含 `PUBLIC`/`RIDER`/`PRIVATE` 活跃凭证）、非群主 `RESERVED` 恢复超时移除、群主 `RESERVED` 恢复超时解散房间全部在 per-room 串行锁内同步 `await BanRoomUser` 后再提交状态、推进 `revision` 与广播；OpenAPI 故障时保持状态未提交并拒绝超时席位 `/resume` 或新凭证签发（`502 RTC_REVOCATION_FAILED`）；所有剩余路由接入 per-room 锁并在 `server.mjs` 补齐 `await` 与定时 sweep 防重入保护（长轮询在释放锁后等待）。
  - **P1-1（关键反例回归测试）**：`GroupHybridSessionOrchestratorTest` 新增 `cloudSnapshotWithNullRtcRoomIdClearsCachedTokenNeverJoinsAndDrainsActiveCloud`；`tools/group-cloud/server.test.mjs` 新增基于真实回环 HTTP `FakeVolcRtcServer` 的 3 项 sweep 吊销测试（覆盖 `ADMITTED -> RESERVED`、非群主恢复超时移除、群主恢复超时解散在 OpenAPI 挂起延迟与 HTTP 500 下的未提交保障、提交前旧 Token 立即拒绝顺序及 60 秒内 `/resume` 的 `UpdateBanRoomUserRule` 解封重入）。
  - **P1-2（文档与 Manifest 同步）**：同步更新 `docs/plans/2026-11-23-group-radio-r5.md` 并修正 `app/src/groupPreview/AndroidManifest.xml` 预览标签为 `摩声群组 R5 预览`。

## 自动化验证结果

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| Node 控制面单测与 FakeVolcRtcServer 端到端 | `node --test tools/group-cloud/server.test.mjs` | **21/21 通过**（0 失败） |
| Android 主线单测 | `.\gradlew.bat :app:testDebugUnitTest` | **879/879 通过**（105 文件，0 失败，0 跳过） |
| Android 群组预览单测 | `.\gradlew.bat :app:testGroupPreviewUnitTest` | **221/221 通过**（37 文件，0 失败，0 跳过；含 `GroupHybridSessionOrchestratorTest` 7/7） |
| 主线与预览 Lint | `.\gradlew.bat :app:lintDebug :app:lintGroupPreview` | **0 错误、0 警告**（`lint-results-debug.sarif` 与 `lint-results-groupPreview.sarif` 均 0/0） |
| 主线与预览 APK 构建 | `.\gradlew.bat :app:assembleDebug :app:assembleGroupPreview` | **BUILD SUCCESSFUL**（经临时纯 ASCII junction 构建，收尾已安全删除） |

## APK 与包体审计

- `aapt2 dump badging`：包名 `com.kuma.motointercom.group.preview`，`versionName=2.3.0-r5.preview.1`，`versionCode=6`，`minSdkVersion=23`，`targetSdkVersion=36`，`application-label='摩声群组 R5 预览'`。
- `apksigner verify`：v1/v2 签名有效；当前为 Android Debug 证书（SHA-256 `3c19cbe0fc21bc106f7ecc34571e221cc734f1b713e635fd867d357443983601`）。
- `zipalign -c -P 16 4`：通过（exit 0）。
- 原生库核验：`arm64-v8a`、`armeabi-v7a`、`x86_64` 均含 `libvolcenginertc.so`、`libRTCFFmpeg.so`、`libc++_shared.so`、`libjingle_peerconnection_so.so`、`libmoto_rnnoise.so`；`arm64-v8a` 与 `armeabi-v7a` 含 `libbytenn.so`；已裁剪的视频超分/降噪/锐化/BMF/H.265/VP8/FDK AAC/FFmpeg 音频扩展匹配数为 `0`。
- 主线隔离核验：`app-debug.apk`（`59,502,475` bytes，SHA-256 `c857a0cf08c08b9e37ddc56d513c5a68f33773f6adf030bd976b8cf36f070a3c`）的 Volc / bytenn / ContextCompat 条目数为 **`0`**。

## 交付产物

- 路径：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.1-8756f777.apk`
- 校验文件：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.1-8756f777.apk.sha256`
- 文件大小：`125,461,855` bytes（`119.65 MiB`）
- SHA-256：`8756f777e5c5732f25a78e07e060840be7dacd7b76eff579709fddcaa63e6fdf`

## 真机安装与云服务器 R5 控制面部署（2026-11-23）

- **测试真机安装**：已通过 Tailscale ADB 将 `motocom-group-2.3.0-r5.preview.1-8756f777.apk` 覆盖安装至小米 13（`2211133C`），机内核验 `com.kuma.motointercom.group.preview` 为 `2.3.0-r5.preview.1`（`versionCode = 6`），正式主包 `com.kuma.motointercom` 保持 `1.2.3+f587472`（`versionCode = 7`）未改动。
- **云服务器升级**：经用户明确授权，已将 R5 版 `tools/group-cloud` 同步至云服务器 `/srv/group-cloud`（`root:groupcloud` `0640`），并通过标准输入将火山 OpenAPI AK/SK 安全写入仅本机 `groupcloud` 可读的 `/srv/group-cloud/.local/config.json`（`0600`，未入库、未落日志）；`moto-group-cloud.service` 重启后处于 `active (running)`，日志确认 `openApiBan: true`。
- **真实火山 OpenAPI 与公网 HTTPS 自检通过**：
  - 直连真实 `https://rtc.volcengineapi.com` 实测单人 `BanRoomUser`、`UpdateBanRoomUserRule` 解封与整房 `BanRoomUser` 均返回 `success`。
  - 严格 TLS 公网自检 `/healthz`（`200`）与 `/group-cloud/healthz`（`200`，`openApiRevocationEnforced: true`）通过；完成 `HYBRID` 建房（`LOCAL` 零 RTC 签发 + `/rtc-token` 返 `409 TRANSPORT_NOT_CLOUD`）、`LOCAL -> CLOUD`、`PUBLIC -> RIDER -> PUBLIC`（真实 OpenAPI 吊销与解封）、`CLOUD -> LOCAL` 整房吊销、解散以及 `NETWORK` 50 人建房/解散全链路闭环。

## 增量变更：云端邀请码改为随机 6 位数字（`2.3.0-r5.preview.2`）

- **改动内容**：按用户要求将云端邀请码由 8 位字母数字混合改为随机 6 位数字（`000000`–`999999`，`crypto.randomInt` 均匀随机生成，输入自动忽略空格与连字符并弹出数字键盘），同步更新服务端、Android `groupPreview` 端与测试。
- **验证与只读复审**：Node 控制面 **21/21**、`:app:testGroupPreviewUnitTest` **221/221**、`:app:lintGroupPreview` **0 错误/0 警告**；只读 `motointercom-product-architect`（`r5_invite_6digit_review`）结论 **`APPROVED`**（`P0 = 0`、`P1 = 0`；Non-blocking 建议公网环境后续可为 `/v1/rooms/join-requests` 增加速率限制）。
- **最新产包与同步部署**：
  - APK：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.2-53dbb4f6.apk`（`124,362,880` bytes，SHA-256 `53dbb4f68650c2fcda5b300f3b4a0ef505ed367c0ea3204aff30b4d6d9c8c7aa`，`zipalign -c -P 16 4` 与 `apksigner verify` v1/v2 通过）。
  - 云服务器 `/srv/group-cloud` 已同步重启并通过公网 HTTPS 实测（生成 6 位数字邀请码，拒绝旧 8 位字母数字码返回 `400 INVALID_INVITE`）；小米 13 已覆盖安装至 `2.3.0-r5.preview.2`（`versionCode = 7`），正式主包未改动。

## 增量升级：本地优先自救 + 路线 A 免弹窗静默直连 + 50 米两阶段预连回切（`2.3.0-r5.preview.3`）

- **改动内容**：
  - **本地优先与断连宽限**：语音抖动保持原地 `RestartLink`（10 秒内最多 2 次）；成员 TCP/Wi-Fi 瞬断先本地静默自救最多 3 次 / 10 秒（T+0s / T+3s / T+6s；第 2 次起丢弃缓存频点退回 2.4GHz 全信道扫描），`cloudOffline == true` 时超 10 秒仍持续本地重试；房主单人掉线宽限放宽至 13 秒（`LOCAL_PEER_DROP_GRACE_MS = 13_000L`）；全链路拆分单调时钟 `nowMs` 与墙钟 `wallNowMs`。
  - **Android 10+ 路线 A（Fast Connection API）**：服务端按阶段最小暴露 `localP2pSsid`（`DIRECT-mc-xxxxxxxx`）、16 位随机 `localP2pPassphrase`、`localControlPort = 8899`、`localHostAddress` 与 `localFrequencyMhz`；移除非房主成员（含 `LOCAL` 阶段立即推进 `transportEpoch` 并保持 `active = LOCAL` 零 RTC 签发）或预连中止时立即轮换全部路线 A 凭据与 `localCode`。房主与客户端统一频点与 2.4GHz band 互斥配置；客户端通过独立 `p2p0` 网卡静默入网并显式绑定 Socket，不踢断蜂窝/主 Wi-Fi；`GroupWifiP2pClient.close()` 通过回调 fencing、`cancelConnect` 协调、1.5s 兜底与 `confirmAbsent()` 杜绝 lease 泄漏或误拆后继组。
  - **50 米探测与零断音预连切回**：高功率 BLE + 31 秒低频 Wi-Fi 扫描读取真实房主 RSSI（`>= -75 dBm`，无精细定位权限时显式降级至高功率 BLE 并上报 `localRssiDbm = null`，仍受冷却、12 秒稳定期与 `PRECONNECTING` 约束）；`PRECONNECTING` 阶段不断开火山 RTC、仅在后台建立控制面 `p2p0` + PAKE TCP 心跳（绝不创建 `GroupAudio`），全员 `localControlReady = true` 后服务端在 per-room 锁内同步 `await BanRoomUser` 并附带 `handoverFromEpoch` 原子切回 `LOCAL`，客户端排空火山 RTC 后通过 `PromotePreconnectToLocal` 原子移交控制面并缓存提前到达的 `Roster`。
- **自动化验证结果**：
  - 控制面：`node --test tools/group-cloud/server.test.mjs` — **25/25 通过**（含 `LOCAL` 阶段非房主移除轮换凭据与零日志泄露、BLE 降级 `localRssiDbm = null` 完整约束、`-75 dBm` 边界、预连失败/20s 超时静默退避）。
  - Android 单测：`:app:testGroupPreviewUnitTest` — **236/236 通过**；`:app:testDebugUnitTest` — **879/879 通过**（`UP-TO-DATE`，本轮 Route A 改动未新增触碰 `app/src/main`）。
  - Lint 与编译：`:app:lintDebug`、`:app:lintGroupPreview` 均 **0 错误**；`:app:assembleDebug`、`:app:assembleGroupPreview` 与 `-PtestBuildType=groupPreview :app:compileGroupPreviewAndroidTestKotlin`（含新增真机探针 `GroupPreviewRouteAProbeTest`）全部通过。
- **只读架构门禁复审**：
  - 首轮（`r5_local_first_route_a_review`）提出 3 条 P1（`LOCAL` 非房主移除凭据轮换、`GroupWifiP2pClient.close()` 取消连接 lease 泄漏、`GroupWifiHost` 频点/频段互斥）与 2 条 Non-blocking（`enterAdmittedSession` 墙钟校验、`localRssiDbm = null` BLE 降级路径显式化），已全部修复并补齐自动化测试。
  - 复审（`r5_local_first_route_a_review_v2`）结论：**`APPROVED`**（`P0 = 0`、`P1 = 0`；Base/Head SHA `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`，未提交工作树；允许进入 Route A 双机/多机真机验证与预览包更新）。
- **最新产包与主线隔离核验**：
  - 预览包 APK：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.3-a964e17b.apk`（`124,412,028` bytes / `118.65 MiB`，`versionName = 2.3.0-r5.preview.3`，`versionCode = 8`）
  - 校验文件：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.3-a964e17b.apk.sha256`
  - SHA-256：`a964e17ba7da2db139c9cff232977d798d360f0ee5217942e0296af926cd6264`
  - 打包校验：`zipalign -c -P 16 4` 通过、`apksigner verify` V2 签名通过；`arm64-v8a` / `armeabi-v7a` 均完整打包 `libvolcenginertc.so`、`libbytenn.so`、`libc++_shared.so` 且排除的视频超分/特效/编解码 `.so` 为 `0`；主线 `app-debug.apk`（`59,502,475` bytes，SHA-256 `c857a0cf08c08b9e37ddc56d513c5a68f33773f6adf030bd976b8cf36f070a3c`）Volc/bytenn/RTCFFmpeg 条目数为 **`0`**。
- **云端同步部署与小米 13 真机初验（2026-11-23）**：
  - 云服务器 `110.41.77.64`：`/srv/group-cloud` 全部 6 个运行文件已同步并完成 SHA-256 逐一校验一致（保留 `/srv/group-cloud/.local/config.json` 与 `root:groupcloud` `0640` 权限），`moto-group-cloud.service` 重启后 `active (running)`（`maxMembers: 50`, `openApiBan: true`）。
  - 公网 HTTPS 实测（`https://110.41.77.64/group-cloud`）：`GET /healthz` 返回 `200`；`POST /v1/rooms`（`HYBRID`）返回 `201`（6 位纯数字邀请码、`transport.active = LOCAL`、`epoch = 1`、`recoveryStage = IDLE`、`DIRECT-mc-xxxxxxxx` + 16 位口令 + `localControlPort = 8899`、`rtc = null`）；`HYBRID/LOCAL` 请求 `/rtc-token` 返回 `409 TRANSPORT_NOT_CLOUD`；`/transport/local-status` 上报 `192.168.49.1` 与 `2437 MHz` 正常回传；旧 8 位邀请码返回 `400 INVALID_INVITE`；测试房 `/end` 正常解散，服务端 `journalctl` 零敏感凭据泄露。
  - 小米 13（`2211133C`，Android 16 / API 36 / HyperOS，无线调试 `100.84.141.14:5555`）：已覆盖安装 `com.kuma.motointercom.group.preview` 至 `2.3.0-r5.preview.3`（`versionCode = 8`），正式主包 `com.kuma.motointercom`（`1.2.3+f587472`）保持不变。
  - 小米 13 单机路线 A 房主探针：`GroupPreviewRouteAProbeTest#routeAHostCreates24GhzPresetGroupAndReleasesCleanly` **PASS（0.731s）** — HyperOS Android 16 下 `WifiP2pConfig.Builder` 2.4GHz 预设 GO（`DIRECT-mc-probe24g`）0 弹窗建组成功、实际频点位于 2.4GHz、获取 IPv4 地址、建组过程未踢断现有 Wi-Fi / Tailscale 无线调试连接，且 `GroupWifiHost.close()` 正常释放 `GroupGoOwnership` 返回 `RELEASED`。

## R5 审查修复（`2.3.0-r5.preview.4`，versionCode 9）

本轮接续 11 月 23 日的未提交更改审查，基线为 `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`。

| 范围 | 回归场景 |
| --- | --- |
| 房间与审批 | 活跃会话滑动续期，空闲会话到期。获批响应丢失后可以重领，成员被移除后重领返回终止原因。结束房间在 5 分钟后回收，定时任务每轮只做一次全局清扫。 |
| 网络 RTC | 以墙钟判断 Token 有效期，以单调时钟安排重试。吊销和解封只接受服务端对该操作的明确确认，空响应、空结果及未知消息都会中止变更。[火山房间管理返回约定](https://docs.volcengine.com/docs/real-time-communication/server-level-openapi?lang=zh) |
| 本地连接 | 房主能重建两名成员间的链路。收满回调队列时仍按序交付离队与关闭。Wi-Fi Direct 建组回调丢失后重建通道，核对本机角色、可用的群组名及连接状态，仅在确认后释放占用。 |
| 双模与权限 | BLE 信标和扫描过滤器使用同一份服务数据。成员可暂存快照前的 Offer 和云音频释放期间的 ICE。Android 9 重连使用已配对的网络，凭据失效后重新搜索。Android 12 从大致位置升级到精确位置时成对申请权限。 |
| 音频生命周期 | 服务销毁时释放多人房的本机资源。成员异步关闭或初始化回滚中的释放失败会传递到引擎关闭结果，阻止启动下一路音频。 |

自动化验证使用本地 Node 与 Gradle 离线构建。控制面 30/30、Android 主线 881/881、群组预览 250/250，均无跳过。两个变体的 Lint 均为 0 错误，主线有 89 条警告，群组预览有 100 条警告。两个 APK 构建成功，群组真机测试源码编译通过。预览 APK 的 v2 签名和 16 KB 对齐校验通过，主线 APK 中火山 RTC 原生库匹配数为 0。本轮验证范围为本地构建与自动化测试。

只读架构复审结论为 **APPROVED**，P0 和 P1 均为 0，允许进入真机验证。Base 和 Head 同为上述提交，审查范围包含其上的未提交修复。

预览产物为 `output/motocom-group-2.3.0-r5.preview.4-3fabe0bd.apk`（125,523,739 字节），SHA-256 为 `3fabe0bd310cc0427d1479e33d4af034a851711ec01b9172b4a98ead54f7db2e`，同目录含校验文件。

### 11 月 23 日云端部署与小米 13 复验

- 云端更新了 `README.md`、`room-store.mjs` 和 `volc-openapi.mjs`。更新前 3 分钟的房间业务请求和当时的后端连接均为 0。重启后 6 个部署文件的 SHA-256 与本地逐一吻合，`moto-group-cloud.service` 处于 `active`，公网 HTTPS 健康检查返回 200。上一版文件保存在服务器 `/var/backups/moto-group-cloud/before-preview4-jlkl1ode`。
- 小米 13（`2211133C`，API 36）已覆盖安装 `2.3.0-r5.preview.4`（versionCode 9），机内 APK 哈希匹配，手机已保存公网控制面地址。2.4 GHz 预设 Wi-Fi Direct 房主建组、获取 IPv4 地址和释放测试通过，用时 0.818 秒，测试结束后无线 ADB 仍在线。
- 双模房公网实测通过。在本地模式请求 RTC Token 得到 `409 TRANSPORT_NOT_CLOUD`，切云后公共频道、领队频道和公共频道之间的往返成功，切回本地后停止签发 RTC Token。
- 使用两名控制面测试成员上报邻近状态。从报告本地掉线起，第 15.7 秒进入 `PROBING`，第 28.1 秒进入 `PRECONNECTING`。仅房主就绪时仍在云端，两名成员均就绪后切回本地，`epoch` 从 2 变为 3，`handoverFromEpoch = 2`。该测试使用合成 RSSI 和就绪报告，物理距离、静默入网及回切听感仍需双机验证。测试房均已解散。

## 11 月 25 日 preview.5 席位身份上线

小米 13 已从 `preview.4` 覆盖升级到 `2.3.0-r5.preview.5`（版本码 10）。机内 APK 的 SHA-256 与交付件一致，应用冷启动成功。

生产控制面更新了 `room-store.mjs` 和 `README.md`。切换前日志显示 9 个房间全部结束，后端连接数为 0。重启后 6 个部署文件的 SHA-256 与本地一致，公网 `/group-cloud/healthz` 返回 200。两个旧文件的回滚副本已校验，位于服务器 `/var/backups/moto-group-cloud/before-preview5-86m0sakx`。

公网测试房验证了同一席位续签、恢复时保留 RTC 用户 ID，撤销准入后旧会话续签返回 401。同一设备重新获批后取得新 RTC 用户 ID，旧会话仍返回 401，重放旧撤销回执也未影响新席位。测试房已解散，服务日志无错误事件。双机断网回切与实际通话听感仍待真机验证。

## 11 月 25 日 preview.6 审查加固、版本归口与上线（versionCode 12）

- **分支与固定来源**：独立分支 `codex/group-radio-r5`（Base `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`），功能 Source `0df6c1fa80b42a96cdfa7d2e2a7ea27732fc8fce`，预览版本身份升级为 `2.3.0-r5.preview.6+0df6c1f`（`groupPreviewVersionCode = 12`，高于 `betaVersionCode = 11` 及已装机历史 `code 10`）。
- **修复与回归覆盖**：
  - 控制面 `volc-openapi.mjs` 为请求头与响应体统一接入默认 `5000ms` 超时中断与 `64 KiB` 响应上限，防止上游挂起阻塞房间串行锁及全局 `sweep()`；`server.test.mjs` **37/37** 通过。
  - 客户端 `GroupRuntime` 与 `GroupHybridRuntime` 补齐底层音频异步释放失败（`onDisposeFailed`）下的 `keepAlive` 与前台服务收尾，同时保持进程级 `GroupRuntimeOwnership` 安全锁定防止双路音频；`HttpGroupCloudClient` 兼容 API 23 校验 `Content-Length` 并限制响应体大小（成功 `8 MiB`、错误 `64 KiB`）。
  - 主线单测 **105 套件 / 881 项**、群组预览单测 **40 套件 / 264 项** 全部通过（0 失败、0 跳过）；`lintDebug`（0 错误 / 89 警告）与 `lintGroupPreview`（0 错误 / 100 警告）通过；`app-debug.apk` 中火山 / bytenn / ContextCompat 条目数为 **0**。
- **交付产物**：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.6-e9fc919a.apk`（`124,428,433` bytes，SHA-256 `e9fc919acdfb37e2411d6b4ac1ffe43fa68165295d69c7407e9295db8fed00ce`，同目录含 `.sha256`；`zipalign -c -P 16 4` 与 `apksigner verify` v2 通过）。
- **云服务器与小米 13 真机升级**：
  - 服务器 `110.41.77.64` 在零活跃连接窗口备份至 `/var/backups/moto-group-cloud/before-preview6-0df6c1f` 后同步 `/srv/group-cloud` 全部 6 个运行文件，SHA-256 与本地逐一吻合，`moto-group-cloud.service` 重启后处于 `active`；同时为 `/etc/nginx/sites-available/moto.conf` 显式补齐 `default_server`（备份 `moto.conf.bak`），修复无 SNI 纯 IP TLS 请求落入新增域名站点的证书不匹配问题，回归验证 `api.toneride.top`、`admin.toneride.top`、`toneride.top` 与 `https://110.41.77.64/group-cloud/healthz`（`200`）全部正常，公网双模建房、切云与真实火山 OpenAPI `BanRoomUser` 解散冒烟通过，服务已重置为空房间干净状态且日志零密钥泄露。
  - 小米 13（`2211133C`，`100.84.141.14:5555`）已覆盖安装 `2.3.0-r5.preview.6+0df6c1f`（`versionCode = 12`），机内 `base.apk` SHA-256 与交付件一致，正式主包 `com.kuma.motointercom`（`1.2.3+f587472` / `versionCode = 7`）未受影响，`GroupActivity` 冷启动正常（`772ms`），机内严格 TLS 请求 `https://110.41.77.64/group-cloud/healthz` 返回 `200`。

## 11 月 26 日独立群组域名上线

- **入口**：用户确认主域名备案通过后，为群组控制面新增独立子域名 `https://group.toneride.top`。华为云 DNS 记录 `group.toneride.top A 110.41.77.64`（TTL `300`）状态为 `ACTIVE`；未修改小程序使用的 `api.toneride.top`，也未修改 `admin.toneride.top`、`toneride.top`、`www.toneride.top` 及其已有记录。
- **证书**：使用现有 `acme.sh 3.1.7` 通过 HTTP-01 签发 Let's Encrypt EC-256 独立证书（`2026-11-26` 至 `2027-02-24`），安装至 `/etc/ssl/certs/toneride-group.fullchain.pem` 与 `/etc/ssl/private/toneride-group.key`（私钥 `0600 root:root`）；续期由 ARI 窗口驱动，下一次计划续期为 `2027-01-24`，续期后自动 `reload nginx`。
- **反向代理**：新建独立站点 `/etc/nginx/sites-available/group.toneride.top.conf`，仅将 `group.toneride.top` 的 `80/443` 转发至 `127.0.0.1:8443`；未改动 `moto.conf`、`moto-domain.conf` 及其他站点。`nginx -t` 通过，`nginx` 与 `moto-group-cloud.service` 均为 `active`。
- **验收**：`223.5.5.5` 与 `8.8.8.8` 均解析到 `110.41.77.64`；HTTP 请求返回 `301` 至 HTTPS；严格 TLS `GET https://group.toneride.top/healthz` 返回 `200`（`maxMembers: 50`、`openApiRevocationEnforced: true`）；`POST /v1/rooms`（空 body）正确返回 `400 INVALID_INPUT`，证明写请求已到达控制面而非 Nginx 默认 404。回归 `api.toneride.top`、`admin.toneride.top`、`www.toneride.top` 与 IP 入口 `/group-cloud/healthz` 均返回 `200`。
- **客户端配置（preview.6 时点）**：当时群组预览版仍需手动填写 `https://group.toneride.top`，本次只变更 DNS、TLS 与 Nginx；preview.7 起改为内置地址，见下节。

## 11 月 26 日 preview.7 内置云端地址与发布变体

- **目标与构建拓扑**：普通用户不再看到或修改云端地址。保留 `groupPreview` 调试变体（BuildConfig.DEBUG=true）用于 `adb reverse`/临时环境联调；新增 `groupPreviewRelease` 非调试变体，`initWith(release)`、`isDebuggable=false`，复用 `src/groupPreview` 的 Java/Kotlin/Manifest/jniLibs，但使用独立 `src/groupPreviewRelease/res` 的严格网络安全配置。两个变体包名均为 `com.kuma.motointercom.group.preview`，版本同由 `version.properties` 管理。
- **连接策略**：`groupPreviewRelease` 的 BuildConfig 内置 `https://group.toneride.top` 与精确 host allowlist；真实 release 单测断言 BuildConfig.DEBUG=false、恶意 HTTPS 域名和回环 HTTP 均被拒绝。开发者输入仅在 BuildConfig.DEBUG=true 时显示在独立“开发者设置”对话框；普通准备页已移除“云端服务地址”。发布构建初始化时会清除旧 `server_url` 与 `developer_server_url`，旧自定义地址不会继续生效。Service、Runtime 与 HTTP Client 仍会再次执行 `normalizeConfiguredUrl`，不能仅靠绕过 UI 改地址。
- **网络安全**：调试变体仍允许回环明文用于 `adb reverse`；release 变体 APK 内 `group_preview_network_security_config` 仅有 `base-config cleartextTrafficPermitted=false`，禁止全部明文流量。
- **架构门禁**：只读架构复审结论 **APPROVED**（P0 无、P1 无），Base `9436d7e4b6bda9695730f25e8670bd63986b700a`，Head `f9bc538fcf08ea647728d708d3f2c50db2092b34`。
- **自动验证**：主线单测 **105 套件 / 881 项**、调试群组单测 **41 套件 / 270 项**、release 策略单测 **1/1** 全部通过；`lintDebug`（0 错误 / 89 警告）、`lintGroupPreview`（0 错误 / 101 警告）、`lintGroupPreviewRelease`（0 错误 / 100 警告）通过；`assembleGroupPreview`、`assembleGroupPreviewRelease` 与 `assembleGroupPreviewAndroidTest -PtestBuildType=groupPreview` 通过。
- **交付产物**：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.7-10d494ee.apk`（`119,627,901` bytes，SHA-256 `10d494ee6a51b8f8fe673c1764a25345f0a950bb3e7c9b44a1d5cbe0834c4648`，同目录含 `.sha256`）。身份为 `2.3.0-r5.preview.7+f9bc538` / `versionCode = 13`，`apkanalyzer manifest debuggable` 为 `false`；`apksigner verify` v1/v2 通过，`zipalign -c -P 16 4` 通过。
- **小米 13 验证**：已覆盖安装 preview.7 release 包，机内 `base.apk` SHA-256 与交付件一致，包 flags 不含 `DEBUGGABLE`，`GroupActivity` 冷启动 **363ms**；双模准备页无“开发者设置”与“云端服务地址”入口，`AndroidRuntime` 无异常。正式主包 `com.kuma.motointercom`（`1.2.3+f587472` / `versionCode = 7`）未受影响。

## 11 月 26 日 preview.7 正式分发签名切换与重打包

- **签名体系切换**：在 `app/build.gradle.kts` 移除 `groupPreviewRelease` 对 `signingConfigs.getByName("debug")` 的硬编码依赖，改为显式解析 `releaseDistribution` 正式分发签名（优先读取 Gradle 属性/环境变量 `MOTOCOM_RELEASE_STORE_FILE` / `MOTOCOM_RELEASE_STORE_PASSWORD` / `MOTOCOM_RELEASE_KEY_ALIAS` / `MOTOCOM_RELEASE_KEY_PASSWORD`，否则回退 git-ignored `local.properties` 中的 `motocom.release.*`），开启 APK Signature Scheme v1/v2/v3，并对打包任务实施 fail-closed 护栏：禁止使用 `debug.keystore`、禁止配置残缺，无签名配置时 CI 仍可执行 `testGroupPreviewReleaseUnitTest` / `lintGroupPreviewRelease`，但 `assembleGroupPreviewRelease` / `packageGroupPreviewRelease` / `bundleGroupPreviewRelease` 会直接失败阻断。
- **正式分发证书**：本机仓库外新建独立 PKCS12 证书（RSA 4096-bit，`SHA256withRSA`，有效期 10,000 天，未入库），DN 为 `CN=MotoCom Distribution, OU=Mobile, O=ToneRide, L=Shanghai, ST=Shanghai, C=CN`，证书 SHA-256 指纹 `26833c0bb88a8754b7d7ee0431adb2707a1fb50ef730d8087bbba99eb328caca`（SHA-1 `70f73abcd611349b2fefd7157cfb410331c0b752`）。
- **正式签名交付产物**：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.7-f3d0183d.apk`（`119,632,896` bytes，SHA-256 `f3d0183d49c75f1cd6f1ba2e0420b14d1d133210523703f9efd4d71a0b4bf47f`，同目录含 `.sha256`）。包身份保持 `com.kuma.motointercom.group.preview` / `2.3.0-r5.preview.7+f9bc538` / `versionCode = 13`（Android 源码功能基线仍为 `f9bc538`）；`apkanalyzer manifest debuggable` 为 `false`，`zipalign -c -P 16 4` 通过，`apksigner verify` v1/v2/v3 通过且签名主体为上述正式证书；`GroupReleaseCloudPolicyTest` **1/1** 与 `lintGroupPreviewRelease`（0 错误 / 100 警告）通过。
- **覆盖升级与旧包作废口径**：此前使用 `Android Debug` 签名的 `motocom-group-2.3.0-r5.preview.7-10d494ee.apk` 及 `.sha256` 已作废并从 `output/` 清理，不再对外发放。所有曾安装过 `preview.1`–`preview.7`（`Android Debug` 签名）`com.kuma.motointercom.group.preview` 的设备（含小米 13）必须先卸载旧预览包，再安装正式签名包 `motocom-group-2.3.0-r5.preview.7-f3d0183d.apk`；后续同正式证书签发的新版本可直接覆盖升级。本次打包时未连接小米 13 ADB，正式签名包尚未在小米 13 上执行卸载重装实测。

## 11 月 26 日控制面吊销修复线上同步与 R5 Liquid Glass UI 落地

- **云服务器控制面同步（`cc8a375`）**：在零活跃连接与零活跃房间窗口备份旧 `/srv/group-cloud/room-store.mjs` 至 `/var/backups/moto-group-cloud/before-cc8a375/room-store.mjs`，同步新版 `room-store.mjs`（SHA-256 `a2e00095ac931838261a970f97a1a7994b0995074ca519b81f2a03fafd85333c`，`root:groupcloud 0640`，保留 `/srv/group-cloud/.local` 不动），6 个运行文件 SHA-256 与本地仓库逐一一致。重启 `moto-group-cloud.service` 后确认 `active`（`openApiBan: true`），公网严格 TLS `GET https://group.toneride.top/healthz` 返回 `200`（`openApiRevocationEnforced: true`），完整公网 HTTPS 双模冒烟（建房 -> `LOCAL -> CLOUD` -> `CLOUD -> LOCAL` 真实火山 OpenAPI `BanRoomUser` 吊销 -> 解散）通过并重置为空房间干净状态。
- **R5 Liquid Glass UI 落地（功能提交 `f144b6e`，主线 `app/src/main/` 零改动）**：
  - 严格隔离在 `app/src/groupPreview/`、`app/src/groupPreviewCommon/res/` 与 `app/src/testGroupPreview/`：`app/build.gradle.kts` 为 `groupPreview` 与 `groupPreviewRelease` 同时注册公共资源目录 `src/groupPreviewCommon/res`（保留两变体各自独立的网络安全配置），合入头盔主视觉资源 `drawable-nodpi/group_helmet_hero.png`。
  - 新增 `group/ui/` 模块落地全部 8 套液态玻璃界面与状态（Daylight / NightRide 双主题与 API ≤ 34 系统栏配色同步、大厅三模式切换与含真实 `innerTextField()` 且兼容 LTR/RTL 触摸坐标定位的 6 位分段入房码、本地/双模 2×2 席位玻璃网格、云端兜底与私聊夜航态势、50 人网络语音席与成员列表、根部双层全屏遮罩悬浮玻璃底部面板 `GlassBottomSheetOverlay` 承载音频通路/VOX 与按 `(deviceId, incarnation)` 锁定的防误操作成员治理、系统设置含通话中昵称锁定与音频路由/VOX 灵敏度串行持久化及实时下发、日志诊断绑定 `STARTED` 生命周期的 2 秒刷新与覆盖 RFC 7616 `Digest`/`username*`/`HMAC-SHA256` 的展示/复制/导出三路统一脱敏）。
  - 回归验证全部通过：`:app:testGroupPreviewUnitTest` **42 套件 / 288 项** 0 失败 0 跳过（含 `GroupGlassUiTest` 18 项与全部既有 270 项）；`:app:testGroupPreviewReleaseUnitTest` **1/1** 通过；`lintGroupPreview` 与 `lintGroupPreviewRelease` 均为 **0 错误 / 99 警告**（本次新增与改动的全部 Kotlin 源文件 **0 错误 / 0 警告 / 0 提示**，`app/build.gradle.kts` 仅保留既有 8 条依赖/targetSdk 基线警告）；`assembleGroupPreview` 与 `assembleGroupPreviewRelease` 通过，release APK 确认 `debuggable=false`、V3 正式分发证书指纹 `26833c0bb88a8754b7d7ee0431adb2707a1fb50ef730d8087bbba99eb328caca`，且 `aapt2 dump resources` 确认 `drawable/group_helmet_hero` 与 `xml/group_preview_network_security_config` 均已打入。
  - **独立只读终审**：经多轮复审收口（脱敏全量线性迭代扫描/4096 截断防泄漏/前缀保留、VOX 冷启动非阻塞水合/建房状态同步/建房前预览即时生效/生产工厂直测）后获得 **`APPROVED`（P0 0 / P1 0 / P2 0）**。
- **11 月 27 日 `preview.8` 正式签名发布包（Source `f144b6e593d4366027ae030e3e1fadc2273c6fdc`）**：
  - **交付产物**：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.8-822925b3.apk`（`119,835,738` bytes，SHA-256 `822925b3e7bcff3b7023d8945d53735e1ec4d94cf7f5726322c3b4678e56064a`，同目录含 `.sha256`）。
  - **包身份与核验**：`com.kuma.motointercom.group.preview` / `2.3.0-r5.preview.8+f144b6e` / `versionCode = 14`；`apkanalyzer manifest debuggable` 为 `false`，`zipalign -c -P 16 4` 通过，`apksigner verify` v1/v2/v3 通过且 V3 证书主体为 `CN=MotoCom Distribution, OU=Mobile, O=ToneRide, L=Shanghai, ST=Shanghai, C=CN`（SHA-256 `26833c0bb88a8754b7d7ee0431adb2707a1fb50ef730d8087bbba99eb328caca`）；`aapt2` 确认 `drawable/group_helmet_hero` 与 `xml/group_preview_network_security_config`（`cleartextTrafficPermitted=false`）均已打入；`GroupReleaseCloudPolicyTest` **1/1** 与 `lintGroupPreviewRelease`（0 错误 / 99 警告）通过。
  - **升级口径**：沿用正式分发证书，已安装正式签名版 `preview.7`（`f3d0183d`）的设备可直接覆盖升级；仍安装旧调试签名预览包的设备需先卸载后安装。本次打包未连接物理机 ADB，真机实测仍列于下方门禁。
- **11 月 27 日 R5 v2 统一布局重构与 `preview.9` 正式签名发布包（Source `150b3e28c3a73fd4cb021ed279c693bc44ff3061`，主线 `app/src/main/` 零改动）**：
  - **功能改动（`150b3e2`）**：按方案 A（单层 `verticalScroll` + `BoxWithConstraints` + `heightIn(min = viewportHeight)` + `Arrangement.SpaceBetween`）实现主界面底栏贴底；移除大厅冗余“收起对讲”按钮、保留无边框透明头盔主视觉并补齐底部音频快捷栏；三模式统一为顶部可折叠 3+3 `RoomCodeHeroCard`、`正在发言` + `UnifiedMemberRow` 列表与升级底栏 Dock；修复双模 `CLOUD` VOX 误判、窄屏/大字号 Dock 与成员行挤压，并通过嵌套 `AdaptiveTrailingRow`、`FitRoomCodeDigits` / `resolveFittedRoomCodeLayout` 及无中点 `formatStackedThreePlusThree`（`618\n390`）覆盖 `374dp`、`404dp@1.15x`、`220dp@100%..200%` 与 `46dp`/`24dp` 极端窄屏；同步更新 R2/R4 仪器冒烟测试。
  - **独立只读终审与回归**：经 7 轮独立只读复核收口获得 **`APPROVED`（P0 0 / P1 0 / P2 0）**；`:app:testGroupPreviewUnitTest` **42 套件 / 294 项** 0 失败 0 跳过，`-PtestBuildType=groupPreview :app:compileGroupPreviewAndroidTestKotlin` 通过，`:app:testGroupPreviewReleaseUnitTest` **1/1** 通过，`lintGroupPreviewRelease` **0 错误 / 99 警告**。
  - **交付产物**：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.9-f58987db.apk`（`119,868,510` bytes，SHA-256 `f58987dbc446ed32414e0f3d017199d831a3e302e0aa7fb794a0209b91975fcb`，同目录含 `.sha256`）。
  - **包身份与核验**：`com.kuma.motointercom.group.preview` / `2.3.0-r5.preview.9+150b3e2` / `versionCode = 15`；`aapt2 dump badging` 无 `application-debuggable`（`debuggable=false`），`zipalign -c -P 16 4` 通过，`apksigner verify` v1/v2/v3 通过且 V3 证书主体为 `CN=MotoCom Distribution, OU=Mobile, O=ToneRide, L=Shanghai, ST=Shanghai, C=CN`（SHA-256 `26833c0bb88a8754b7d7ee0431adb2707a1fb50ef730d8087bbba99eb328caca`）；`aapt2 dump resources` 确认 `drawable/group_helmet_hero` 与 `xml/group_preview_network_security_config` 均已打入。
  - **无线调试实机覆盖安装**：已通过无线 ADB（`100.84.141.14:5555`，Xiaomi 13 `2211133C`）`adb install -r` 直接覆盖升级成功，`dumpsys package` 确认 `versionCode=15`、`versionName=2.3.0-r5.preview.9+150b3e2`。
- **11 月 27 日激活码权益、人·分钟三层熔断与 `preview.10` 正式签名发布包（Source `540821a33dca3d451356227d31775d80f39c78bd`，主线 `app/src/main/` 零改动）**：
  - **功能改动（`540821a`）**：服务端新增 `entitlement-store.mjs`（Node v22.22.1 内置 `node:sqlite` WAL 账本，HMAC-SHA256 哈希落盘、Crockford Base32 + 2 位校验码、设备绑定、7 天限 2 次换绑、叠码与幂等键绑定请求体哈希）、`room-store.mjs` 与 `server.mjs` 落地“仅房主建房（`NETWORK` 与 `HYBRID`）需激活码、队员凭 6 位邀请码免码加入（可热切 `requireCodeForJoin`）”、`HYBRID` 本地阶段零扣费、仅云端在线 `>= 2` 人按整数人·秒计费、动态缩短 RTC Token TTL、8,500 人·分钟自然月全局熔断、`BanRoomUser` 即时断流与 `/admin/v1/*` 后台管理接口及 `codes-cli.mjs`；Android 端新增 `GroupEntitlementStore`（`AndroidKeyStore` AES-256-GCM 加密存凭证）、大厅建房权益芯片、设置页激活卡与兑换弹窗，并将日志脱敏扩展至 `X-Moto-Entitlement`、`MOTO-...` 与 `mte_...`。
  - **架构门禁与自动回归**：3 轮独立只读 `motointercom-product-architect` 复审收口获得 **`APPROVED`（P0 0 / P1 0）**；Node `npm test` **58/58** 通过，Android `:app:testGroupPreviewUnitTest` **44 套件 / 299 项** 0 失败 0 跳过，`:app:testGroupPreviewReleaseUnitTest` **1/1** 通过，`:app:lintGroupPreviewRelease` **0 错误**。
  - **线上部署与实网冒烟（`group.toneride.top` / `110.41.77.64`）**：已备份旧版控制面、systemd 与 Nginx 配置至 `/var/backups/moto-group-cloud/before-entitlement-20261127`；8 个线上运行文件 SHA-256 与本地仓库逐一一致；systemd 新增 `StateDirectory=moto-group-cloud`（SQLite WAL 落盘 `/var/lib/moto-group-cloud/entitlements.db`），Nginx 增加 `/admin/`（`2r/s`）与 `/v1/entitlements/redeem`（`1r/s`）边缘限流，`.local/config.json` 开启 `entitlementEnforced: true`、`trustLoopbackProxy: true`、`globalMonthlyLimitMinutes: 8500` 与 64-hex `codeHmacSecret`/`adminToken`。重启后 `https://group.toneride.top/healthz` 返回 `200`（`entitlementEnabled: true, adminApiEnabled: true`），公网 HTTPS 实网冒烟（无码建房 401、缺设备头 400、跨设备 403、兑码建 `HYBRID` 房、队员免码加入、结束并清理冒烟权益）全通，并签发首批 10 张微信群测试码（批次 `wechat-beta-1`，300 人·分钟 / 限 6 人 / 14 天）。
  - **交付产物与核验**：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.3.0-r5.preview.10-89983aa9.apk`（`119,901,277` bytes，SHA-256 `89983aa9df7bfc6ad88398230bbda5782aa1771c8b4fff96f13f65a5d294f44d`，同目录含 `.sha256` 与 `wechat-beta-1-codes.txt`）；包身份 `com.kuma.motointercom.group.preview` / `2.3.0-r5.preview.10+540821a` / `versionCode = 16`；`aapt2 dump badging` 无 `application-debuggable`（`debuggable=false`），`zipalign -c -P 16 4` 通过，`apksigner verify` v1/v2/v3 通过且 V3 证书为 `CN=MotoCom Distribution, OU=Mobile, O=ToneRide, L=Shanghai, ST=Shanghai, C=CN`（SHA-256 `26833c0bb88a8754b7d7ee0431adb2707a1fb50ef730d8087bbba99eb328caca`）。本次打包未连接物理机 ADB，实机覆盖安装与兑换流仍待物理设备验收。

## 仍待完成的物理设备门禁（NOT RUN）

0. **R5 路线 A（Fast Connection API）双机/多机真机 0 弹窗矩阵**。小米 13 的单机 2.4 GHz 预设 GO 建组与释放在 `preview.3` 和 `preview.4` 均已通过。仍需第二台 Android 10+ 真机验证客户端 `GroupWifiP2pClient` 在亮屏、后台及息屏时静默加入和重连，以及多端并发加入、50 米邻近探测和后台预连后的语音回切。
1. **R5 四机本地与频道隔离**：至少 4 台真机验证 `HYBRID` 初始 `LOCAL` 模式六对互听，以及公共 / 领队 / 私聊三频道语音隔离与 `riderIncludesFormation` 切换。
2. **R5 整组切云与切回无双路音频**：物理断开本地 GO 或成员 Wi-Fi Direct 验证整组切至 veRTC 云端兜底、全员邻近恢复后整组切回本地、切换全程无重复/双路音频，以及连续故障下的 15→30→60 秒退避与 60 秒稳定窗口清零。
3. **真实火山 OpenAPI 吊销与恢复**：在对接真实火山引擎 RTC 与 OpenAPI AK/SK 环境下，验证移除/离房/频道切换/断线进 `RESERVED`/恢复超时后旧 `localCode` 与旧 RTC Token 立即失效，以及 60 秒内 `/resume` 经 `UpdateBanRoomUserRule` 正常解封重入。
4. **R5 治理与系统并发**：群主转让与本地宿主重分配、后台保活/锁屏、系统来电音频焦点恢复、蓝牙头盔 SCO 与 Wi-Fi Direct / BLE 邻近探活共存。
5. **主线与历史阶段真机回归**。仍需主线 1:1、R1 四机两小时、R2 全流程和 R4 多端音频选路等真机验证。小米 13 已完成 R4 跨 Token 到期点的自动续签，详见 [R4 验证记录](2026-11-22-group-radio-r4.md#小米-13-跨-token-到期点实测)。
