# 群组电台 R3 验证记录与预览包

> 历史记录：R3 自建 WebRTC/TURN 方案已被 [R4](2026-11-22-group-radio-r4.md) 的火山引擎 RTC 方案取代，其预览包与 TURN 验收不再作为当前交付目标。

日期：2026-11-21
基线提交：`7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`（主工作区 `MotoIntercomApp` 未提交工作树，仅工程预览，未创建分支、未提交、未发布）
预览包标识：`com.kuma.motointercom.group.preview` / `2.1.0-r3.preview.1`（`versionCode = 3`）

## 结论

- 工程自动门禁全绿：`testDebugUnitTest` 878/878、`testGroupPreviewUnitTest` 193/193（含真实 Kotlin ↔ Node HTTP 4 人 E2E）、`tools/group-cloud` Node 测试 10/10、`lintDebug` 0 错误、`lintGroupPreview` 0 错误、APK 构建与 v1/v2 签名核验通过。
- `motointercom-product-architect` 只读复审最终结论：**`APPROVED`**（`P0: None`、`P1: None`）。首轮复审发现 1 项 P0 与 2 项 P1（邀请码加入可接管现有/`RESERVED` 成员、群主 CAS 可省略、非回环地址允许无 TLS 明文启动），均已在源码根因修复并补齐专项回归测试，复审确认全部闭环。
- 小米 13（`efcb9031`，Android 16 / API 36，USB 调试）已完成 R3 网络模式实机烟测（经 `adb reverse` 对接本机已加固 `tools/group-cloud/server.mjs`）、R2 本地模式回归与 4 项原生 WebRTC 媒体回归，crash 缓冲与 `lastanr` 均无异常，正式主包 `com.kuma.motointercom`（`1.2.3+f587472`）保持未修改。
- **未完成退出门禁**：4 台物理设备跨网络全互听与真实公网 `coturn` TURN 中继实测尚未执行，因此 R3 仅完成工程预览与单机真机烟测，不视为 R3 退出验收通过。

## 只读架构门禁审查（`motointercom-product-architect`）

- 首轮审查：`REQUEST CHANGES`（P0=1，P1=2）：
  1. **P0**：`tools/group-cloud/room-store.mjs` 中 `submitJoinRequest` 对现有 `RESERVED` 或同 `runtimeId` 成员直接恢复并重新 `_issueSession`，存在持邀请码冒充在线成员或接管群主风险。
  2. **P1**：`_assertExpectedRevision` 省略 `expectedRevision` 时放行，未强制服务端 CAS。
  3. **P1**：`tools/group-cloud/server.mjs` 在未配 TLS 时允许绑定非回环地址（如 `0.0.0.0`）明文监听。
- 根因修复与回归：
  - `submitJoinRequest` 对任何现有成员（含 `RESERVED`）统一拒绝 `409 ALREADY_IN_ROOM`，恢复强制持原 bearer `sessionToken` 调用 `/resume`；被替换待审批请求先标记 `CANCELLED` 并唤醒等待者。
  - `_assertExpectedRevision` 强制要求 `>= 1` 的正整数 `expectedRevision`（缺失/类型错返回 `400 INVALID_REQUEST`，版本错返回 `409 REVISION_CONFLICT`），覆盖全部 8 个群主管理端点。
  - `server.mjs` 新增 `isLoopbackHost`，在构造期与非 TLS `listen()` 双层拦截非回环及省略 host 的明文监听，并拒绝半套 TLS 配置。
  - 新增 `server.test.mjs` 第 8–10 项专项回归并归档至 `app/build/verification/r3-20261121/node-server-tests.txt`。
- 最终复审：
  - 结论：`APPROVED`
  - `P0`：None
  - `P1`：None
  - `Non-blocking`：R3 四台设备跨网络互听与真实 TURN 真机验收仍待执行
  - `Base SHA`：`7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`
  - `Head SHA`：`WORKTREE on 7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`（uncommitted，`main`）
  - `Next gate allowed`：R3 四机跨网 + TURN 物理设备验收

## 自动化验证结果

