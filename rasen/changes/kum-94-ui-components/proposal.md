## Why

现有 Android 界面能完成对讲，但信息层级依赖大量说明文字，Home、Discover、Settings 和 Logs 的卡片、状态与导航风格也不统一。概念预览确认了更简洁的“仪表 + 雷达”方向：减少解释性文案，把真实状态、可连接车友和音频操作放到更直接的位置，同时提供日间与夜航两套主题。

## What Changes

- 建立 `NIGHTRIDE`、`DAYLIGHT` 两套完整色板，默认白昼，并在设置页提供即时切换和本地持久化。
- Home 改为居中“摩声”字标、整卡雷达和底部主操作区；雷达使用头盔标记，发现阶段显示扫描、声纳波纹和回波。
- 首页雷达气泡使用真实 `RiderPresence` 数据；点击气泡按 `deviceId + sessionId` 回查并发起真实连接。
- Discover 改为在线数量、扫描入口、车友分组和重新扫描；离线状态显示“待机中”，不再伪装成对讲中。
- Settings 重做身份、主题、音频、VOX、自动重连、权限和更多入口；Logs 对齐同一主题和卡片结构。
- 底部导航改为圆角浮动胶囊，图标在左、文字在右，并随主题同步更新系统栏和选中态。
- Home、Discover、Settings 和 Logs 共享顶栏、面板、状态胶囊、开关和主题选择器等 Compose 组件。
- 清理重复描述和错误语境文案，例如设置页不再显示“点击下方启动摩声”，VOX 不再重复显示灵敏度。
- 保持导航、业务状态、服务、协议、持久化范围和音频流程不变；新增的持久化仅限主题选择。
- 辅助 Help、About、首次引导和来电确认继续使用现有系统对话框/引导实现，不在本次重做范围内。

## Capabilities

### New Capabilities

- `ui-components`: 双主题、四页统一视觉、真实雷达气泡和主题入口。

### Modified Capabilities

无。

## Impact

主要影响 `MotoComTheme.kt`、`MotoComConceptComponents.kt`、`HomeScreen.kt`、`DiscoverScreen.kt`、`SettingsScreen.kt`、`LogsScreen.kt`、`MainScreen.kt`、`MainActivity.kt`、`AppearancePreferences.kt`、`activity_main.xml`、`styles.xml`、字符串资源和相关测试。不改动 `IntercomService`、`IntercomStateMachine`、`SessionOrchestrator`、`SignalingProtocol`、transport adapters、Gradle 或数据库。
