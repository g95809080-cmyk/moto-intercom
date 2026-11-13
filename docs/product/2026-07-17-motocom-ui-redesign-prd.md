# 摩声 MotoCom Android UI 重构 PRD

## 0. 文档信息

| 项目 | 内容 |
| --- | --- |
| 产品 | 摩声 MotoCom |
| 文档类型 | Android 前端 UI 重构产品需求文档 |
| PRD 版本 | v1.0 |
| 日期 | 2026-07-17 |
| 需求状态 | 已确认，可用于后续实现 |
| 实现状态 | 源码/UI 已落地；模拟器、真机与人工听音验收仍待完成 |
| 当前仓库 | <code>C:\Users\kuma\Documents\摩托对讲机\MotoIntercomApp</code> |
| 编写时分支 | <code>feat/sprint2-signaling-v2</code> |
| 编写时基线 | <code>1438cb2</code> |
| 设计稿 | <code>C:\Users\kuma\Desktop\ChatGPT Image 2026年7月8日 01_14_09.png</code> |

> 本文档不讨论开发排期。未来开始实现前，必须重新核对 Git 分支、当前产品状态模型、接口签名和真机环境。

## 1. 文档目的

本 PRD 用于把已确认的 MotoCom UI 需求固化为可直接执行的开发依据，解决以下问题：

1. 当前主界面将状态、音频、VOX、发现车友、设置和日志全部纵向堆在一个长页面中，与原设计稿的多页面结构不一致。
2. 当前部分 UI 标签并非来自精确接口，而是通过产品状态或音量阈值推算，容易形成伪状态。
3. 当前动态 View 页面过大，不利于多页面导航、不同屏幕尺寸适配和后续维护。
4. 未实现功能缺少统一的占位交互规范。
5. 发现身份、当前 Socket 身份和真正连接成功之间的产品文案边界需要明确。

本文档定义页面结构、状态映射、真实能力、占位能力、响应式布局、可访问性、技术边界和验收标准。

## 2. 设计参考

原始设计稿由以下内容组成：

1. 主对讲界面（首页）。
2. 发现车友 / 配对界面。
3. 设置 / 音频设置界面。
4. 设计稿左侧的头盔、Logo、产品卖点和路线图属于展示板，不属于 App 页面。

本地参考图：

![MotoCom 浅色 UI 设计稿](<C:/Users/kuma/Desktop/ChatGPT Image 2026年7月8日 01_14_09.png>)

### 2.1 还原原则

- 高度还原设计稿的页面层级、卡片结构、主按钮位置、浅色视觉和荧光绿强调色。
- 不以截图切片作为页面背景。
- 不为了视觉相似而伪造昵称、设备、头像、电量、距离、连接状态或身份结论。
- 不为了实现设计稿而修改底层连接协议或增加假功能。
- Android 屏幕、字体、系统栏和可访问性要求优先于像素级复制。

## 3. 产品背景

MotoCom 是一对一、离线优先的骑行实时对讲应用。当前真实能力包括：

- 启动和停止前台对讲服务。
- LAN 与 Wi-Fi Direct 发现。
- 基于稳定设备身份的 Presence 聚合。
- 选择附近车友并创建目标锁定。
- Signaling v2 请求、接受、拒绝、忙碌和断开。
- 当前 Socket 身份检查。
- WebRTC 音频连接。
- 当前音频来源和蓝牙路由状态反馈。
- 入站连接确认。
- 日志和音量电平展示。

UI 的职责是：

1. 展示真实产品状态。
2. 发送用户意图。
3. 不成为新的产品状态写入者。
4. 不越过身份、Attempt、Transport、Socket、Signaling 和 WebRTC 边界。

## 4. 目标

### 4.1 产品目标

- 将当前单一长页面重构为三个一级页面和一个二级页面。
- 让启动、发现车友、连接、设置和诊断路径更自然。
- 忠实呈现真实状态，删除伪造或推算出来的状态。
- 为尚未接入的功能提供一致、可理解的占位交互。
- 覆盖不同尺寸、长宽比、刘海、挖孔和圆角 Android 屏幕。
- 保持现有对讲功能、权限、Service 生命周期和回调链路不变。

### 4.2 工程目标

- 使用单 Activity 和 XML 页面。
- 将页面结构、视觉资源和状态渲染职责拆分清楚。
- 不引入 Compose、Fragment 或新的第三方 UI 依赖。
- 保留现有 Service 和产品状态的单一事实来源。
- 建立可重复执行的视觉与功能验收矩阵。

## 5. 非目标

本 UI 工作不得顺带实现以下内容：

- VOX 开关、灵敏度配置或精确 VOX 状态接口。
- 静音能力。
- 手动选择蓝牙耳机、听筒或外放。
- 自动重连策略开关。
- 手动重启 LAN 或 Wi-Fi Direct 扫描。
- 帮助中心或反馈提交服务。
- 新的 PIN、二维码、证书或账号身份体系。
- Sprint 3 Transport Race、双通道竞速或赢家仲裁。
- Sprint 4 完整恢复策略。
- 新的 WebRTC、音频路由、TCP、Wi-Fi Direct 或 Signaling 实现。
- 用户头像上传、车队、车型、位置、距离或社交资料。
- 平板专用双栏布局。
- 横屏专用视觉设计。
- 深色主题。

## 6. 锁定决策

以下决策已经确认，后续实现不得自行更改：

1. 使用三个一级页面：Home、Discover Riders、Settings。
2. 使用一个二级页面：Settings > Logs。
3. 使用一个 Activity。
4. 页面使用 XML 实现。
5. 不使用 Compose。
6. 不使用 Fragment。
7. 不增加 UI 依赖。
8. 页面各自拥有独立滚动区域。
9. 首页不再承载发现、设置和日志的完整内容。
10. 未实现功能保持可点击，点击后弹统一“功能开发中”对话框。
11. 未实现功能不得改变任何产品、导航或设置状态。
12. 不显示假昵称、假设备、假电量、假头像或假身份验证结果。
13. 头像统一使用通用 MotoCom 骑士头盔占位图。
14. 屏幕适配范围为 320dp 至 600dp。
15. 支持系统字体比例至少 1.3x。
16. 宽屏限制内容最大宽度并居中。
17. 固定使用浅色主题。
18. 日志保留为真实能力。

## 7. 用户与核心场景

### 7.1 主要用户

- 两名近距离骑行者。
- 使用 Android 手机进行一对一离线对讲。
- 可能佩戴蓝牙耳机。
- 可能在前台、后台或锁屏状态中操作。
- 可能使用小屏旧设备或带挖孔、圆角的较新设备。

