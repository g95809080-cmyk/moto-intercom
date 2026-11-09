# KUM-94 验证记录

## 代码候选

- Base SHA：`c44c2ba78a253a317e8d4ce7175f2e7dcaf368b6`
- Branch：`feat/kum-94-ui-components`
- Feature Commit：`17b374c4d53d8fa7bd2c16a25f37f0bdd8d38c42`
- Feature Commit Tree：`404700bf0b6d91967d5d0ec4eb57b6680034f331`
- Reviewed Candidate Tree：`df498530ee042fc9cb040277b5b82a541b00bfaf`
- Draft PR：[#68](https://github.com/g95809080-cmyk/moto-intercom/pull/68)
- Worktree：`C:\Users\kuma\.codex\worktrees\kum-94-ui-components\MotoIntercomApp`
- 状态：功能分支已提交并推送到 `origin`；Draft PR 的 base 为 `fix/kum-93-ui-accessibility`，继续保持 Draft。

Reviewed Candidate Tree 包含本轮源码、测试、资源、Rasen 文档和最终截图。架构审查后仅更新 `verification.md` 与 `tasks.md`，源码未再变化。重复维护一套视觉原型且未被正式页面引用的 `UiConceptActivity` 已删除，只保留使用正式 Compose 组件的 `DesignPreviewActivity`。

## 实现结果

- `NIGHTRIDE` 与 `DAYLIGHT` 两套主题已接入四个主页面、底部导航、系统栏和设置页即时切换；首次启动默认白昼，未知持久化值回退白昼。
- Home 使用居中“摩声”字标、整卡深色雷达、头盔中心标记和真实发现车友的圆形气泡。气泡点击按 `deviceId + sessionId` 回查当前发现对象，再走现有连接请求路径。
- Discover、Settings、Logs 统一顶栏、面板、状态胶囊和主题色；Discover 离线状态显示“待机中”，不再显示“对讲中”。
- Settings 的主题入口直接可见；自动重连卡片显示真实短状态；VOX 不再重复显示灵敏度；已连接但音频未就绪时显示“正在等待音频就绪”。
- 文案清理删除了重复的产品说明、旧品牌副标题、Home 的通道/WebRTC 明细、Discover 的身份解释和 Settings 的冗余摘要；权限、Wi-Fi、连接、静音、VOX、日志导出等必要操作文案保留。
- 雷达扫描、声纳波纹和字标脉冲只在活动状态且系统动画开启时运行；关闭动画后保留静态网格、真实状态和原有点击语义。
- 未修改 `IntercomService`、`IntercomStateMachine`、`SessionOrchestrator`、`SignalingProtocol`、transport adapters、Gradle 或数据库。

## 自动化门禁

- 命令：`.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain`
- 结果：`BUILD SUCCESSFUL in 2m 52s`
- 测试：104 suites / 861 tests；failures 0、errors 0、skipped 0。
- Lint：0 errors / 88 warnings / 2 hints。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，59,393,188 bytes。
- `git diff --check`：无空白错误；仅输出 Windows 换行转换提示。

新增或更新的定向覆盖包括：

- 主题默认值、选择持久化、未知值回退和切换入口。
- `productStatusLabel` 对 Offline、Discovering、IncomingConfirmation、Connecting、Optimizing、Connected、Recovering、Resetting、Stopping 的短标签映射。
- Home 雷达使用真实车友气泡，点击时分发同一 `RiderPresence`，并保持不可连接对象不触发连接。
- Discover 的离线、扫描、重新扫描和车友分组展示。
- Settings 的身份编辑、音频路由、VOX、自动重连、主题和更多入口。
- Logs 的复制、导出和关闭操作。

## 模拟器验收

- 设备：`MotoIntercom_Graphics_API36`，API 36，1080x2400，420dpi，字体比例 1.0。
- 白昼截图：`verification/final-home-daylight.png`、`verification/final-discover-daylight.png`、`verification/final-settings-daylight.png`。
- 夜航截图：`verification/final-home-nightride.png`、`verification/final-discover-nightride.png`、`verification/final-settings-nightride.png`、`verification/final-logs-nightride.png`。
- 小屏截图：`verification/final-home-320-font130.png`、`verification/final-discover-320-font130.png`、`verification/final-settings-320-font130.png`。
- 最终夜航设置页已使用本次最终 APK 重拍，确认自动重连状态为“待机中”，VOX 只显示一处灵敏度。
- 早前已在同一候选实现上完成白昼、夜航四页和强制停止重启后的主题保留检查；最终 APK 的主题读取路径未变化。
- 在 320dp 宽、1.3 倍字体下检查 Home、Discover、Settings，可滚动且无关键操作截断。

## 架构审查

```text
APPROVED
P0
- 无
P1
- 无
Non-blocking
- Home 在 Connected 但音频尚未就绪的短窗口内，状态胶囊来自产品状态“已连接”，雷达接触点则等待 audioReady；不会错误发起连接，属于短暂视觉状态差异。
- 雷达气泡使用固定角度和距离做布局分布，不代表真实物理方位；气泡身份和可连接性来自真实发现数据。
- 88 条 Lint warning 为仓库既有范围告警，本次门禁为 0 errors，未扩大到无关告警清理。
- Help、About、首次引导和来电确认仍使用旧系统对话框，属于本轮非目标。
- 本轮未新增 TalkBack 设备验收；KUM-93 的既有无障碍覆盖继续有效。
- 分支仍堆叠在 KUM-93 head 上，KUM-93 合并后需要 rebase 到 main，只保留 KUM-94 的 UI 改动。
Base SHA
- c44c2ba78a253a317e8d4ce7175f2e7dcaf368b6
Head SHA
- df498530ee042fc9cb040277b5b82a541b00bfaf（Reviewed Candidate Tree）
- df49853..17b374c 没有源码变化，只更新任务状态、验证记录和最终截图。
Next gate allowed
- 已提交并推送功能分支、更新 Draft PR #68；未经用户明确授权不合并、不推送 main。
```

审查边界：

- 只读检查了 Git 状态、完整源码差异、真实发现数据到雷达点击的路径、主题持久化、状态映射、系统栏接线和测试覆盖。
- 雷达点击要求当前路由为 Home、产品状态为 Discovering、runtime session 一致、对象仍可连接且没有待处理连接，再调用既有 `connectToPresence` 路径。
- 主题选择先更新页面状态，再写入 `motocom_appearance`，并同步 `MainScreen` 的导航和系统栏外观。
- 没有新的产品状态写入者、后台资源所有者、协议路径或导航入口。

## 提交边界

- 本轮已提交 `17b374c` 并推送 `feat/kum-94-ui-components`，并更新 Draft PR #68。
- 未合并、未发布、未使用签名密钥、未修改生产数据库。
- Linear 状态与评论未在本轮修改。

## P1 无障碍修复复核（2026-11-09）

- 工作区：`HEAD 3d8d4d7b9b4164815fd703ec84161706bc18e612` + 8 个未提交文件。
- Settings：卡片外层保留唯一 `Switch` 语义，内部 `MotoComToggle` 改为非交互并清除语义；VOX 开关补上 `settings_vox_switch_description`。
- 触控目标：保存昵称、音频路由、VOX 开关与滑块、权限 CTA、Discover 操作/连接/管理配对、Logs 返回与按钮、主题选择器均达到至少 48dp 高度。
- 字体缩放：TopBar、PageHeading、SectionLabel、Discover Hero、分组标题、Logs 标题和 MoreGrid 由固定高度改为最小高度。
- 减少动画：Discover 状态点在系统动画关闭时保留扫描状态与强调色，但不再启动无限脉冲；`MotoComStatusPill` 将状态与动画拆成独立参数。
- 未修改 `IntercomService`、`IntercomStateMachine`、`SessionOrchestrator`、`SignalingProtocol`、transport adapters、Gradle 或数据库。

自动化门禁：

- 命令：`.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain`
- 结果：`BUILD SUCCESSFUL in 2m 46s`
- 测试：104 suites / 868 tests；failures 0、errors 0、skipped 0。
- Lint：0 errors / 87 warnings。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk` 构建成功。
- `git diff --check`：无空白错误。

只读审查结论：

```text
APPROVED
P0
- 无
P1
- 无
Non-blocking
- 音频路由三段式选项在 320dp 宽度下受半宽卡片限制，单项宽度不足 48dp；高度已达到 48dp，本轮 P1 按高度验收，后续若继续压缩布局需重新评估横向目标。
- 已配对车友的连接与管理配对按钮改为 48dp 外层目标后，行高增加约 48dp，列表密度略降。
- `MotoComTopBar` 与 `MotoComStatusPill` 的既有 `ModifierParameter` Lint warning 仍在，属于参数顺序提示，不影响本轮门禁。
Base SHA
- c44c2ba78a253a317e8d4ce7175f2e7dcaf368b6
Head SHA
- 3d8d4d7b9b4164815fd703ec84161706bc18e612 + working tree（8 个修改文件）
Next gate allowed
- 可提交并推送本轮修复、更新 Draft PR #68；未经用户明确授权不合并、不发布、不修改 main。
```

审查边界：只读检查了当前工作区差异、开关语义树、48dp 布局约束、2 倍字体测试、减少动画分支、测试结果和 Lint 报告；未重跑构建，未修改审查范围内的源码。

## P2 清理与复核（2026-11-09）

- 工作区：`HEAD 5ed818d` + working tree；分支 `feat/kum-94-ui-components`，Draft PR #68 保持 Draft。
- 雷达气泡改为按真实车友数量和画布半径动态排布，尺寸限制在 48–72dp；雷达环和单气泡都避让 94dp 中心节点，容量不足时显示 `+N`。
- 删除未接入生产页面的 `MotoComSurfaceCard`、`MotoComIconButton`、`MotoComSectionHeader`、`MotoComSettingRow`，以及未挂载的 `RippleView` 和 `discover_radar_ripple`。
- `MotoComTopBar` 只朗读“摩声”；状态由雷达/页面状态胶囊独立朗读。`RadarStatusPill` 和 `RadarOverflowBadge` 使用单一语义，避免视觉文字和描述重复。
- 主题保存默认改用真实 `commit()` 结果；保存失败时 Toast 提示并保持当前主题，保存成功后才更新页面和系统栏。
- 未修改 `IntercomService`、`IntercomStateMachine`、`SessionOrchestrator`、`SignalingProtocol`、transport adapters、Gradle 或数据库。

自动化门禁：

- 命令：`.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain`
- 结果：`BUILD SUCCESSFUL in 2m 58s`
- 测试：104 suites / 870 tests；failures 0、errors 0、skipped 0。
- Lint：0 errors / 88 warnings。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，59,395,468 bytes。
- `git diff --check`：无空白错误。

新增或更新的定向覆盖包括：

- 1–30 个车友、多个画布半径下，气泡尺寸保持 48–72dp、相邻气泡不重叠，并在 320dp 实际 Compose 布局中检查单气泡与中心节点、状态胶囊和卡片边界的关系。
- 超出雷达容量时，按实际画布尺寸计算隐藏数量并断言“另有 N 位车友”。
- 雷达气泡点击继续分发精确的 `RadarPreviewPeer`。
- 顶栏只暴露“摩声”单一描述；Discover/Settings 的顶栏断言同步更新。
- 主题保存失败时保持原主题，提交失败结果可被测试注入并观测。

首轮只读复核结论为 `REQUEST CHANGES`，指出主题默认写入仍是 `apply()`、单气泡压住中心节点、TopBar/overflow 可能重复朗读。上述 P1/P2 已全部修复并重跑门禁。

第二轮只读复核结论：

```text
APPROVED
P0
- 无
P1
- 无
P2
- 无
Non-blocking
- 复核未重跑 Gradle；门禁数据沿用 104 suites / 870 tests、0 failures/errors/skipped，Lint 0 errors / 88 warnings，assembleDebug 成功。
- 本轮修复仍在未提交工作区，需随 PR #68 一并提交后再合入。
- PR #68 保持 Draft；未经明确授权不合并、不推送 main、不删除远端分支。
Base SHA
- c44c2ba78a253a317e8d4ce7175f2e7dcaf368b6
Head SHA
- 5ed818db9e5fd27d79c9f80a9311d15f2e8fb45b + working tree
Next gate allowed
- 可进入 Rasen verify-change / PR review cycle，并可将 PR #68 更新为待评审状态；合并、推送 main、发布仍不在本门禁授权范围内。
```

审查边界：只读检查了本轮完整差异、主题保存与回滚链路、雷达几何和 320dp 布局测试、顶栏/状态/溢出语义，以及治理禁止修改范围；未修改文件、未提交、未推送。

## 交付记录（2026-11-09）

- P2 复核通过的工作区内容已提交为 `fb61b16`，父提交为 `5ed818d`；提交内容与门禁运行的源码一致，仅额外包含验证记录和任务状态。
- 分支 `feat/kum-94-ui-components` 已推送；Draft PR #68 的源码 head 为 `fb61b16`，其后仅有验证记录提交。
- PR 描述同步更新为 104 suites / 870 tests、Lint 0 errors / 88 warnings 和源码 Head SHA。
- KUM-93 PR #67 当时仍为 Draft；本 PR 暂时保持堆叠 base `fix/kum-93-ui-accessibility`，未触发 Android CI。

## 基线同步与 PR 重定向（2026-11-09）

- KUM-93 PR #67 已获用户明确授权合入 `main`，merge commit 为 `c343413`。
- 功能分支以 merge commit `a70f9f6` 合入 `origin/main`，未使用 force push；源码内容未变化。
- Draft PR #68 的 base 已切换为 `main`，Android CI 在最终 head 上运行。
