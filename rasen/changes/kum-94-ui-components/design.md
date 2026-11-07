## Context

Home、Discover 和 Settings 都接收不可变 UI state 和事件回调，状态仍由现有 `MainScreen` 与 `MainActivity` 持有。本次只调整 Compose 渲染层，不建立新的 ViewModel、状态持有者或导航路径。

## Goals / Non-Goals

**Goals:**

- 让同类卡片、图标操作、分组标题和设置行具有一致的视觉与语义结构。
- 把页面内写死的字号和圆角收敛到主题 token。
- 保持调用方对根 modifier、点击、选中、开关和测试 tag 的所有权。
- 用最小 Compose 测试证明共享组件契约。

**Non-Goals:**

- 不改业务状态、导航、服务、协议、持久化、音频、依赖或 Gradle。
- 不扩 KUM-93 的 TalkBack 验收范围。
- 不引入新的设计系统库或截图测试基础设施。

## Decisions

- `MotoComSurfaceCard` 固定卡片背景、边框和内容内边距，根 modifier 由调用方放置；可变内容使用单一 content slot。
- `MotoComIconButton` 只接收 `Painter`、可访问名称和点击回调。可访问名称放在交互父节点，内部 `Icon` 的 `contentDescription` 固定为 `null`，避免重复朗读。
- `MotoComSectionHeader` 只负责分组标题的字体与颜色；页面间距仍由调用方 modifier 提供。
- `MotoComSettingRow` 是纯布局组件，接受 leading、headline、supporting 和 trailing slots。`clickable`、`toggleable`、`selectable`、`semantics` 和 `testTag` 均由调用方 modifier 提供。
- 第一批字体 token 把辅助信息的最小字号提升到 12sp；卡片标题、正文、标签和页面标题分别使用 Material 3 typography。形状 token 采用 6/12/16/20/26dp 的递进关系。
- 离线配对状态新增 `motocom_status_pending`，与现有深绿色和弱化文字 token 区分。

## Risks / Trade-offs

- 统一组件会改变所有页面的卡圆角和标题字号，属于预期的视觉变化；通过三张模拟器截图验收。
- `MotoComIconButton` 默认提供浅色圆形背景，页头操作会比旧版更显眼；这是本次明确的视觉层级调整。
- `MotoComSettingRow` 不拥有交互语义，调用方仍需正确提供 role、toggleable 或 selectable 语义；现有页面测试继续覆盖这些行为。
