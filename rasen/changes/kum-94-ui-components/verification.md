# KUM-94 验证记录

## 代码候选

- Base SHA：`c44c2ba78a253a317e8d4ce7175f2e7dcaf368b6`
- Branch：`feat/kum-94-ui-components`
- HEAD：`35c7de9524dee37e209303ce1f79e0e651db7eae`
- Reviewed Candidate Tree：`df498530ee042fc9cb040277b5b82a541b00bfaf`
- Worktree：`C:\Users\kuma\.codex\worktrees\kum-94-ui-components\MotoIntercomApp`
- 状态：本轮改动仍在工作树中，未提交、未推送；现有 Draft PR #68 仍指向之前的提交。

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
Next gate allowed
- 可以提交当前候选树并更新 Draft PR #68；未经用户明确授权不合并、不推送 main。
```

审查边界：

- 只读检查了 Git 状态、完整源码差异、真实发现数据到雷达点击的路径、主题持久化、状态映射、系统栏接线和测试覆盖。
- 雷达点击要求当前路由为 Home、产品状态为 Discovering、runtime session 一致、对象仍可连接且没有待处理连接，再调用既有 `connectToPresence` 路径。
- 主题选择先更新页面状态，再写入 `motocom_appearance`，并同步 `MainScreen` 的导航和系统栏外观。
- 没有新的产品状态写入者、后台资源所有者、协议路径或导航入口。

## 未提交边界

- 本轮未执行 `git commit`、`git push` 或 PR 更新。
- 未合并、未发布、未使用签名密钥、未修改生产数据库。
- Linear 状态与评论未在本轮修改。
