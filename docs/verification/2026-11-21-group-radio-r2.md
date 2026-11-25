# 群组电台 R2 验证记录

日期：2026-11-21。依据：[群组 PRD](../product/2026-11-17-motocom-group-radio-prd.md)、[R2 实施记录](../plans/2026-11-21-group-radio-r2.md)。

## 范围与基线

- 主工作区：`MotoIntercomApp`（Gradle 通过 `M:/` 映射执行），基线 `main` `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`。
- 交付形态：独立 `groupPreview` 变体，包名 `com.kuma.motointercom.group.preview`，版本 `2.1.0-r2.preview.1`（`versionCode = 2`）。
- 未新建分支、未提交 Git、未更新 Linear、未发布正式包。
- 本轮覆盖 R2（FR-13..FR-20）；R3 网络 4 人、R4 50 人 SFU、R5 双模未实现。

## 架构门禁复审记录

按 `AGENTS.md` 要求执行 `motointercom-product-architect` 只读审查：

1. 首轮审查结论为 `REQUEST CHANGES`（1 项 P0）：`CloseMedia` 抛异常时，`GroupSessionOrchestrator` 忽略异常继续执行后续 `OpenMedia`，且 `GroupMediaController` 已提前移除旧 slot，存在旧、新频道音频并存风险。
2. 根因修复：`GroupMediaController` 锁存 `closeFailed` 拒绝新开会话并保留未关闭会话在 teardown 重试；`GroupAudio` 在关闭失败时保留 lease 跟踪、立即 `suspendAudio()` 并上报不可用；`GroupSessionOrchestrator` 锁存 `mediaCloseBlocked`，跳过同批次及后续 `OpenMedia`/`MediaSignal`，并置顶失败终止事件安全停机（房主同步通知成员 `HOST_ENDED`）。
3. 复审结论：`APPROVED`（P0：无；P1：无；Base/Head SHA：`7041d3c461fe6e4530d94e3cc7d30c72f48c85c6` 未提交工作树；允许进入 R2 多设备真机验收门禁）。

## 自动检查结果

执行命令（`M:/`，JDK 17，`--console=plain --max-workers=2`）：

```powershell
.\gradlew.bat testGroupPreviewUnitTest testDebugUnitTest lintGroupPreview lintDebug assembleGroupPreview assembleDebug --console=plain --max-workers=2
```

| 检查项 | 结果 | 说明 |
|---|---|---|
| `testGroupPreviewUnitTest` | 178 项通过（27 套件），0 失败 / 0 错误 / 0 跳过 | 相比 R1 新增 16 项 R2 与跨层失败顺序回归；与 `testDebugUnitTest` 存在共享用例，不简单相加 |
| `testDebugUnitTest` | 876 项通过（105 套件），0 失败 / 0 错误 / 0 跳过 | 双人主线与共享音频回归全部通过 |
| `lintGroupPreview` | 0 错误、93 警告 | 与 R1 基线持平，无新增警告 |
| `lintDebug` | 0 错误、89 警告 | 与 R1 基线持平 |
| `assembleGroupPreview` / `assembleDebug` | 均成功 | 双变体均可正常打包 |
| `apksigner verify` | 通过（exit 0，v1/v2 调试签名） | 核验预览包签名完整 |
| 小米 13 R2 单机生命周期与通知烟测（`GroupPreviewR2DeviceSmokeTest`） | 1 项通过（10.55s，Android 16 / API 36，`efcb9031`） | 实机验证 R2 准备页、建组、领队切频、阵型开关、动态前台通知、后台回房、解散确认、GO 释放重建与脱敏日志 |
| 小米 13 原生媒体回归（`RiderAudioEngineHotSessionTest`） | 4 项通过（4.31s，Android 16 / API 36，`efcb9031`） | 实机同进程三 PeerConnection 共享平台与 RTP 环回；不代表多机互听 |

## 小米 13 单机真机烟测记录

设备：Xiaomi 13（`2211133C` / `fuxi`，serial `efcb9031`，Android 16 / API 36，USB 调试）。测试目标为已安装的 `com.kuma.motointercom.group.preview`（`2.1.0-r2.preview.1` / `versionCode = 2`），通过隔离测试包 `com.kuma.motointercom.group.preview.instrumentation` 执行，不改动主包与已安装 R2 APK。

原始证据位于 `app/build/verification/r2-phone-20261121/`（`smoke-raw.txt`、`motocom-group-logcat.txt`、`native-media-raw.txt`）。

