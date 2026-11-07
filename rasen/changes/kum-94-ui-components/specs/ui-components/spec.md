## ADDED Requirements

### Requirement: Dual Theme With Persistence

应用 SHALL 提供 `夜航` 和 `白昼` 两套完整主题，并在重新启动后保留用户选择。

#### Scenario: 首次启动

- **WHEN** 本机没有保存过主题选择
- **THEN** 应用使用 `白昼`
- **AND** 设置页显示 `白昼` 已选中

#### Scenario: 切换主题

- **WHEN** 用户在设置页选择 `夜航` 或 `白昼`
- **THEN** 四个主页面、底部导航、状态栏和导航栏同步更新
- **AND** 选择写入本地偏好
- **AND** 强制停止并重新启动后仍保持该主题

#### Scenario: 未知持久化值

- **WHEN** 本地保存的主题值无法识别
- **THEN** 应用回退到 `白昼`

### Requirement: Centered Product Wordmark

主页面 SHALL 只在顶栏中央显示“摩声”字标。

#### Scenario: 页面切换

- **WHEN** 用户切换 Home、Discover、Settings 或 Logs
- **THEN** 顶栏继续居中显示“摩声”
- **AND** 不出现左上菜单、右上设置或其他旧入口图标

### Requirement: Truthful Status Labels

页面 SHALL 使用真实产品状态生成短标签，不得把连接中、待确认或恢复中显示成待机。

#### Scenario: 状态映射

- **WHEN** 产品处于 Offline、Discovering、IncomingConfirmation、Connecting、Optimizing、Connected、Recovering、Resetting 或 Stopping
- **THEN** 状态标签分别为“待机中、搜索中、待确认、连接中、连接中、已连接、恢复中、重置中、结束中”
- **AND** 已连接但音频未就绪时设置页显示“正在等待音频就绪”

#### Scenario: Discover 离线

- **WHEN** 应用离线且没有正在扫描
- **THEN** Discover 状态胶囊和摘要显示“待机中”
- **AND** 不显示“对讲中”或把离线原因当成附近车友标题

### Requirement: Full-Card Discovery Radar

Home SHALL 在发现车友阶段以铺满深色状态卡的雷达/声纳反馈表达扫描，同时保持离线、连接和减少动画状态清晰可辨。

#### Scenario: 启动后进入扫描

- **WHEN** Home 收到 `discovering=true` 且系统动画开启
- **THEN** 雷达扫描扇区持续旋转
- **AND** 声纳波纹从中心向外扩散
- **AND** 雷达背景覆盖整张状态卡并保留内嵌圆角边框
- **AND** 中心显示骑手头盔标记

#### Scenario: 关闭动画

- **WHEN** 系统动画关闭
- **THEN** 雷达保持静态网格
- **AND** 状态文字、主操作和可访问语义不变

#### Scenario: 白昼主题

- **WHEN** 应用处于白昼主题且雷达正在发现或连接
- **THEN** 雷达卡仍使用夜航深色色板

### Requirement: Real Discovered Peer Bubbles

Home 雷达 SHALL 只显示真实发现列表中的可连接车友，并允许点击气泡发起同一连接流程。

#### Scenario: 显示气泡

- **WHEN** 产品处于 Discovering 且发现列表包含可连接车友
- **THEN** 雷达气泡数量和名称来自真实发现数据
- **AND** 不可连接车友不生成可点击气泡

#### Scenario: 点击气泡

- **WHEN** 用户点击某个雷达气泡
- **THEN** 应用使用该车友的 `deviceId + sessionId` 回查当前发现对象
- **AND** 通过现有连接请求路径发起连接
- **AND** 过期或已不可连接的对象不得触发连接

### Requirement: Compact Four-Page Visual System

Home、Discover、Settings 和 Logs SHALL 使用统一的顶栏、面板、状态胶囊和主题色，并删除重复的描述性提示。

#### Scenario: 设置页主题入口

- **WHEN** 用户打开 Settings
- **THEN** 主题选择器直接显示在主内容中
- **AND** 不重复显示同一 VOX 灵敏度文案
- **AND** 自动重连卡片显示真实短状态而不是“点击下方启动摩声”

#### Scenario: 底部导航

- **WHEN** 任一主页面显示底部导航
- **THEN** 导航为圆角浮动胶囊
- **AND** 图标在左、文字在右
- **AND** 选中态和文字颜色随主题更新