### 7.2 核心场景

1. 用户打开 App，在首页启动摩声。
2. 已配对车友出现后，应用按现有规则发起连接。
3. 没有可自动连接目标时，用户主动进入发现车友页。
4. 用户从真实 Presence 列表选择车友。
5. 应用回到首页展示真实连接进度。
6. 连接成功后展示真实对端、通道和音频状态。
7. 连接断开时恢复原目标，不被普通发现结果替换。
8. 陌生车友请求连接时，用户接受或拒绝。
9. 用户进入设置页修改昵称或查看真实设备状态。
10. 用户进入日志页查看和复制当前 UI 会话日志。
11. 用户点击尚未实现的设置，看到明确的开发中提示。

## 8. 术语和状态边界

| 概念 | 含义 | UI 权限 |
| --- | --- | --- |
| UI Route | 当前显示的 Home、Discover、Settings 或 Logs | UI 可写 |
| Product State | Offline、Discovering、Connecting、Connected 等 | UI 只读 |
| Connection Attempt | 当前目标、触发方式、Attempt ID、截止时间和计划通道 | UI 只读 |
| Transport | LAN 或 Wi-Fi Direct 物理通道 | UI 只读 |
| Discovery Identity | Presence、TXT 或 NSD 中的发现声明 | 只能描述为发现信息 |
| Current Socket Identity | 当前 Socket HELLO / IDENTITY 声明 | 连接流程内部校验 |
| Signaling | 请求、接受、拒绝、忙碌、断开、SDP 和 ICE 顺序 | UI 不直接驱动协议 |
| WebRTC | 产品授权后的媒体会话 | UI 只读 |

### 8.1 强制边界

- UI Route 与 Product State 必须分离。
- 页面切换不得触发产品状态跳转。
- Product State 继续由 <code>SessionOrchestrator</code> 单一写入。
- 三个一级页面共享同一个 Activity 和同一个 Service Listener owner，不得让多个页面或 Activity 竞争注册 Listener。
- UI 不得根据昵称、IP、MAC、设备名、发现记录或 Target Lock 宣称身份已经确认。
- 只有当前连接流程完成适用的 Socket 身份检查后，首页才可以显示“对端身份已确认”。
- 发现页不得使用“已认证”“已验证身份”或同类文案。

## 9. 信息架构

    MainActivity
    ├── Home
    ├── Discover Riders
    ├── Settings
    │   └── Logs
    ├── Navigation Panel
    ├── Incoming Confirmation Dialog
    └── Feature Placeholder Dialog

### 9.1 默认路由

- App 冷启动默认进入 Home。
- Activity 重建后恢复重建前的 UI Route。
- 进程重新启动后默认进入 Home。
- Product State 始终从重新绑定的 Service 回调恢复，不从页面保存状态伪造。

## 10. 导航规则

### 10.1 一级导航

- Home 顶部汉堡按钮打开导航面板。
- 导航面板包含：首页、发现车友、设置。
- Home 顶部齿轮直接进入 Settings。
- Home 音频设置图标进入 Settings，并定位到音频区域。
- Home 无目标 CTA 进入 Discover Riders。

### 10.2 返回规则

| 当前界面 | Android Back 行为 |
| --- | --- |
| Navigation Panel 打开 | 关闭导航面板 |
| Feature Placeholder Dialog | 关闭对话框 |
| Incoming Confirmation Dialog | 按真实请求规则处理，不允许通过 Back 绕过 |
| Logs | 返回 Settings |
| Discover Riders | 返回 Home |
| Settings | 返回 Home |
| Home | 执行系统默认退出/后台行为 |

### 10.3 页面恢复

- 使用 <code>savedInstanceState</code> 保存当前 UI Route。
- 页面切换时保留各页面滚动位置。
- Activity 重建后恢复当前 Route 和滚动位置。
- 连接状态、对端、Presence、音频和日志不从保存的 View 文本恢复，必须重新绑定真实数据。

## 11. 状态驱动的主要流程

### 11.1 从首页启动

1. 用户点击“启动摩声”。
2. 调用现有启动逻辑。
3. 页面保持在 Home。
4. Product State 进入 Discovering 后展示真实搜索状态。
5. 若现有配对和发现逻辑找到目标，继续展示 Connecting。
6. 若当前没有目标 Attempt，显示“查看附近车友”CTA。
7. 不强制自动跳入 Discover Riders。

### 11.2 手动选择车友

1. 用户进入 Discover Riders。
2. 仅展示真实 Presence。
3. 只有 Product State 为 Discovering 且 Presence 仍可选择时，显示真实“连接”按钮。
4. 用户点击“连接”后调用现有 <code>connectToPresence</code> 路径。
5. 连接意图被 UI/controller 接受后返回 Home。
6. 后续成功或失败只根据 Product State 和 Service 回调展示。

### 11.3 非 Discovering 状态选择规则

| Product State | Discover 页行为 |
| --- | --- |
| Offline | 不展示可连接按钮；展示“启动摩声后开始发现”真实操作 |
| Discovering | 可连接的 Presence 显示“连接” |
| IncomingConfirmation | 列表只读，真实请求对话框优先 |
| Connecting | 列表只读，不允许替换当前目标 |
| Optimizing | 列表只读，不允许替换当前目标 |
| Connected | 列表只读，提示需先结束当前对讲 |
| Recovering | 列表只读，禁止替换原目标 |
| Resetting | 列表只读，禁止创建新目标 |
| Stopping | 列表只读 |

### 11.4 连接失败与恢复

- 连接失败返回 Discovering 时，Home 显示真实失败/搜索文案和“重新选择车友”CTA。
- Recovering 时继续展示原目标真实昵称；如果当前状态没有可展示昵称，则显示“正在恢复原车友连接”，不得补假昵称。
- 普通 Presence 更新不得替换恢复目标。
- Resetting 时只显示通用重置文案，不从设备 ID 推导昵称。

## 12. 全局交互规则

### 12.1 防重复点击

- 主 Start/Stop 按钮保留现有防快速重复点击能力。
- Stopping 状态主按钮禁用并显示“停止中…”。
- 正在处理页面切换时，不重复创建相同页面实例。
- 同一占位对话框已显示时，后续占位点击不得堆叠新对话框。

### 12.2 对话框优先级

优先级从高到低：

1. 当前有效的 Incoming Confirmation。
2. 权限或系统阻断对话框。
3. Feature Placeholder Dialog。

当真实 Incoming Confirmation 到达时：

- 关闭正在显示的 Feature Placeholder Dialog。
- 显示当前有效请求对话框。
- 对话框必须绑定当前 action nonce / Attempt。
- 请求取消、超时或被替换后立即关闭旧对话框。
- 旧对话框操作不得影响新请求。

