# MotoCom 当前版本非真机实现状态（2026-08-10）

这份增量记录用于覆盖 2026-07-17 UI PRD 中仍按旧检查点描述的条目；历史 PRD 原文保留，当前代码和本文件共同构成最新状态说明。

## 已推进的非真机闭环

- 自动重连：Settings 开关由 `MainActivity` 持久化，经过 `MainScreen` 和 `IntercomService` 传入串行信令协调器。关闭后，下一次 Connected 意外断开回到 Discovering，不创建恢复尝试；已经处于 Recovering 时切换开关不打断当前尝试。证据：`SignalingControlCoordinatorTest`、`MainActivityRobolectricTest`、`SettingsScreenComposeTest`、`IntercomServiceRobolectricTest`。
- 帮助与反馈：Discover/Settings 的帮助入口打开真实帮助弹窗；反馈通过系统 `ACTION_CHOOSER` 发送 `text/plain` 模板，仅含应用版本和 Android 版本，不附带日志、录音、URI 或 URI 授权。证据：`DiscoverScreenComposeTest`、`SettingsScreenComposeTest`、`MainScreenRobolectricTest`、`MainActivityRobolectricTest.feedbackUsesAnExplicitUserChooserWithoutAttachingSessionLogs`。
- 测试隔离：服务 Robolectric 测试在销毁服务后等待协程作用域结束，并重置 Room 单例，避免跨 sandbox 的 SQLite 指针异常污染后续 Compose 测试。
- 自动化门禁：GitHub Actions 增加 API 36 emulator 的 `connectedDebugAndroidTest` job；本地仍可从 `N:` 映射运行全量门禁。

## 当前仍不宣称完成

双设备 Presence/连接、LAN/Wi-Fi Direct/WebRTC/Bluetooth 真硬件行为、射频/电池/热、手动听感、物理设备 TalkBack、像素级视觉人工判断和发布设备验收仍为 `Deferred / Not Run`。模拟器状态、UI tree、JVM 测试不能替代这些证据。

## 架构边界

本轮不改变 `SessionOrchestrator` 唯一产品状态写入职责，不把 UI 状态写回产品状态；`IntercomService` 继续负责生命周期、副作用和资源；反馈 Intent 由应用创建并由用户显式选择接收应用。
