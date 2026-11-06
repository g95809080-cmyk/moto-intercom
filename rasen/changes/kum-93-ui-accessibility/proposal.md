## Why

当前 Compose 页面存在重复图标语义、昵称输入框缺少可访问标签，以及昵称空态提示文字对比度不足的问题。它们会直接影响 TalkBack 使用和低视力用户在设置、发现和返回操作中的理解。

## What Changes

- 图标按钮只保留父节点的单个可访问名称，内部装饰图标不再重复朗读。
- 昵称输入框保留真实可编辑文本和选区语义，可见标签继续作为独立名称，不以 `contentDescription` 覆盖输入内容。
- 昵称空态提示改用已有高对比度文字 token。
- 增加 Compose 语义回归，锁定按钮单一标签、输入框 `EditableText`、选区和无 `contentDescription`。
- 保持现有布局、导航、业务状态、持久化、通话流程和依赖不变。

## Capabilities

### New Capabilities

- `ui-accessibility`: Android Compose 页面的可访问名称、输入标签和文字对比度回归。

### Modified Capabilities

无。

## Impact

仅影响 `DiscoverScreen.kt`、`SettingsScreen.kt` 及其 Compose 测试；不修改服务、协议、状态机、传输、Gradle 或数据库。