## 13. Home 页面规格

### 13.1 页面目标

Home 是对讲状态和主要操作中心。用户无需滚动穿过发现、设置或日志即可完成启动、观察连接和停止。

### 13.2 页面结构

从上到下：

1. 顶部导航栏。
2. 产品标题和“一对一对讲 · 无需网络”状态胶囊。
3. 主连接卡片。
4. 音频输出卡片。
5. 主控制区。
6. VOX 占位状态区。
7. 条件性“查看附近车友 / 重新选择车友”CTA。
8. 页面底部安全间距。

### 13.3 顶部导航栏

- 左侧：汉堡菜单，打开导航面板。
- 中间：摩声 MotoCom。
- 右侧：设置齿轮，进入 Settings。
- 所有点击区域至少 48dp。

### 13.4 主连接卡片

必须展示：

- Product State 对应的主状态文案。
- 状态补充文案。
- 通用骑士头像。
- 真实远端昵称或安全空态。
- 真实 Transport 标签。
- WebRTC 状态。
- 蓝牙音频状态。
- VOX 占位标签。

不得展示：

- 假头盔照片对应的假身份。
- 假设备型号。
- 假电池百分比。
- 未经当前 Socket 流程支持的“身份已认证”。

### 13.5 车友展示

| 条件 | 显示 |
| --- | --- |
| 无真实远端 | 等待车友加入 |
| Connecting/Optimizing 且存在真实 peer | 显示真实昵称 |
| Connecting/Optimizing 且 peer 为空 | 正在确认目标车友 |
| Connected | 显示真实已连接 peer 昵称 |
| Recovering | 显示原目标真实昵称 |
| Resetting 且无昵称 | 正在恢复原车友 |
| Stopping | 可保留最后一个真实昵称，进入 Offline 后清空 |

### 13.6 状态文案矩阵

| IntercomState | 主文案 | 补充文案 | 主按钮 | 按钮状态 | 发现 CTA |
| --- | --- | --- | --- | --- | --- |
| Offline | 点击下方启动摩声 | 一对一对讲 · 无需网络 | 启动摩声 | 权限满足时启用 | 隐藏 |
| Discovering | 正在寻找附近 MotoCom 车友 | 保持两台手机的 Wi-Fi 可用 | 结束对讲 | 启用 | 显示 |
| IncomingConfirmation | 收到附近车友的连接请求 | 请在确认框中选择接受或拒绝 | 结束对讲 | 启用 | 隐藏 |
| Connecting | 正在建立连接 | 正在建立信令与媒体通道 | 结束对讲 | 启用 | 隐藏 |
| Optimizing | 正在优化连接通道 | 保持当前目标不变 | 结束对讲 | 启用 | 隐藏 |
| Connected | 语音通道已连接 | 对讲已就绪 | 结束对讲 | 启用 | 隐藏 |
| Recovering | 正在恢复原车友连接 | 不会切换到其他附近车友 | 结束对讲 | 启用 | 隐藏 |
| Resetting | 正在重置无线连接 | 请稍候 | 结束对讲 | 启用 | 隐藏 |
| Stopping | 正在结束对讲 | 正在释放连接和音频资源 | 停止中… | 禁用 | 隐藏 |

说明：

- Optimizing 和 Resetting 属于必须安全渲染的完整状态模型，但在编写本 PRD 时不是当前常规生产流程，不得作为已经上线的常用能力宣传。
- UI 必须支持未来真实产生这两个状态时正确显示，但不得自行制造它们。

### 13.7 状态数据优先级

- 主文案由 <code>IntercomState</code> 决定。
- <code>onStatusChanged</code> 只能作为补充状态或日志，不得覆盖 Product State 语义。
- <code>onError</code> 可更新错误补充文案，但不得自行把 Product State 改成 Offline 或 Connected。
- 对端昵称必须按当前 IntercomState 决定是否展示，不能依赖旧 TextView 内容；离开有效对端状态后必须清除或切换到安全空态。

### 13.8 通道标签

| 标签 | 真实来源 | 激活规则 |
| --- | --- | --- |
| LAN | 当前 Attempt 的 ChannelPlan | 计划或连接通道为 LAN |
| Wi-Fi Direct | 当前 Attempt 的 ChannelPlan | 计划或连接通道为 WIFI_DIRECT |
| WebRTC | Product State | 仅 Connected 显示“已连接”；Connecting 只能显示“建立中” |
| 蓝牙耳机 | <code>onAudioSourceChanged</code> | 仅真实 bluetooth=true 时激活 |
| VOX | 无精确接口 | 始终显示灰色“状态接口待接入”，点击弹占位对话框 |

禁止继续沿用以下推算方式：

- 用所有 active 状态推断 Wi-Fi Direct 已连接。
- 用 Connecting 推断 WebRTC 已连接。
- 用 Connected 推断 VOX 正处于某一精确门控状态。
- 用音量阈值推断 LISTENING、OPEN 或 HANGOVER。

### 13.9 主控制区

包含：

- 左侧静音图标：占位入口。
- 中央大圆形 Start/Stop：真实入口。
- 右侧音频设置图标：真实导航入口，进入 Settings 音频区域。

主按钮继续调用现有 Start/Stop 逻辑，不改变权限判断和 Service 生命周期。

### 13.10 VOX 状态区

设计稿中的 LISTENING、OPEN、HANGOVER 保留视觉结构，但必须：

- 使用灰色非激活样式。
- 显示“状态接口待接入”。
- 整个区域点击后弹 Feature Placeholder Dialog。
- 不根据音量波形点亮任意阶段。

### 13.11 音量波形

- 数据来自真实 <code>onAudioLevelChanged</code>。
- 仅表示当前音量活动，不表示 VOX 精确状态。
- 页面不可见或系统动画关闭时停止动画刷新。
- 不得驱动产品状态。

## 14. Discover Riders 页面规格

### 14.1 页面目标

展示真实附近车友 Presence，让用户在允许的产品状态下明确选择目标。

### 14.2 页面结构

1. 顶部返回栏与帮助占位按钮。
2. 搜索状态和雷达动画。
3. 身份边界说明。
4. 已配对车友分组。
5. 附近车友分组。
6. 空态。
7. 手动重新扫描占位按钮。
8. 一对一产品提示。

### 14.3 搜索雷达

- 仅在 Product State 为 Discovering 时运行动画。
- 其他状态停止动画并显示对应只读状态。
- 系统动画关闭时使用静态雷达图。
- 雷达仅表示应用正在发现，不代表真实距离或方向。