| 检查项 | 命令 / 工件 | 结果 |
|---|---|---|
| 云端控制面单测与安全回归 | `node --test server.test.mjs`（`tools/group-cloud/`，归档 `app/build/verification/r3-20261121/node-server-tests.txt`） | **10/10 通过**（0 失败/跳过） |
| 主线单元测试 | `:app:testDebugUnitTest`（106 XML，含 `RiderIceConfigurationTest`） | **878/878 通过**（0 失败/错误/跳过） |
| 群组预览单元测试 | `:app:testGroupPreviewUnitTest`（32 XML，含 `GroupCloudCodecAndTurnTest`、`GroupCloudSessionOrchestratorTest`、`GroupCloudEndToEndHttpTest`、`GroupR3ScreenAndServiceTest`） | **193/193 通过**（0 失败/错误/跳过） |
| 主线 Lint | `:app:lintDebug` | **0 错误**，89 警告 |
| 群组预览 Lint | `:app:lintGroupPreview` | **0 错误**，94 警告（已修复 `Map.putIfAbsent` API 23 `NewApi` 错误） |
| APK 构建与签名校验 | `:app:assembleGroupPreview`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`apksigner verify`、`aapt2 dump badging` | 全部成功；v1/v2 签名有效，`versionName=2.1.0-r3.preview.1`，`versionCode=3` |

## 小米 13 单机实机烟测与回归（USB `efcb9031`）

通过隔离测试包 `com.kuma.motointercom.group.preview.instrumentation`（不覆盖正式主包与原测试包）实测以下三组用例，原始日志归档于 `app/build/verification/r3-20261121/`：

| 测试项 | 验证内容与实测结果 | 证据文件 |
|---|---|---|
| `GroupPreviewR3DeviceSmokeTest`（1/1，4.75 s） | 仅授 `RECORD_AUDIO` + `POST_NOTIFICATIONS`（无需 Wi‑Fi Direct/BLE）；通过 `adb reverse tcp:8443` 对接本机 `tools/group-cloud/server.mjs`；切换网络群组、创建房间获取 8 位邀请码并确认无领队频道入口；前台通知 `2056` 为 `VISIBILITY_PUBLIC`/`FLAG_ONGOING_EVENT` 且标题 `摩声网络群组 · 公共频道`、零邀请码泄露；远端测试车友（`二号远端车`）提交加入申请后小米 13 长轮询实时弹窗、群主点“同意加入”完成准入且确认非群主不回传邀请码且下发有效 TURN 凭证；刷新邀请码成功变更；点击“收起对讲”后前台服务保活、重开回房保持新邀请码与 `2/4` 成员；群主点击离开触发强制“群主离开方式”弹窗并执行原子“转让并离开”，小米 13 回到准备页且前台通知清除（不误发终态通知 `2057`），远端新群主收到升格快照并正常解散房间；`MotoComGroup` 诊断包含 `group_cloud_create`/`group_cloud_approval`/`group_cloud_owner_transfer`/`group_end` 且零邀请码/token/TURN secret 泄露 | `xiaomi13-r3-smoke.txt` |
| `GroupPreviewR2DeviceSmokeTest`（1/1，8.98 s） | 同 R3 预览包上回归本地 Wi‑Fi Direct 建组、公共/领队频道切换、阵型开关、收起回房、本地解散确认、GO 释放与二次重建、脱敏诊断 | `xiaomi13-r2-local-regression.txt` |
| `RiderAudioEngineHotSessionTest`（4/4，4.41 s） | 同 R3 预览包上回归热会话挂起/恢复、顺序 PC 共享平台、GROUP 3-PC 与替补替换、单对关闭不影响其余对 RTP 音频 | `xiaomi13-native-audio.txt` |
| 稳定性与收尾 | `logcat -d -b crash` 为空；`dumpsys activity lastanr` 无开机后 ANR；`adb reverse` 已清理、本机临时 Node 服务已停止、手机停留在 R3 准备页且无残留前台服务 | — |

## 交付产物

- 产物路径：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.1.0-r3.preview.1-bf491069.apk`
- 文件大小：`61,292,943` bytes
- SHA-256：`bf491069a68a4101a5bc109785d5eb680f58fda3726ff9bc17f9724a0dfc3ec2`

## 仍待完成的物理设备门禁

1. **R3 退出门禁**：4 台物理真机跨网络（不同 Wi‑Fi / 蜂窝 4G/5G NAT 环境）接入带 TLS 1.2+ 证书的云端控制面与真实公网 `coturn` 服务器，实测 6 对全互联互听、强制 `IceTransportsType.RELAY` 音频通路、60 秒断网席位保留与 `/resume` 恢复、群主 60 秒超时解散、语音端到端延迟（P95 ≤ 300 ms）与长时稳定性。
2. **R1 / R2 历史多机项**：4 台真机本地 Wi‑Fi Direct 6 对互听、无路由器、蓝牙耳机 SCO 共存、多机切房/私聊零串台与 2 小时稳定性。
3. **延期范围**：R4（50 人 SFU）与 R5（本地优先双模切换）不在本轮交付范围。
