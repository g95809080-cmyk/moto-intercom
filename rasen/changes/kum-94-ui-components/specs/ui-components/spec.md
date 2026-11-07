## ADDED Requirements

### Requirement: Shared Surface And Icon Actions

Android Compose 页面 SHALL 通过共享组件渲染可重复的卡片表面和圆形图标操作。

#### Scenario: 图标操作只朗读一次

- **WHEN** 页面渲染共享图标按钮
- **THEN** 交互父节点提供唯一的可访问名称
- **AND** 内部图标不发布重复名称
- **AND** 点击回调仍由调用方提供

#### Scenario: 调用方控制根布局

- **WHEN** 页面把 `modifier.testTag(...)` 传给共享卡片
- **THEN** 该 tag 位于卡片根节点
- **AND** 卡片内容仍渲染在统一背景、边框和内边距内

### Requirement: Consistent Setting Rows

设置页面 SHALL 使用共享行组件组合图标、标题、辅助内容和尾部控件。

#### Scenario: 开关和选择行为不丢失

- **WHEN** 设置行包含开关或音频路由
- **THEN** 调用方 modifier 继续提供 toggleable、clickable 或 selected 语义
- **AND** 现有回调、持久化状态和测试 tag 不变

### Requirement: Material 3 Tokens

页面文字和形状 SHALL 使用主题 typography 与 shape token，而不是重复的页面级魔法数字。

#### Scenario: 辅助信息可读

- **WHEN** 页面渲染刻度、通道事实、状态标签或辅助说明
- **THEN** 字号不低于 12sp
- **AND** 主标题、卡片标题和正文使用对应 Material 3 typography

### Requirement: Consistent Motion Feedback

共享交互组件 SHALL 提供克制的按压和颜色反馈，不改变布局尺寸、触控边界或语义。

#### Scenario: 按压后回弹

- **WHEN** 用户按住共享图标按钮或 Home 主操作
- **THEN** 控件按 0.94 比例缩放并在释放后回弹
- **AND** 点击回调、可访问名称和禁用态保持不变

#### Scenario: 状态颜色平滑变化

- **WHEN** 卡片的选中边框、图标按钮容器或内容色发生状态变化
- **THEN** 颜色在 180ms 内过渡到目标值
- **AND** 布局不因动效重新测量