### 14.4 身份说明

固定文案：

> 发现信息仅用于选择目标；真正连接仍需当前通道身份检查。

不得使用：

- 已通过身份认证。
- 安全设备。
- 已验证真人。
- 可抵抗同网络攻击。

### 14.5 列表分组与排序

排序顺序：

1. 在线且优先的已配对车友。
2. 在线的已配对车友。
3. 在线的未配对附近车友。
4. 暂时离线的已配对车友。

UI 不自行计算 Presence TTL，完全使用 Service 提供的 Presence Snapshot。

### 14.6 车友卡片

显示真实字段：

- 通用骑士头像。
- displayName。
- deviceName。
- 可用 Transport：LAN、P2P 或 LAN + P2P。
- 已配对。
- 优先车友。
- 当前是否可选择。

不得显示：

- 假距离。
- 假在线时长。
- 假车型。
- 假头像。
- 发现阶段的“身份已确认”。

### 14.7 同昵称设备

若多个 Presence 的 displayName 相同：

- 继续显示真实 deviceName。
- deviceName 仍相同或为空时，可显示稳定 deviceId 的末 4 位作为区分标识。
- 不显示完整 deviceId。
- 区分标识不等于认证声明。

### 14.8 Presence 过期

- Presence 从真实 Snapshot 消失时，卡片随下一次渲染移除。
- 用户点击后 Presence 立即过期时，由现有连接路径拒绝或失败。
- UI 不缓存过期 Presence 作为可选项。
- 连接失败后根据最新 Product State 和 Presence 重新渲染。

### 14.9 连接按钮

- 仅 <code>presence.isSelectable</code> 且 Product State 为 Discovering 时显示。
- 点击调用现有 connectToPresence。
- 不直接把 UI 改成 Connected。
- 请求被 controller 接收后返回 Home。
- Service 尚未绑定时不返回 Home，显示真实“后台服务未就绪”反馈。

### 14.10 Offline 空态

Discover Riders 可从导航面板在 Offline 状态打开。

此时：

- 不显示旧 Presence 的可连接按钮。
- 显示“启动摩声后开始发现附近车友”。
- 提供真实“启动摩声”按钮。
- 点击后调用现有启动逻辑并留在 Discover Riders，等待真实 Presence。

### 14.11 手动重新扫描

当前没有手动重启发现接口，因此：

- 按钮保持可点击。
- 显示“开发中”标记。
- 点击弹 Feature Placeholder Dialog。
- 不调用任何 LAN/Wi-Fi Direct 重启逻辑。

## 15. Settings 页面规格

### 15.1 页面结构

1. 顶部返回栏。
2. 个人设置。
3. VOX 自动开麦。
4. 音频输出。
5. 连接方式。
6. 设备状态。
7. 高级设置。
8. App 版本。

### 15.2 本机昵称

真实能力。

规则：

- 提供输入框和明确的“保存昵称”按钮。
- 保存前去除首尾空白。
- 空内容不保存并显示输入错误。
- 最大长度为 64 个 Unicode code point。
- 昵称不要求唯一。
- 离线保存后，下次启动摩声使用新昵称。
- 对讲运行中允许保存，但提示“已保存，下次启动对讲时生效”。
- 活动连接期间不动态修改广播、当前 Socket 身份或远端已显示昵称。
- 保存失败时保留编辑内容，并显示真实错误。
- Activity 重建后从已保存数据恢复。

### 15.3 VOX 区域

以下全部为占位：

- VOX 开关。
- 灵敏度滑块。
- LISTENING / OPEN / HANGOVER 精确状态。

展示规则：

- 不显示虚假的开关开启/关闭值。
- 不显示虚假的灵敏度数字或滑块位置。
- 使用灰色图标和“开发中”标签。
- 点击任意控件弹 Feature Placeholder Dialog。
- 不调用 RiderAudioEngine。

### 15.4 音频输出

真实只读内容：

- 当前音频来源文本。
- 蓝牙是否实际连接。
- Service 回调提供的真实设备描述。

占位内容：

- 手动选择蓝牙耳机。
- 手动切换手机听筒。
- 手动切换手机外放。

规则：

- 不显示设计稿中的假型号 M200 Pro。
- 不显示假电量 80%。
- 没有设备名时只显示“蓝牙音频已连接”。
- 选择控件显示开发中样式，点击弹占位对话框。
- 不调用 AudioRouteController 的内部策略。

### 15.5 连接方式

真实只读说明：

- 当前 Attempt 的计划 Transport。
- 发现列表中实际可用的 LAN / P2P 候选。
- 产品当前为单 Attempt 单 Transport 计划。
- UI 只显示当前真实计划或候选，不宣称固定优先 Wi-Fi Direct；编写本 PRD 时，现有选择路径可能优先采用 LAN。

禁止文案：

- 同时竞速两个通道。
- 自动选择最快通道（除非未来真实实现）。
- 切换 Transport Race。

### 15.6 自动重连

当前产品存在真实 Recovering，但没有用户开关接口。

因此：

- “自动重连”设置是占位入口。
- 不显示开启或关闭状态。
- 不允许它控制现有恢复逻辑。
- 点击弹 Feature Placeholder Dialog。

### 15.7 设备状态

真实只读展示：

- 当前音频来源。
- 蓝牙连接布尔状态。
- 当前 Product State。
- 当前 Transport（存在 Attempt 时）。

不显示系统未提供的数据。

### 15.8 高级设置

| 入口 | 类型 | 行为 |
| --- | --- | --- |
| 日志诊断 | 真实 | 进入 Logs |
| 帮助与反馈 | 占位 | 弹 Feature Placeholder Dialog |
| 关于我们 | 真实静态信息 | 弹 About Dialog |

### 15.9 About Dialog

展示：

- 摩声 MotoCom。
- 当前真实 versionName。
- 一对一离线骑行对讲定位。
- “power by kuma”可保留为品牌文案。
- 确定/关闭按钮。

不得展示不存在的网站、客服、隐私认证或版本能力。

## 16. Logs 二级页面规格

### 16.1 页面目标

保留现有调试能力，同时从 Home 移除长日志卡片。

### 16.2 日志范围

- 仅展示当前 UI 会话收到的日志。
- 内存缓冲最多 300 条。
- Activity/进程重建后可以丢失，页面必须明确说明。
- 不宣称日志已持久化。
- 不伪造缺失的时间戳或历史记录。

### 16.3 页面结构

1. 返回 Settings。
2. 标题“日志诊断”。
3. 说明“仅显示本次界面会话日志”。
4. 可滚动等宽字体日志区域。
5. “复制全部”。
6. “关闭”。

