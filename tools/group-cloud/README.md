# 摩声 R4 网络群组控制面（`tools/group-cloud`）

零 npm 依赖的 Node.js（`>=22`）权威控制面。它负责房间生命周期、6 位数字邀请码、审批队列、`expectedRevision` CAS、群主转让/解散和 60 秒席位恢复；音视频媒体直接走火山引擎 RTC（veRTC），不再提供 TURN 凭证、SDP/ICE 编解码或 `/signals` 中继。

群组预览版 `2.3.0-r5.preview.6` 与本目录控制面配套使用。每次准入签发独立的 `rtcUserId`，同一席位续签或恢复时保留该身份。客户端用名单中的 `rtcUserId` 对应骑手，只播放当前频道内已准入且未屏蔽席位的音频。申请加入时可携带随机的 `joinSecret`，用于在回包丢失后撤销同一次申请。通过申请回执准入的成员首次持房间会话凭证访问控制面后，才可接任群主。

## 快速上手

```bash
cd tools/group-cloud
npm test
npm run dev-config
node server.mjs --config=.local/config.json
```

`dev-config` 只在 `.local/config.json` 写入空配置，不会覆盖已有目录。`.local/` 已由本目录 `.gitignore` 严格忽略。

真机 USB 本地联调可把手机回环端口转发到开发机：

```bash
adb reverse tcp:8443 tcp:8443
```

手机网络模式填 `http://127.0.0.1:8443`。服务端默认只允许在回环地址启动无 TLS 明文 HTTP；监听非回环地址必须同时提供 TLS 1.2+ 证书与私钥。生产环境建议由可信反向代理终结 TLS，本服务绑定 `127.0.0.1`。

## 服务端配置

必填：

- `volcAppId` / `volcAppKey`：火山引擎 RTC 控制台中的大群 AppID 与 AppKey。AppKey 只留在服务端，绝不下发客户端。

可选：

- `volcOpenApiAccessKey` / `volcOpenApiSecretKey`：**必填**火山 OpenAPI AK/SK。服务端启动时强制校验，缺失则拒绝启动。成员移除、离开、房间解散、双模频道/私聊切换及 `CLOUD -> LOCAL` 切回时都会同步等待 `BanRoomUser` 立即吊销旧 RTC Token；成员经授权切回原频道或解除限制重新准入时再通过 `UpdateBanRoomUserRule` 解封。OpenAPI 调用失败时按 fail-closed 中止状态变更，绝不静默跳过。
- `volcOpenApiRegion`：默认 `cn-north-1`。
- `volcOpenApiTimeoutMs`：火山 OpenAPI 单次请求（含响应体读取）超时毫秒数，默认 `5000`。
- `banRemovedForbiddenSeconds`：移除成员的禁入时长，默认 300 秒。
- `banRoomForbiddenSeconds`：解散房间的禁入时长，默认 3600 秒。
- `maxMembers`：默认且最大 50，可配置为 2–50。

也可用对应环境变量覆盖：

```text
GROUP_CLOUD_HOST
GROUP_CLOUD_PORT
GROUP_CLOUD_MAX_MEMBERS
GROUP_CLOUD_VOLC_APP_ID
GROUP_CLOUD_VOLC_APP_KEY
GROUP_CLOUD_VOLC_OPENAPI_AK
GROUP_CLOUD_VOLC_OPENAPI_SK
GROUP_CLOUD_VOLC_OPENAPI_REGION
GROUP_CLOUD_VOLC_OPENAPI_TIMEOUT_MS
GROUP_CLOUD_BAN_REMOVED_SECONDS
GROUP_CLOUD_BAN_ROOM_SECONDS
GROUP_CLOUD_ENTITLEMENT_ENFORCED
GROUP_CLOUD_DB_PATH
GROUP_CLOUD_CODE_HMAC_SECRET
GROUP_CLOUD_ADMIN_TOKEN
GROUP_CLOUD_GLOBAL_MONTHLY_LIMIT_MINUTES
GROUP_CLOUD_REQUIRE_CODE_FOR_JOIN
```

