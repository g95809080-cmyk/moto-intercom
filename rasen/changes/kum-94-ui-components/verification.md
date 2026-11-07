# KUM-94 验证记录

## 代码候选

- Base SHA：`c44c2ba78a253a317e8d4ce7175f2e7dcaf368b6`
- Reviewed Code Tree SHA：`55b2c1385bb14d8de01f18087d61485bec04d762`
- Motion Candidate Tree：`87dea0e756b1ec7012e7f7a44f5908ff68abdaed`
- Feature Commit：`81ba1fc1bd0f71c022c2d50fbe94dc5f23adafce`
- Feature Commit Tree：`8e23fbe75b72d90aacb348694d7c90a980bfe696`
- Motion Commit：`5a01aee1e4db0a8b19380c07fcd1d99a41d05ad7`
- Motion Commit Tree：`b7b80a03119a8cceb20f578bf17d8c24e93d8ab1`
- Draft PR：[#68](https://github.com/g95809080-cmyk/moto-intercom/pull/68)
- 分支：`feat/kum-94-ui-components`
- 工作树：`C:\Users\kuma\.codex\worktrees\kum-94-ui-components\MotoIntercomApp`
- 状态：动效补充已提交并推送到 `origin`；Draft PR 的 base 为 `fix/kum-93-ui-accessibility`。

## 自动化门禁

- Compose 定向测试：`MotoComComponentsTest`、`SettingsScreenComposeTest`、`DiscoverScreenComposeTest`、`HomeScreenComposeTest` 通过。
- 完整门禁：`testDebugUnitTest lintDebug assembleDebug` 通过。
- 测试结果：103 suites / 856 tests；failures 0、errors 0、skipped 0。
- Lint：0 errors / 71 warnings，与 KUM-93 基线一致。
- `assembleDebug`：成功，生成 `app/build/outputs/apk/debug/app-debug.apk`。
- `git diff --check`：干净。
- 首次最终门禁曾遇 Gradle daemon 系统内存不足；关闭模拟器释放内存后使用 `--no-daemon` 完整重跑成功，本次结果不是代码失败。

## 组件覆盖

- 图标按钮完整语义树中只有一个可访问名称，并路由真实点击回调。
- 调用方 `modifier.testTag(...)` 能落到 SurfaceCard 根节点。
- SettingRow 的 leading、headline、supporting、trailing 四个 slot 均可渲染。
- 既有页面测试继续覆盖 Settings 昵称输入、VOX 开关与滑块、音频路由、自动重连、Discover 重新扫描与配对管理、Home 主操作与 VOX 卡片。

## 动效补充

- 共享 `motoComPressScale` 使用 `0.94` 绘制缩放和低回弹 spring，不改变测量尺寸、触控边界或语义。
- `MotoComIconButton` 应用于按压和容器、边框、内容色的 180ms 过渡。
- `MotoComSurfaceCard` 的容器色和状态边框使用 180ms 过渡。
- Home 主操作复用同一按压弹簧，不改变现有 `onPrimaryAction`、禁用态或 guide anchor。
- 动效审查候选树 `87dea0e` 与提交树 `b7b80a0` 的源码一致，差异只涉及 Rasen 文档和 `motion.gif`。
- Compose 动画沿用系统 animator duration scale；系统关闭动画时动画会立即完成。

## 模拟器验收

- 设备：`emulator-5554`，API 36，1080x2400。
- 截图：`home.png`、`discover.png`、`settings.png`、`settings-bottom.png`。
- 动效证据：`motion.gif`，从 `DesignPreviewActivity` 录制 Home 主操作按压。
- 像素测量：主操作绿色圆形直径 `252px -> 246px -> 238px`，释放后回到 `252px`，对应约 `0.94` 缩放。
- 视觉检查：三张页面使用一致的浅色卡片、20dp 主卡圆角、深色 Home/Discover 面板和绿色主操作；辅助文字不再低于 12sp。
- 边界：本轮只验证视觉回归，不扩展或声称 TalkBack 设备验收。

## 架构审查

```text
APPROVED
P0
- 无
P1
- 无
Non-blocking
- LogsScreen 仍使用旧 motocom_card_radius，不在本 issue 三屏迁移范围。
- 模拟器截图只证明视觉状态，不证明 TalkBack 设备验收。
- 本分支堆叠在 KUM-93 head 上，KUM-93 合并后需 rebase 到 main，只保留本 issue 的 UI 组件提交。
Base SHA
- c44c2ba78a253a317e8d4ce7175f2e7dcaf368b6
Head SHA
- 55b2c1385bb14d8de01f18087d61485bec04d762（架构审查时的代码候选树）
- 55b2c13..8e23fbe 只增加 `tasks.md` 和 `verification.md`，没有源码变化。
Next gate allowed
- 提交当前候选树并创建 Draft PR；未经用户授权不合并、不推送 main。
```

动效补充审查：

```text
APPROVED
P0
- 无
P1
- 无
Non-blocking
- motion.gif 只记录 Home 主操作的按压；共享图标按钮复用同一 modifier，未单独录像。
- 180ms 颜色过渡未单独录制，由 Compose 测试和代码审查覆盖。
- 本次不增加页面转场或列表错峰入场。
Base SHA
- c44c2ba78a253a317e8d4ce7175f2e7dcaf368b6
Head SHA
- 87dea0e756b1ec7012e7f7a44f5908ff68abdaed（动效候选树）
Next gate allowed
- 提交动效补充、推送到功能分支并更新 Draft PR #68。
```

审查边界：

- 改动只覆盖 Compose 组件、主题 token、三屏渲染和相关测试。
- 未修改 `IntercomService`、`IntercomStateMachine`、`SessionOrchestrator`、`SignalingProtocol`、transport adapters、导航、持久化、Gradle 或数据库。
- 共享组件由调用方持有根 modifier、交互语义和回调；没有布尔外形开关矩阵。

## Linear 同步

- KUM-94 已写入实现、门禁、审查、commit 和 PR 摘要。
- 当前状态已推进到 `In Review`。