### 16.4 更新行为

- 页面不可见时继续写入 <code>BoundedLogBuffer</code>。
- 页面不可见时不得持续刷新隐藏 TextView。
- 打开 Logs 时一次性渲染最新 Snapshot。
- Logs 可见时追加新日志。
- 用户位于底部时自动滚到底部。
- 用户已经向上查看历史时，不强制抢回底部。

### 16.5 复制行为

- 按当前显示顺序复制全部日志。
- 日志之间以换行分隔。
- 无日志时禁用复制按钮。
- 成功后显示简短“日志已复制”反馈。
- 不提供假上传、假导出文件或自动问题诊断。

## 17. Navigation Panel 规格

包含：

- 首页。
- 发现车友。
- 设置。

规则：

- 当前页面有明确选中态。
- 点击当前页面只关闭面板，不重复创建页面。
- 点击遮罩或 Back 关闭面板。
- 不加入尚未确认的产品入口。
- 宽屏仍使用同一导航语义，不改成新的产品结构。

## 18. Feature Placeholder Dialog 统一契约

### 18.1 固定内容

| 项目 | 内容 |
| --- | --- |
| 标题 | 功能开发中 |
| 正文 | 该功能还没做好，暂时无法使用。 |
| 按钮 | 确定 |

### 18.2 行为

- 所有占位功能使用同一个 Dialog 实现。
- Dialog 同时只允许一个实例。
- 点击“确定”关闭。
- Back 可关闭。
- 不跳转页面。
- 不写设置值。
- 不修改 Product State。
- 不修改 Connection Attempt。
- 不调用 Service、Transport、WebRTC、音频或发现接口。
- 除 Dialog 显示/关闭外，不产生其他 UI 状态变化。

### 18.3 视觉

- 占位入口使用灰色图标或“开发中”标签。
- 容器仍保持可点击和至少 48dp 触控区域。
- TalkBack 描述需包含“功能开发中”。

## 19. Incoming Confirmation 规格

该对话框为真实安全相关入口，优先级高于占位对话框。

必须保留：

- 真实骑士昵称。
- 真实设备名。
- “接受”。
- “拒绝本次”。
- 不可通过点击外部区域取消。
- 绑定当前 action nonce。
- 请求取消或超时后关闭。
- 旧请求按钮不影响新请求。

接受后：

- 只调用现有 respondToIncomingConfirmation。
- 导航到 Home。
- 等待 Product State 更新。

拒绝后：

- 只调用现有拒绝路径。
- 保持当前页面或回到 Home 均不得伪造 Connected。
- 推荐保持用户当前 Route，真实状态由 Service 更新。

## 20. 真实能力与占位能力矩阵

| 能力 | 类型 | 入口 | 真实来源/动作 | 禁止事项 |
| --- | --- | --- | --- | --- |
| 启动摩声 | 真实 | Home / Discover Offline 空态 | 现有 startIntercom | 不绕过权限 |
| 结束对讲 | 真实 | Home 主按钮 | 现有 stopIntercom | 不直接清 Product State |
| 选择车友 | 真实 | Discover 卡片 | connectToPresence | 不自行标记 Connected |
| 入站接受/拒绝 | 真实 | Incoming Dialog | Service 响应接口 | 不处理过期 nonce |
| 修改昵称 | 真实 | Settings | 保存本地昵称；下次启动生效 | 活动连接中不动态广播 |
| Product State | 真实只读 | Home / Settings | onIntercomStateChanged | UI 不写状态 |
| 状态补充文本 | 真实只读 | Home | onStatusChanged | 不覆盖 Product State 语义 |
| Presence | 真实只读 | Discover | onPresencesChanged | 不缓存过期候选 |
| 对端昵称 | 真实只读 | Home | Connected peer / onRemoteRiderIdentified | 不生成假昵称 |
| 音频来源 | 真实只读 | Home / Settings | onAudioSourceChanged | 不显示假设备 |
| 音量波形 | 真实只读 | Home | onAudioLevelChanged | 不推断 VOX 状态 |
| 日志 | 真实 | Logs | onLog + BoundedLogBuffer | 不伪造持久化 |
| 静音 | 占位 | Home | Feature Placeholder Dialog | 不改音轨 |
| VOX 开关 | 占位 | Settings | Feature Placeholder Dialog | 不改 RiderAudioEngine |
| VOX 灵敏度 | 占位 | Settings | Feature Placeholder Dialog | 不显示假数值 |
| VOX 精确状态 | 占位 | Home / Settings | Feature Placeholder Dialog | 不用音量阈值推算 |
| 手动音频路由 | 占位 | Settings | Feature Placeholder Dialog | 不改 AudioRouteController |
| 自动重连开关 | 占位 | Settings | Feature Placeholder Dialog | 不影响 Recovering |
| 手动重新扫描 | 占位 | Discover | Feature Placeholder Dialog | 不重启 Transport |
| 帮助与反馈 | 占位 | Settings / Discover | Feature Placeholder Dialog | 不显示假提交成功 |
| 关于我们 | 真实静态 | Settings | About Dialog | 不显示不存在的服务 |

## 21. 权限和系统条件

### 21.1 核心权限缺失

- Home 主按钮不得假装可启动。
- 展示可执行的权限说明。
- 提供真实“授权”操作。
- 用户永久拒绝时提供进入系统设置的真实入口。
- 不把权限失败描述为连接失败。

### 21.2 可选权限

- 通知或蓝牙可选权限未授予时，不得错误阻止所有对讲启动。
- 页面以真实能力降级文案说明。
- 不显示蓝牙已连接。

### 21.3 Wi-Fi 不可用

- 显示真实 Wi-Fi 不可用提示。
- 若当前已有打开 Wi-Fi 设置/面板能力，可保留真实入口。
- 不把 Wi-Fi 不可用描述为“没有附近车友”。

### 21.4 Service 未连接

- Start/Connect 操作不得静默失败。
- 显示“后台服务未就绪”。
- 不切换到 Connected 或 Connecting 假状态。

## 22. 视觉规范

### 22.1 颜色令牌

| Token | 色值 | 用途 |
| --- | --- | --- |
| Background | #F7F9FC | 页面背景 |
| Surface | #FFFFFF | 卡片和导航 |
| Text Primary | #111827 | 主文字 |
| Text Secondary | #6B7280 | 次级文字 |
| Text Muted | #9CA3AF | 占位和弱提示 |
| Accent | #78D900 | 主品牌绿 |
| Accent Alt | #7EDB22 | 视觉高亮 |
| Connected | #4CCB00 | 已连接 |
| Warning | #FFAA00 | 等待或警告 |
| Error | #EF4444 | 错误 |
| Border | #E5E7EB | 边框 |
| Field | #F3F4F6 | 输入和弱背景 |