## 激活码权益与后台管理（SQLite WAL + `/admin/v1/*`）

- 服务启动默认开启激活码权益拦截（`entitlementEnforced = true`），强制要求配置 `codeHmacSecret`（至少 16 位）；生产环境由 systemd 通过 `StateDirectory=moto-group-cloud` 将数据库落在 `/var/lib/moto-group-cloud/entitlements.db`。
- 建房（`POST /v1/rooms`，涵盖 `NETWORK` 与 `HYBRID`）要求房主携带 `X-Moto-Entitlement`，查询权益（`GET /v1/entitlements/me`）强制校验 `X-Moto-Entitlement` 与 `X-Moto-Device-Id` 设备绑定关系；队员输入 6 位邀请码即可免码加入（可通过 `requireCodeForJoin` 动态收紧为全员激活）。
- `HYBRID` 模式在 `LOCAL` 阶段零扣费；`NETWORK` 与 `HYBRID/CLOUD` 仅在同逻辑频道在线 `>= 2` 人时按整数人·秒扣减，并按剩余额度动态收紧 RTC Token 有效期；当单码余额归零、单次连续云端通话达上限或当月全局红线（默认 8,500 人·分钟，按 `Asia/Shanghai` 自然月重置）触发时，服务端广播终结事件并同步调用火山 OpenAPI `BanRoomUser` 立即断流。
- 后台 HTTP 管理接口需在配置中设置高熵 `adminToken`（`Authorization: Bearer <adminToken>`）方可启用，提供 `/admin/v1/overview`、`/admin/v1/codes/batch`、`/admin/v1/codes`、`/admin/v1/codes/:codeId/revoke`、`/admin/v1/entitlements`、`/admin/v1/entitlements/:id/(topup|reset-rebind|revoke)`、`/admin/v1/rooms/active`、`/admin/v1/rooms/:roomId/force-end`、`PUT /admin/v1/config/global-quota` 与 `/admin/v1/audit-logs`，并配套命令行客户端 `node codes-cli.mjs`。
- 可视化 Web 管理后台托管于 `/admin`（静态资源白名单 `/admin/assets/admin.css`、`/admin/assets/admin.js`，启用严格 CSP 与 `X-Frame-Options: DENY`，未配置 `adminToken` 时 fail-closed 返回 `404`），支持自然月配额水位与 14 天用量趋势图、批量发码与微信文案一键复制导出、激活码筛选吊销、设备权益充值续期/重置换绑/停用、实时活跃房间强制解散、全局熔断开关及审计日志检索。

## 火山引擎 RTC 部署要求

1. 请在 **火山引擎「实时音视频 RTC」控制台**（`https://console.volcengine.com/rtc/listRTC`）开通服务并创建应用，获取 `AppID` 与 `AppKey`（每个 RTC 应用自带每月 10,000 分钟免费额度）。**切勿误用「WTN传输网络」控制台下的应用**，WTN 应用在 RTC `joinRoom` 时会返回 `404 AppID Illegal`（SDK `-1001`），且没有房间模式配置。
2. 大群使用的 **RTC AppID 必须在「实时音视频 RTC → 功能配置 → 房间模式」**（`https://console.volcengine.com/rtc/cloudRTC`）**开启「音频选路」**。默认房间虽能容纳 50 个可见用户，但发布流与单用户订阅流都只有 30 路上限，超限会返回 `-1080` / `-1070`；音频选路会把可见用户与发布上限扩展到 1,000 路，并由服务端托管活跃发言者下行。
3. 客户端使用正式 AccessToken v001。服务端通过 `POST /v1/rooms/:roomId/rtc-token` 签发或续签 `{ appId, rtcRoomId, rtcUserId, rtcToken, expiresAtMs }`；Token 只随鉴权 bearer 会话下发，不写入日志、通知或邀请码。
4. 客户端监听 `onTokenWillExpire`，并在到期前 30 秒调用续签接口后执行 `updateToken`。
5. 计费按房内每个用户的停留时长累计，即使独自一人或无人说话也计费。Android 端必须懒进房：只有已入群（`ADMITTED`）且房内在线成员数 `>= 2` 才加入 RTC；待审批、单人等待、成员回落到 1 人或离房时立即退出。50 人满员每分钟消耗 50 分钟免费额度。
6. 成员本地静音在 Android SDK V3.60 中按流 ID 调用 `subscribeStreamAudio(streamId, false)` 取消对应远端音频订阅（由 `onUserPublishStreamAudio` / `onAudioSubscribeStateChanged` / `onRemoteAudioPropertiesReport` 映射 `userId -> streamId`），不改变其他成员的收听关系。

