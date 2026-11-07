## Why

Home、Discover 和 Settings 已经统一由 Compose 渲染，但卡片、图标按钮、设置行和分组标题仍各自实现。字体大小混用 10–30sp，圆角大量写死为 14–26dp，同类信息在不同页面的层级和触控外观不一致。KUM-93 只修正了可访问名称和输入语义，没有解决这层 UI 重复。

## What Changes

- 新增 `MotoComSurfaceCard`、`MotoComIconButton`、`MotoComSectionHeader` 和 `MotoComSettingRow` 四个共享 Compose 组件。
- 在 `MotoComTheme` 建立第一批 Material 3 字体和形状 token，并把页面内写死的字号、圆角迁移到 token。
- 将 Home、Discover 和 Settings 的私有卡片、图标按钮和设置行替换为共享组件。
- 新增待上线状态的语义颜色 token，并让离线配对标签使用该 token。
- 增加共享组件契约测试，锁定单图标语义、调用方 modifier、slot 渲染和点击回调。
- 保持导航、业务状态、服务、协议、持久化、音频流程和依赖不变。

## Capabilities

### New Capabilities

- `ui-components`: Android Compose 页面的共享视觉组件和 Material 3 token。

### Modified Capabilities

无。

## Impact

主要影响 `MotoComComponents.kt`、`MotoComTheme.kt`、`HomeScreen.kt`、`DiscoverScreen.kt`、`SettingsScreen.kt`、`colors.xml` 和新增组件测试。不改动 `IntercomService`、`IntercomStateMachine`、`SessionOrchestrator`、`SignalingProtocol`、transport adapters、Gradle 或数据库。