| 场景 | 实测结果 |
|---|---|
| 准备页与六位码输入校验 | 显示 `本地群组 · R2 预览`；5 位数字加入按钮禁用，6 位启用，清空恢复禁用 |
| 首次 Wi-Fi Direct 建组（738 ms 就绪） | 显示六位房间码、`群主 · 我`、`公共频道 · 频道内 1 人 · 全队 1/4 人`、`当前频道仅你 1 人`；前台通知 `2056` 为 `VISIBILITY_PUBLIC`、`FLAG_ONGOING_EVENT`，标题 `摩声群组 · 公共频道`，不含房间码 |
| 切换领队频道与阵型开关 | 切入领队频道后 UI 显示 `领队频道 · 频道内 1 人 · 全队 1/4 人` 与 `✓ 领队频道（当前）`，前台通知标题同步变为 `摩声群组 · 领队频道`；`领队频道包含头车 / 尾车` 关闭与重开正常，群主留在领队频道；切回公共频道后 UI 与通知均恢复 |
| 收起对讲与后台回房 | 点击 `收起对讲` 后前台通知 `2056` 保持活跃；重新拉起 `GroupActivity` 回到原房间且原六位码不变 |
| 本地解散确认与撤销 | 点 `结束全队对讲` 显示 `本地模式不支持转让群主` 提示；点 `继续对讲` 保留原房间；确认 `结束房间` 后回准备页、旧码消失、前台通知撤销，且 `USER_LEFT` 未误发终态通知 `2057` |
| GO 释放与二次重建（589 ms 就绪） | 结束后可立即二次建组并再次正常结束，最终留在准备页、无前台服务残留 |
| 脱敏诊断与稳定性 | `MotoComGroup` 完整记录 `group_create`、`group_channel_switch`、`group_role_changed`、`group_end`，两次六位码零泄露；`crash` 缓冲与 `lastanr` 均无记录 |
| 原生媒体 4 项回归 | `RiderAudioEngineHotSessionTest` 4/4 通过（同设备同进程环回） |

> 注：小米 13 上 `com.android.systemui` 状态栏/灵动岛每秒触发无障碍子树更新，导致命令行 `uiautomator dump` 无法拿到 1 秒全局空闲态；该现象已通过 `uiautomator events` 与 `lastanr` 确认为系统覆盖层行为而非应用卡顿，本次烟测采用应用内 Compose 语义树与系统 `NotificationManager` 直接验证。

## 新增 R2 关键回归覆盖

| 测试文件 | 覆盖场景 |
|---|---|
| `GroupRoomTest` | 角色权限与唯一头/尾车、关闭 `riderIncludesFormation` 撤离领队频道、禁止直切 `Private`、频道隔离 `activePairs`、私聊 30 秒超时/忙线/60 秒 `RESERVED` 保留与恢复/超时自动回公共/移除清理 |
| `GroupWireTest` | v2 帧 `11..16` 编解码往返、v1 帧拒绝、非法频道字节与非法 UUID 拒绝 |
| `GroupControlTest` | R2 `GroupRoster` 与 `GroupControl.Notice` 编解码、邀请期限单调时钟重算、非法角色/人数/跨频道链路拒绝、`GroupClientIngress` 拒绝跨频道媒体帧 |
| `GroupHostIngressTest` | R2 控制帧仅允许发往 `localHost`、跨频道媒体信令拒绝 |
| `GroupMediaControllerTest` | `GroupMediaLease.matches()` 拒绝跨频道 peer；`session.close()` 失败锁存 `closeFailed`、拒绝新会话并在 teardown 重试关闭 |
| `GroupSessionOrchestratorTest` | 四节点角色/阵型开关/领队切换 Break-Before-Make 与 5 秒客户端切房锁；私聊完整生命周期（拒绝/取消/超时/忙线/接受/恢复/结束/移除）；FR-20 脱敏事件校验且零六位码泄露；`CloseMedia` 失败阻止 `OpenMedia` 并 fail-closed 停机 |
| `GroupRuntimeTest` | 真实 `GroupSessionOrchestrator -> GroupRuntime -> GroupMediaController` 跨层 `CloseMedia` 失败顺序：阻止新频道 `OpenMedia`、触发停机并重试关闭未释放会话 |
| `GroupIntercomServiceTest` | FR-19 动态频道/私聊/恢复态前台通知（`VISIBILITY_PUBLIC`、零房间码泄露）；`HOST_ENDED`/`REMOVED`/`RECOVERY_EXPIRED` 终态通知创建、`USER_LEFT` 不误发及重启自动清除 |
| `GroupScreenComposeTest` | FR-13..FR-18 角色显示与稳定序、领队权限与入口控制、群主阵型开关与角色弹窗、私聊来电/呼出/通话与 `RESERVED` 文案、切房锁禁用、本地解散确认文案 |

## 交付产物

- 产物路径：`C:/Users/Administrator/Documents/摩托对讲机/output/motocom-group-2.1.0-r2.preview.1-acd93da9.apk`
- 文件大小：`61,388,387` bytes
- SHA-256：`acd93da9dc654fe360f3255dcbbcb357924a39f51abaead4e11a634ae07a4487`

## 未验收项（设备门禁待执行）

单台小米 13 已完成 R2 生命周期、领队频道、动态通知、脱敏诊断与同进程原生音频回归，但以下多机/硬件真机门禁尚未运行，需后续补齐真机矩阵实测：

1. R1 待补真机项：4 台真机 6 对互听、无路由器场景、真实蓝牙耳机 SCO 与 Wi-Fi Direct 共存、来电中断恢复与 2 小时稳定性。
2. R2 多设备真机项：多台真机公共/领队/私聊实际人耳听音隔离（无旧频道残音/串台）、切房时延 ≤ 3 秒、私聊建立 ≤ 5 秒、群主改角色与阵型开关实时生效、被移除与房主结束的后台/锁屏终态通知、60 秒断网恢复回原频道。
