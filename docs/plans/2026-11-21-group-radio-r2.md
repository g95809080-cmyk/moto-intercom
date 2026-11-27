# 群组电台 R2 实施记录

依据：[群组 PRD](../product/2026-11-17-motocom-group-radio-prd.md)（FR-13 至 FR-20）。本轮在 R1 `groupPreview` 预览变体之上完成 R2 工程实现与自动验收，不修改双人发布基线，不实现 R3 网络、R4 大群 SFU 与 R5 双模。

## 实现边界

- 主工作区基线：`main` 的 `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`（未提交工作树，不新建分支、不提交、不发布）。
- 构建变体与版本：独立 `groupPreview` 变体，包名 `com.kuma.motointercom.group.preview`，版本更新为 `2.1.0-r2.preview.1`（`groupPreviewVersionCode = 2`）；既有 `version` 与 `betaVersion` 保持不变。
- 业务状态边界：所有 R2 领域、协议、诊断、服务通知和界面变更均落在 `app/src/groupPreview` 与 `app/src/testGroupPreview`，未在 R2 阶段修改 `app/src/main/**`。
- 协议版本升级：`GroupWire` 与 `GroupControlCodec` 统一升级至 v2，与 R1 形成 fail-closed 边界。

## 模块与能力落地

| 模块 | 落地内容 | 对应需求 |
|---|---|---|
| `GroupRoom.kt` | 新增 `GroupRole`、`GroupChannel`（`Public` / `Rider` / `Private`）、`GroupPrivateInvite`（30 秒超时）、`riderIncludesFormation` 与 `channelRevision`；群主恒为 `OWNER`，`HEAD` / `TAIL` 全队唯一；`activePairs` 与 `links` 严格限定为同频道 `ADMITTED` 成员；60 秒 `RESERVED` 窗口保留角色与频道并在恢复时校验权限，移除/退出/超时/新代次加入自动关闭私聊并让对方回公共频道 | FR-13, FR-14, FR-15, FR-16 |
| `GroupWire.kt` / `GroupControl.kt` / `GroupHostIngress.kt` | 升级 v2 帧格式，新增类型 `11..16`（`SwitchChannel` 仅允许 `Public`/`Rider`，以及私聊请求/接受/拒绝/取消/结束）与控制通知 `GroupControl.Notice`；`GroupRoster` 严格校验唯一群主、唯一头尾车、频道权限、私聊两人成对、同频道链路与单调时钟重算的邀请期限；Host/Client Ingress 拒绝跨频道媒体信令 | FR-14, FR-15, FR-16 |
| `GroupSessionOrchestrator.kt` / `GroupMediaController.kt` / `GroupAudio.kt` | 频道级 `voiceReady` 与媒体同步；切房按 Break-Before-Make 先 `CloseMedia` 后 `OpenMedia`；客户端按 `channelRevision` 持有 5 秒 `PendingChannelSwitch` 切房锁禁止并发二次切换；`CloseMedia` 失败时三层联合 fail-closed（`GroupMediaController` 锁存 `closeFailed` 并保留未关闭会话供 teardown 重试，`GroupAudio` 立即 `suspendAudio()`，`GroupSessionOrchestrator` 跳过后续 `OpenMedia`/`MediaSignal` 并终止会话） | FR-16, 10.2 零双路音频护栏 |
| `GroupDiagnostics.kt` / `GroupRuntime.kt` | 新增脱敏生命周期诊断（`MotoComGroup`）：`group_create`、`group_join`、`group_voice_ready`、`group_channel_switch`、`group_private_call`、`group_role_changed`、`group_recovery`、`group_end`，不接收也不输出六位房间码与凭证 | FR-12, FR-20 |
| `GroupIntercomService.kt` | 前台通知按当前频道与恢复态动态更新标题（`公共频道` / `领队频道` / `私聊（<对方昵称>）` / `恢复中`），使用 `VISIBILITY_PUBLIC` 且不含房间码；房主结束、被移除、60 秒重连超时发送非驻留自动清除终态通知（`TERMINAL_NOTIFICATION_ID = 2057`），再次建组/加入时自动清除 | FR-19 |
| `GroupActivity.kt` / `GroupAccess.kt` / `AndroidManifest.xml` | 重构 R2 房间页信息架构：频道概览与切换器、群主专属 `领队频道包含头车 / 尾车` 开关、私聊来电/呼出/通话中横幅、按房主优先稳定排序的成员卡片、群主角色弹窗、频道级未就绪配对提示、本地模式解散确认说明；全部交互控件满足 `48dp+` 触控目标与非纯颜色语义 | FR-13..FR-18, 9.5 |

## 状态与验收边界

- 工程侧自动检查（单元测试、Lint、构建、签名核验）与 `motointercom-product-architect` 只读架构复审均已通过，结果详见 [R2 验证记录与预览包](../verification/2026-11-21-group-radio-r2.md)。
- R1 四台真机六对互听/蓝牙共存/两小时稳定，以及 R2 多设备真机切房、私聊、移除、恢复、锁屏通知与无串台实测尚未执行，当前不视为产品验收完成或可正式发布。