## 核心契约

- **房间**：单公共频道，默认 50 个可见席位（`ADMITTED` + `RESERVED`），同时发布流仍受火山侧音频选路配置约束。
- **身份**：服务端签发短期 `sessionToken`；每次成功鉴权都会滑动续期（Token 本身不变），持续轮询或恢复不会因固定签发时间被强制下线，空闲超过 TTL 仍会失效；邀请码加入接口不接受接管现有或 `RESERVED` 席位，断线恢复必须持原 `sessionToken` 调用 `/resume`。
- **审批**：房主可刷新 6 位随机数字邀请码，邀请码到期自动失效。并发审批受房间容量约束。若获批响应丢失，成员可在领取窗口内用原申请凭据重试，取回同一份会话。会话被吊销后，重领会返回离房或移除结果。
- **群主治理**：非单人房间拒绝群主直接离开（`409 OWNER_TRANSFER_REQUIRED`），必须原子转让、转让并离开或解散；审批、拒绝、刷新邀请码、审批开关、转让、移除、解除限制、解散均强制要求整数 `expectedRevision`。
- **恢复**：非主动离开成员进入 60 秒 `RESERVED`；普通成员超时释放席位，群主超时明确解散，不静默自动选举。
- **回收**：已解散房间保留 5 分钟终止通知，窗口结束后清理房间、消息队列、申请索引及邀请码索引，不永久驻留内存。
- **传输**：客户端媒体只使用 veRTC；`/signals`、`/confirm-link`、`/restart-link` 和 TURN 凭证接口均已移除。
- **R5 双模（`HYBRID`）路线 A 预连与回切**：`HYBRID` 硬上限 4 人，`LOCAL` 阶段严格禁止签发 RTC Token（`409 TRANSPORT_NOT_CLOUD`）。`POST /v1/rooms/:roomId/transport/local-status` 支持 `localRssiDbm`（上报 Wi-Fi RSSI 时默认强制 `>= -75 dBm`；无精细定位权限时显式允许高功率 BLE 降级路径上报 `localReachable = true` 且 `localRssiDbm = null`，仍受退避冷却、12 秒稳定期与 `PRECONNECTING` 约束）、`localControlReady`、`localHostAddress`、`localFrequencyMhz` 与 `preconnectFailed`，按 `IDLE -> PROBING`（12 秒信号稳定期）`-> PRECONNECTING`（全员后台静默建连控制面，云端通话不断）`-> LOCAL`（全员 `localControlReady = true` 后同步 `await BanRoomUser` 并附带 `handoverFromEpoch` 原子切回）推进；预连失败或 20 秒超时静默轮换路线 A 凭据并按 15s→30s→60s 退避，不中断云端 RTC；在 `LOCAL` 阶段移除非房主成员时立即推进 `transportEpoch` 并轮换 `localCode` 与路线 A 凭据（保持 `LOCAL` 且零 RTC 签发）。

## 测试

```bash
npm test
```

测试覆盖 AccessToken v001 二进制布局与 HMAC-SHA256、OpenAPI 签名、50 席原子准入、RTC Token 续签、群主移除/解散 Ban 钩子、60 秒恢复、CAS 严格校验、非回环明文 TLS 拦截、激活码兑换/叠码/限次换绑、人·秒计费与动态 TTL、全局红线熔断以及 `/admin/v1/*` 后台管理契约。