### 22.2 字阶

| 用途 | 建议字号 |
| --- | --- |
| 页面标题 | 22sp |
| 主状态 | 20sp |
| 车友昵称 | 20–24sp |
| 卡片标题 | 16sp |
| 正文 | 14sp |
| 辅助文字 | 12–13sp |
| 按钮 | 14–16sp |

不得为了塞入小屏而将关键正文缩小到 12sp 以下。

### 22.3 间距和形状

- Compact 页面水平边距：16dp。
- Standard 页面水平边距：20dp。
- Wide 页面水平边距：24dp。
- 卡片间距：12dp。
- 大区块间距：16dp。
- 卡片圆角：16–20dp。
- 主按钮最小视觉直径：128dp。
- 普通点击区域最小：48dp。
- 边框：1dp。
- 阴影：低透明度轻阴影，不使用厚重悬浮效果。

### 22.4 图标

- 使用 Android VectorDrawable。
- 禁止继续使用 Emoji 或汉字模拟功能图标。
- 图标必须有 contentDescription。
- 装饰图标对 TalkBack 隐藏。
- 通用骑士头像可使用独立静态 PNG/WebP，但不得从整页截图直接裁切低质量页面元素。

### 22.5 动画

允许：

- 搜索雷达。
- 真实音量波形。
- 主连接按钮状态动画。
- 页面轻量淡入淡出。

禁止：

- 与真实状态无关的持续动画。
- 动画驱动产品状态。
- 系统动画关闭后仍强制运行。

## 23. 响应式与异形屏适配

### 23.1 支持范围

- 320dp 至 359dp：Compact。
- 360dp 至 479dp：Standard。
- 480dp 至 599dp：Wide Phone。
- 600dp 及以上：Expanded。

### 23.2 最大内容宽度

- 一级页面主要内容最大宽度：520dp。
- 超出后水平居中。
- 背景可铺满屏幕，卡片内容不得无限拉伸。

### 23.3 页面滚动

- Home、Discover、Settings、Logs 分别独立滚动。
- 禁止将四个页面放入同一个总 ScrollView。
- 禁止横向滚动。
- Compact 和 1.3x 字体允许纵向滚动。
- 标准屏尽量让 Home 核心按钮在首屏可达。

### 23.4 WindowInsets

必须处理：

- 状态栏。
- 导航栏。
- 手势导航底部区域。
- 刘海。
- 挖孔。
- 圆角安全区。

关键按钮和文字不得落入不可安全触达区域。

### 23.5 横屏和平板

- 不要求专用双栏设计。
- 必须无崩溃。
- 必须无水平裁切。
- 所有功能可通过纵向滚动到达。
- 内容保持 520dp 最大宽度并居中。

### 23.6 字体缩放

至少验证：

- 1.0x。
- 1.3x。

要求：

- 文字不互相覆盖。
- 按钮文字不截断。
- 卡片允许增高。
- 状态标签允许换行或重排。
- 不通过固定高度裁切内容。

## 24. 可访问性

- 所有操作控件至少 48dp。
- 状态不能只通过颜色表达，必须有文字。
- 当前页面、按钮状态和连接状态提供 TalkBack 文案。
- 可点击占位项说明“功能开发中”。
- 列表项焦点顺序为：头像说明、昵称、设备/通道、状态、连接按钮。
- 动态状态更新避免频繁抢占 TalkBack。
- 主按钮禁用时提供原因。
- 复制日志成功提供可访问反馈。
- 通用头像标记为装饰，除非未来接入真实头像语义。

## 25. 生命周期与性能

### 25.1 生命周期

- MainActivity 继续负责权限、Service 绑定和 UI controller 装配。
- onStop 时解除 UI listener 并停止页面动画。
- Service 在后台继续拥有连接与音频生命周期。
- 回到前台后重新绑定并渲染最新真实状态。
- UI 不保存或恢复伪造的 Product State。
- Service 重新绑定可以回放状态、Product State、音频来源、Presence 和非空远端昵称；不会回放此前日志和最近音量值，页面不得伪造缺失数据。

### 25.2 渲染

- Presence 列表按最新 Snapshot 更新。
- 页面不可见时不做不必要的 View 文本重绘。
- 日志不可见时只更新缓冲。
- 音量动画只在 Home 可见时刷新。
- 雷达动画只在 Discover 可见且 Discovering 时运行。

### 25.3 旋转和重建

- 保存 UI Route。
- 保存各页面滚动位置。
- 保存未提交的昵称编辑文本。
- 已保存昵称从持久层恢复。
- 真实产品状态从 Service 恢复。
- 当前有效 Incoming Confirmation 由 Service/Activity 重新发布或恢复，不使用旧 Dialog 引用。

## 26. 技术实现约束

### 26.1 推荐结构

    app/src/main/res/layout/
    ├── activity_main.xml
    ├── screen_home.xml
    ├── screen_discover_riders.xml
    ├── screen_settings.xml
    ├── screen_logs.xml
    ├── view_rider_presence.xml
    └── view_navigation_panel.xml

    app/src/main/res/values/
    ├── colors.xml
    ├── dimens.xml
    ├── strings.xml
    └── styles.xml

    app/src/main/res/values-sw600dp/
    └── dimens.xml

    app/src/main/res/drawable/
    ├── bg_card.xml
    ├── bg_primary_button.xml
    ├── bg_status_pill.xml
    └── vector icons...

### 26.2 Kotlin 职责

- MainActivity：权限、Service 绑定、真实 action 转发、Route 保存恢复。
- UI controller/MainScreen：View 引用、页面切换、状态渲染。
- Presence renderer/adapter：仅将 RiderPresence 映射到 View。
- Placeholder dialog helper：统一单实例对话框。
- Log page renderer：缓冲 Snapshot、可见时刷新、复制。

无需为单次用途引入新的架构框架。

### 26.3 禁止修改的底层范围

除非未来有单独授权和独立需求，本 UI 实现不得修改：

