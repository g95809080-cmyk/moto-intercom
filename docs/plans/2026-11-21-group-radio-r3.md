# 群组电台 R3 实施记录

> 历史记录：R3 的 4 人全互联 WebRTC、coturn 与 `/signals` 中继方案已被 [R4](2026-11-22-group-radio-r4.md) 的火山引擎 RTC 50 人方案取代。本文仅保留实施与审查历史，不再作为当前技术路线。

依据：[群组 PRD](../product/2026-11-17-motocom-group-radio-prd.md)（`FR-17`、`FR-21`、`FR-22` 及 `FR-19`/`FR-20`/`9.3` 安全与通知约束）。本轮在 R1/R2 `groupPreview` 预览变体之上完成 R3 工程实现（网络 4 人单公共房间 + 邀请/审批 + 群主转让/解散 + WebRTC/TURN）、安全加固与自动/单机真机烟测；不改变主线一对一产品行为，不实现 R4 50 人 SFU 与 R5 双模切换。

## 实现边界

- 主工作区基线：`main` 的 `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`（未提交工作树，不新建分支、不提交、不发布）。
- 构建变体与版本：独立 `groupPreview` 变体，包名 `com.kuma.motointercom.group.preview`，版本更新为 `2.1.0-r3.preview.1`（`groupPreviewVersionCode = 3`）；既有 `version` 与 `betaVersion` 保持不变。
- 主线最小向后兼容挂钩：`app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt` 仅增加向后兼容的 `RiderIceConfiguration` 数据类与默认空 ICE 参数 `iceConfiguration: () -> RiderIceConfiguration = { RiderIceConfiguration() }`，不改变 1:1 默认 `RiderMediaMode.SINGLE`、空 ICE、本地音轨和现有会话生命周期。
- 网络 v1 范围：单公共云端房间（最多 4 席、最多 6 条全互联 WebRTC 链路），UI 与协议不暴露领队频道或 1:1 私聊控制；业务群主（`ownerId`）与本地网络宿主（`hostDeviceId`）显式分离（纯网络模式 `hostDeviceId = null`）。
- 不引入 GME、高德 Key 或第三方外部依赖；云端控制面采用零 npm 依赖的 Node.js（`>=22`）ESM 实现，Android 端采用内置 `HttpURLConnection`/`HttpsURLConnection` 与 `org.json`。

## 模块与能力落地

