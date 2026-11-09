## Context

Home、Discover、Settings 和 Logs 继续接收不可变 UI state 与事件回调，状态仍由现有 `MainScreen`、`MainActivity` 和 `IntercomService` 持有。本次只调整 Compose 渲染、主题选择、导航外观和 Home 雷达的真实点击接线，不建立新的 ViewModel、状态持有者或导航路径。

## Goals / Non-Goals

**Goals:**

- 用 `NIGHTRIDE` 和 `DAYLIGHT` 两套色板统一四个主页面、系统栏和底部导航。
- 默认使用白昼主题，并让主题选择跨 Activity 重启保留。
- 把 Home 雷达做成整卡仪表视觉，使用头盔中心标记和真实车友气泡。
- 让车友气泡点击走现有连接请求路径，并按设备与会话精确匹配。
- 用短状态文案替代重复解释，保留真实业务状态和所有关键操作。
- 在 320dp 宽、1.3 倍字体下保持可读和可滚动。

**Non-Goals:**

- 不改业务状态机、导航、服务、协议、音频、依赖或数据库。
- 不迁移 Help、About、首次引导和来电确认的系统对话框。
- 不新增 TalkBack 设备验收或扩大 KUM-93 范围。
- 不引入新的设计系统库或截图测试框架。

## Decisions

- `MotoComThemeMode` 同时持有标签和完整色板；`MotoComTheme` 将其映射为 Material 3 light/dark scheme。默认 `DAYLIGHT`，避免首次安装进入深色界面。
- 主题选择通过 `AppearancePreferences` 写入 `motocom_appearance`，`MainActivity` 创建 `MainScreen` 前同步读取；设置页点击后立即更新 Compose、系统栏和导航背景。
- Home 雷达固定使用 `NIGHTRIDE` 色板，白昼主题下仍保留深色仪表卡，避免雷达对比度随主题下降。
- `MotoComTopBar` 只显示居中的“摩声”字标；发现和搜索状态由页面状态胶囊表达，不再恢复左上菜单或右上设置图标。
- 页面状态统一由 `productStatusLabel` 从真实 `IntercomState` 映射为“待机中、搜索中、待确认、连接中、已连接、恢复中、重置中、结束中”；设置页在已连接但音频未就绪时保留“正在等待音频就绪”。
- Home 雷达气泡不创建假数据。`MainScreen` 从 `discoverPresentation` 的同一有序车友列表生成气泡，连接时再按 `deviceId + sessionId` 回查当前可连接对象。
- 雷达中心使用现有 `ic_rider_24` 头盔标记；扫描、扩散和脉冲只在 `discovering && animationsEnabled` 时创建，减少动画时保留静态网格和真实状态。
- Discover 的离线状态优先显示“待机中”，Wi-Fi 不可用显示“Wi-Fi 未开启”；连接、恢复和结束等只读状态使用短状态胶囊，不把连接请求误标成“对讲中”。
- Settings 使用双列音频/VOX 卡片和主题选择器；主题入口直接可见，不再藏在“更多”或二级页面。
- 底部导航继续复用现有 XML Button，由 `MainScreen` 程序化设置为浮动胶囊、图标在左和主题色选中态，避免整页迁移导航框架。
- 文案收敛只删除重复或错误语境的说明，不删除权限、Wi-Fi、连接、静音、VOX 和日志导出等必要操作。

## Risks / Trade-offs

- 白昼主题的浅色背景与深色雷达形成明显对比，这是预期的仪表层级，不作为主题不一致处理。
- 新增主题持久化只保存枚举值；读取未知值时回退白昼，避免升级后出现不可恢复的空白主题。
- 雷达气泡按车友数量沿单环均匀分布，并按画布半径缩放为 48–72dp；容量不足时只显示可容纳数量并折叠为 `+N`，真实车友数量和身份仍来自发现列表，角度不作为物理方位含义。
- 小字号标签在 320dp、1.3 倍字体下已人工检查，但不等同于新的 TalkBack 设备验收。
- Help、About、首次引导和来电确认仍保留旧对话框样式，属于本轮明确的非目标。