- <code>IntercomService.kt</code> 的连接、状态机和资源生命周期逻辑。
- <code>IntercomState.kt</code>。
- <code>IntercomStateMachine.kt</code>。
- <code>SessionOrchestrator.kt</code>。
- <code>ConnectionAttempt.kt</code>。
- <code>PresenceAggregator.kt</code>。
- <code>IncomingConfirmation.kt</code>。
- <code>AttemptDeadlineScheduler.kt</code>。
- <code>AttemptResourceController.kt</code>。
- <code>SessionGeneration.kt</code>。
- <code>SessionIdentifiers.kt</code>。
- <code>SignalingControl.kt</code>。
- <code>SignalingControlCoordinator.kt</code>。
- <code>SignalingProtocol.kt</code>。
- <code>SignalingProtocolV2.kt</code>。
- <code>SignalingSessionV2.kt</code>。
- <code>SignalingV2Types.kt</code>。
- <code>LanDiscoveryCoordinator.kt</code>。
- <code>LanRiderDevice.kt</code>。
- <code>WifiDirectTunnel.kt</code>。
- <code>WifiDirectGroupValidationGate.kt</code>。
- <code>WifiDirectPeerRegistry.kt</code>。
- <code>WifiDirectRiderDevice.kt</code>。
- <code>WifiDirectSignalingSocket.kt</code>。
- <code>IntercomManager.kt</code>。
- <code>RiderAudioEngine.kt</code>。
- <code>VoxGate.kt</code>。
- <code>AudioRouteController.kt</code>。
- <code>ModernAudioRoute.kt</code>。
- <code>LocalIdentityStore.kt</code>。
- <code>PairingDao.kt</code>。
- <code>PairingDatabase.kt</code>。
- <code>PairingRecord.kt</code>。
- <code>PairingRepository.kt</code>。
- <code>PermissionPolicy.kt</code>。
- <code>P2pServiceInstanceCodec.kt</code>。
- Gradle 依赖。

MainActivity 和 IntercomService.Listener 的既有 UI 接线可在不改变底层语义的前提下适配。
<code>RippleView.kt</code>、<code>VisualizerView.kt</code> 和 <code>BoundedLogBuffer.kt</code> 属于 UI helper，可在保持职责单一的前提下复用或调整。

## 27. 当前代码迁移要求

### 27.1 必须删除或替换

- 当前单一总 ScrollView。
- 动态堆叠的 Discovery、Settings 和 Logs 卡片。
- Emoji/汉字图标。
- <code>updateVoxDisplay(level)</code> 对 VOX 三态的推算。
- 使用 active/mediaActive 推断所有链路状态的标签逻辑。
- 隐藏页面 TextView 的持续日志刷新。

### 27.2 必须保留

- Start/Stop 真实回调。
- 权限判断。
- Service 绑定和解绑。
- onIntercomStateChanged。
- onStatusChanged。
- onAudioSourceChanged。
- onPresencesChanged。
- onAudioLevelChanged。
- onLog。
- onRemoteRiderIdentified。
- Incoming Confirmation。
- BoundedLogBuffer 300 条上限。

## 28. 错误和空态

| 场景 | UI 表现 | 可执行操作 |
| --- | --- | --- |
| 无核心权限 | 权限说明卡 | 授权/系统设置 |
| Service 未就绪 | 后台服务未就绪 | 等待或重试真实操作 |
| Wi-Fi 不可用 | Wi-Fi 不可用 | 打开系统 Wi-Fi 设置 |
| Discover 无 Presence | 暂未发现附近车友 | 等待；手动重扫仍为开发中 |
| Presence 过期 | 从列表移除 | 选择其他真实 Presence |
| Connecting peer 为空 | 正在确认目标车友 | 等待或结束对讲 |
| 连接失败返回 Discovering | 显示真实失败补充文案 | 重新选择车友 |
| 蓝牙权限缺失 | 蓝牙状态不可用 | 授予可选权限 |
| 日志为空 | 本次界面会话暂无日志 | 返回 |
| 昵称为空 | 请输入骑士昵称 | 编辑后保存 |
| 昵称保存失败 | 保留输入并显示错误 | 重试 |

## 29. 测试与验收

### 29.1 自动化门禁

在未来实现分支执行：

    $env:JAVA_HOME='F:\Android\jbr'
    .\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug --console=plain

如果 Unicode 路径导致全量测试 ClassNotFoundException，使用已有 M: 映射复核，不能直接判定为 UI 代码失败。

### 29.2 UI 逻辑测试

必须覆盖：

- UI Route 与 Product State 分离。
- 九个 IntercomState 的文案、按钮状态和 CTA 显隐。
- Stopping 禁止重复点击。
- Home、Discover、Settings、Logs Back 行为。
- Activity 重建 Route 恢复。
- 占位 Dialog 单实例。
- 占位点击无产品和设置副作用。
- Incoming Dialog 优先级和过期请求。
- Nickname 验证、保存和下次启动生效语义。
- Presence 过期和不可选择状态。
- Recovering 不允许替换目标。
- Logs 300 条截断和复制。

### 29.3 屏幕矩阵

至少验证：

| 配置 | 要求 |
| --- | --- |
| 320dp Compact | 所有功能可达，无横向裁切 |
| 360dp Standard | Home 核心操作首屏可达 |
| 393–412dp 细长屏 | 无大面积空白或底部遮挡 |
| 600dp Expanded | 内容最大宽度并居中 |
| 1.0x 字体 | 设计基准 |
| 1.3x 字体 | 无截断和重叠 |
| 手势导航 | 底部安全区正确 |
| 三键导航 | 内容不被导航栏遮挡 |
| 刘海/挖孔 | 顶部控件安全 |
| 圆角屏 | 关键触控区安全 |
| 横屏 | 可操作、无崩溃 |

### 29.4 真机回归

至少使用项目当前可用的两台真实 Android 设备验证：

- App 启动。
- 权限申请。
- Start/Stop。
- Discover 页面真实 Presence。
- 选择车友。
- 入站接受和拒绝。
- LAN / Wi-Fi Direct 状态展示。
- WebRTC Connected 展示。
- 蓝牙真实状态。
- 前后台切换。
- 锁屏与恢复。
- Activity 重建。
- Logs 查看和复制。
- 每个占位入口无副作用。

人工听音必须单独标记为人工证据；仅凭 ADB 音频状态不得宣称听音通过。

### 29.5 视觉截图

必须保存：

- Home 九种 Product State。
- Discover 搜索、空态、有车友、只读状态。
- Settings。
- Logs。
- Navigation Panel。
- Incoming Confirmation Dialog。
- Feature Placeholder Dialog。
- 320dp。
- 600dp。
- 1.3x 字体。
- 刘海/挖孔。

用于截图的测试数据必须属于测试/预览夹具，不得进入生产页面或被描述为真实车友。

## 30. 验收标准

### 30.1 功能

- [ ] Start/Stop 继续调用现有真实逻辑。
- [ ] Presence 连接继续调用现有真实逻辑。
- [ ] Incoming Accept/Reject 正常。
- [ ] Service 回调持续更新页面。
- [ ] 前后台和 Activity 重建后状态正确。
- [ ] UI 改造未改变底层产品状态、身份和媒体语义。