| 模块 | 落地内容 | 对应需求 |
|---|---|---|
| `tools/group-cloud/` (`room-store.mjs`、`server.mjs`、`dev-config.mjs`、`server.test.mjs`、`README.md`) | 权威 4 席单公共房间状态机；8 位无歧义大写邀请码（可刷新/过期）与 `PENDING -> ADMITTED / REJECTED / EXPIRED / ENDED / CANCELLED` 审批队列；4 人上限在提交与审批时双重原子校验；禁止邀请码加入接口接管现有 `ADMITTED`/`RESERVED` 成员（恢复强制持原 bearer token 走 `/resume`）；所有 8 个群主管理接口强制校验正整数 `expectedRevision` CAS（缺失 `400`、冲突 `409`）；非单人房间群主直接离开拒绝（`409 OWNER_TRANSFER_REQUIRED`），支持原子转让或转让并离开及不可逆解散；60 秒 `RESERVED` 席位保留（普通成员超时释席，群主超时明确解散 `OWNER_RECOVERY_EXPIRED` 不静默选举）；服务端盖章 `from` 与方向/单调序列校验的多 peer 信令中继与 256 事件窗口自动 `resync`；HMAC-SHA1 短期 `coturn` TURN 凭证签发（永不下发共享密钥）；构造与 `listen()` 双层拦截非回环地址的无 TLS 明文监听 | FR-17, FR-21, FR-22, 9.3 |
| `app/src/main/.../RiderAudioEngine.kt` | 新增内部 `RiderIceConfiguration`（含 `iceServers`、`iceTransportsType`）及构造函数默认参数，供 R3 注入 TURN/RELAY 配置，默认行为与主线 1:1 完全一致 | FR-21 |
| `app/src/groupPreview/.../group/GroupRoom.kt`、`GroupControl.kt`、`GroupSessionOrchestrator.kt`、`GroupDiagnostics.kt`、`GroupAudio.kt` | 新增 `GroupMode { LOCAL, NETWORK }`、`GroupJoinRequest`、`GroupRoomView.ownerId`/`hostDeviceId` 分离与网络模式禁入领队频道；扩展 `GroupSessionSnapshot`（邀请码、审批开关、待审批队列、TURN 状态、强制中继开关）与 R3 `GroupSessionEvent`；新增脱敏云端诊断事件；向 `GroupAudio` 透传 `iceConfiguration` | FR-17, FR-20, FR-21, FR-22 |
| `app/src/groupPreview/.../group/cloud/` (`GroupCloudModels.kt`、`GroupCloudApi.kt`、`HttpGroupCloudClient.kt`、`GroupCloudSessionOrchestrator.kt`、`GroupCloudRuntime.kt`、`GroupCloudPreferences.kt`) | `GroupCloudUrlPolicy`（非回环强制 HTTPS）、`CloudTurnBundle`（`<5s` 过期与强制中继无 TURN 时 fail-closed、脱敏 `toString`）、`GroupCloudRoomView`（Public-only、`hostDeviceId = null`、最多 4 席校验）；独立单写者 `GroupCloudSessionOrchestrator`（延续 R2 三层 `CloseMedia` fail-closed 闭锁、`UpdateTurnBundle` 严格早于 `OpenMedia`、CAS `expectedRevision`、60 秒 `RECONNECTING` 恢复、`<60s` 提前刷新 TURN）；`GroupCloudRuntime` 复用 `GroupRuntimeOwnership` 与 `GroupAudio`，分离长轮询/动作客户端确保 `Stop` 立即打断长轮询并有界（2.5 秒）冲洗终态请求；`GroupCloudPreferences` 仅存非敏感服务地址与强制中继开关 | FR-17, FR-21, FR-22, 9.3 |
| `GroupAccess.kt`、`GroupIntercomService.kt`、`GroupActivity.kt`、`AndroidManifest.xml`、`group_preview_network_security_config.xml` | 网络模式独立权限检查（仅需 `RECORD_AUDIO`，不强求 Wi‑Fi Direct/BLE 权限）；`ACTION_START_NETWORK_GROUP` 前台服务路由与零邀请码泄露的网络通知（含拒绝加入终态通知 `2057`）；准备页本地/网络模式切换、服务地址、审批开关、强制 TURN 开关与 8 位邀请码输入；网络房间页公共单频道、邀请码复制/刷新、待审批同意/拒绝卡片、转让群主/移出成员、强制“群主离开方式”二选一弹窗；全局 `cleartextTrafficPermitted="false"` 仅放行回环调试地址 | FR-17, FR-19, FR-21, FR-22, 9.3, 9.5 |

## 状态与验收边界

- 工程侧自动检查（主线与预览单元测试、Node 控制面安全与并发测试、双变体 Lint、构建与签名核验）、`motointercom-product-architect` 只读架构复审（经首轮 `REQUEST CHANGES` 修复 1 个 P0 与 2 个 P1 后复审 `APPROVED`），以及小米 13（`efcb9031`）单机网络模式烟测、本地 R2 回归与原生媒体回归均已通过，详见 [R3 验证记录与预览包](../verification/2026-11-21-group-radio-r3.md)。
- R3 退出条件中的 4 台物理真机跨网络全互听与真实公网 `coturn` TURN 中继验收尚未执行，连同 R1/R2 剩余多机真机项，当前不视为产品验收完成或可正式发布。