### 30.2 页面

- [ ] Home、Discover、Settings 三个一级页面独立。
- [ ] Logs 是 Settings 二级页面。
- [ ] 不存在全局长 ScrollView。
- [ ] 导航面板、齿轮、音频入口、CTA 和 Back 行为符合规格。
- [ ] 每页独立滚动。

### 30.3 数据真实性

- [ ] 无假昵称。
- [ ] 无假设备型号。
- [ ] 无假电量。
- [ ] 无假距离。
- [ ] 无假 VOX 状态。
- [ ] 无发现阶段身份认证声明。
- [ ] Transport、WebRTC 和蓝牙状态均来自真实数据。

### 30.4 占位功能

- [ ] 所有占位入口可点击。
- [ ] 统一 Dialog 内容正确。
- [ ] 同时只有一个占位 Dialog。
- [ ] 点击确定关闭。
- [ ] 不改变产品、导航和设置状态。

### 30.5 响应式和可访问性

- [ ] 320dp 至 600dp 可用。
- [ ] 1.3x 字体可用。
- [ ] 刘海、挖孔、圆角和系统栏安全。
- [ ] 无横向滚动。
- [ ] 无文字裁切。
- [ ] 无触控区域重叠。
- [ ] 最小触控区 48dp。
- [ ] TalkBack 文案完整。

### 30.6 质量门禁

- [ ] testDebugUnitTest 通过。
- [ ] lintDebug 通过。
- [ ] assembleDebug 通过。
- [ ] 两台真机核心流程通过。
- [ ] 屏幕矩阵截图完成。
- [ ] 视觉对照完成。
- [ ] 未执行项目明确标记为 Not Run，不伪造通过。

## 31. Definition of Done

满足以下全部条件才能认为本 PRD 的 UI 实现完成：

- [ ] 单 Activity + XML 页面落地。
- [ ] 未引入 Compose、Fragment 或新 UI 依赖。
- [ ] 三个一级页面和 Logs 二级页面全部可达。
- [ ] UI Route 与 IntercomState 完全分离。
- [ ] SessionOrchestrator 仍是唯一 Product State 写入者。
- [ ] 九个 IntercomState 按锁定文案渲染。
- [ ] Start/Stop 防重复点击。
- [ ] Discover 只显示真实 Presence。
- [ ] Recovering 不允许替换原目标。
- [ ] Current Socket 身份和发现身份文案边界正确。
- [ ] Incoming Confirmation 的接受、拒绝、取消、超时和过期请求通过验证。
- [ ] Nickname 保存规则全部满足。
- [ ] Logs 300 条、滚动、复制和说明全部满足。
- [ ] 所有占位能力只弹统一 Dialog。
- [ ] 当前 VOX 推算伪状态已移除。
- [ ] 当前链路标签推算已替换为真实来源。
- [ ] 无生产假数据。
- [ ] 响应式矩阵通过。
- [ ] 可访问性检查通过。
- [ ] 自动门禁通过。
- [ ] 两台真机回归通过。
- [ ] 视觉截图和设计稿对照通过。
- [ ] 改动范围未越过 UI 授权边界。

## 32. 推荐实施顺序

本节只定义依赖顺序，不定义排期。

1. 建立 UI Route 模型和 XML 页面容器。
2. 建立颜色、尺寸、样式和 VectorDrawable 资源。
3. 完成 Home 静态布局。
4. 接入九个 Product State 和真实通道标签。
5. 完成 Navigation Panel 和 Back 栈。
6. 完成 Discover Riders 与 Presence 渲染。
7. 完成 Settings 和昵称保存。
8. 完成 Logs 二级页。
9. 完成统一 Feature Placeholder Dialog。
10. 接入 Incoming Confirmation 优先级。
11. 完成 WindowInsets、响应式和可访问性。
12. 删除旧长页面和 VOX/链路伪状态。
13. 执行自动化、模拟器截图和真机回归。

## 33. 未来开发快速启动清单

未来开始实现时，开发者或 Codex 应先执行：

1. 阅读本 PRD。
2. 打开原始设计稿。
3. 确认实际 Git 根目录。
4. 确认当前分支、HEAD、上游和工作树。
5. 重新检查 MainActivity、MainScreen、IntercomState、ConnectionAttempt、RiderPresence 和 IntercomService.Listener。
6. 验证本 PRD 中列出的真实接口仍存在。
7. 如果接口发生变化，先更新 PRD 的能力矩阵，再写 UI。
8. 建立独立 UI 分支，不把未完成的协议或底层修改混入。
9. 保持后端文件 no-touch 边界。
10. 完成一页即验证一页，不等全部堆完后再做屏幕适配。

建议未来开发请求直接引用：

> 按 <code>docs/product/2026-07-17-motocom-ui-redesign-prd.md</code> 实现 MotoCom UI。先核对当前接口和 Git 状态；仅修改 UI 层；完成后执行 PRD 中的全量验收。

## 34. 风险与防护

| 风险 | 防护 |
| --- | --- |
| UI 再次退化成长页面 | 三个一级 XML 页面、独立滚动 |
| UI 成为第二个状态机 | Route 与 Product State 分离 |
| 发现信息被描述为认证 | 固定身份边界文案 |
| VOX 显示伪状态 | 精确状态保持占位 |
| Transport/WebRTC 标签假阳性 | 只绑定 Attempt 和 Connected |
| 占位功能误改底层 | 统一无副作用 Dialog |
| 小屏无法操作 | 320dp 验收、允许单页滚动 |
| 宽屏卡片过宽 | 最大内容宽度 520dp |
| 字体放大裁切 | 禁止固定高度、验证 1.3x |
| Dialog 堆叠或旧请求误操作 | 单实例和 Incoming 优先级 |
| 日志造成隐藏页面重绘 | 缓冲与 TextView 渲染分离 |
| UI 改造破坏底层 | no-touch 文件清单和全量回归 |

## 35. 最终产品原则

1. 真实状态优先于好看的状态。
2. 明确入口优先于隐藏点击区域。
3. 页面导航不能改变产品连接状态。
4. 发现不等于身份认证。
5. 恢复必须保留原目标。
6. 未实现功能可以被看见和点击，但必须诚实说明未完成。
7. 不用假数据填满设计稿。
8. 不以牺牲可访问性和小屏可用性换取截图级相似。
9. UI 重构不能顺带重写底层对讲架构。
10. 所有“完成”结论必须有自动化、截图和真机证据。
