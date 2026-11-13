# MotoCom iOS 跨平台建联、陌生配对与音频会话 PRD

## 0. 文档信息

| 项目 | 内容 |
| --- | --- |
| 产品 | 摩声 MotoCom |
| 文档类型 | iOS 跨平台网络建联、陌生配对与实时对讲产品需求文档 |
| PRD 版本 | v1.0 |
| 日期 | 2026-08-22 |
| 需求状态 | 方案已确认，进入实现 |
| 实现状态 | iOS 工程尚未创建；Android v1.1.0 已具备基础 LAN/Wi‑Fi Direct、V2 信令和 WebRTC 能力，跨平台网络引导尚未实现 |
| 当前 Android 工程 | `C:\Users\kuma\Documents\摩托对讲机\MotoIntercomApp-release-v1` |

> 本 PRD 把“建立临时网络”和“陌生配对”视为两个不同阶段。没有互联网时，设备仍可以使用 Wi‑Fi、Wi‑Fi Direct、Apple 点对点 Wi‑Fi 和 BLE；BLE 只用于发现和网络引导，不承载当前 WebRTC 对讲音频。

## 1. 背景与问题

MotoCom 面向真实骑行场景的一对一实时对讲。骑行地点通常没有现成路由器，也不要求互联网。两台手机需要先建立可互通的临时网络，再发现彼此、完成陌生配对并建立 WebRTC 音频。

当前 Android 工程已经具备：

- LAN Bonjour/NSD 发现和 UDP 发现兜底；
- Android↔Android Wi‑Fi Direct 通道；
- TCP 8890 控制信道和 V2 JSON framing；
- `HELLO`、`CONNECT_REQUEST`、`CONNECT_ACCEPT`、Offer/Answer/Candidate；
- `TargetLock`、运行时会话 ID、入站确认、恢复和配对记录；
- Android 音频焦点、电话抢占、蓝牙路由和 WebRTC 音频会话。

当前缺口：

1. 没有“无共同网络时”的跨平台网络准备状态。
2. 没有 iOS 客户端、Bonjour/TCP/WebRTC 对齐实现。
3. Android 发现信息没有显式声明平台和网络能力。
4. 当前连接成功状态需要进一步收紧为“双方实际音频就绪”。
5. iOS↔iOS 后续需要 Apple 点对点 Wi‑Fi，但不能把它作为 Android 兼容通道。

## 2. 产品目标

### 2.1 用户目标

- 用户无需互联网即可在户外建立一对一对讲。
- 尽量由 App 自动完成蓝牙发现、临时网络创建/加入和设备发现。
- 必须人工操作时，减少到系统热点或 Wi‑Fi 设置中的必要步骤。
- 陌生车友第一次连接时由接收方点击“接受”完成配对。
- 连接成功的用户体验定义为双方实际听到远端音频，而不是仅看到“连接中/已连接”。
- 有蓝牙 HFP 耳机时使用耳机；无蓝牙耳机时使用手机扬声器和内置麦克风。
- 对讲支持后台和锁屏运行；如果 iOS App 被系统终止，重新打开 App 后恢复连接。
- 电话优先于对讲；导航和音乐尽量共存，不承诺控制所有第三方 App 的音量。

### 2.2 工程目标

- 在不改变现有 V2 信令语义的前提下，增加跨平台网络准备层。
- 通过能力协商选择 LAN、Android Wi‑Fi Direct、Android 临时热点或 Apple P2P。
- iOS↔Android 和 iOS↔iOS 尽量复用相同的 TCP framing、信令和 WebRTC 媒体流程。
- 保持 Android `SessionOrchestrator` 为产品状态唯一写入者；网络准备协调器只产生事件和副作用。
- 建立 Android 真机↔iPhone 真机的可重复验收矩阵。

## 3. 范围、非目标与平台边界

### 3.1 本版本范围

- 一对一实时语音对讲。
- Android↔iPhone 无互联网连接。
- Android↔Android 继续使用现有 Wi‑Fi Direct。
- iOS↔iOS 后续支持 Apple 点对点 Wi‑Fi。
- BLE 发现、临时网络引导、Bonjour/直接 endpoint 发现。
- V2 TCP 信令、WebRTC Offer/Answer/Candidate、Opus 音频。
- 陌生设备确认、已配对设备自动连接、配对记录本地保存。
- 电话中断、音频路由变化、后台/锁屏、App 重启后的恢复。

### 3.2 非目标

- 不使用互联网、云端信令、TURN/SFU 或远程中继。
- 不实现群组对讲、多方会议或多人热点拓扑。
- 不承诺固定通信距离；距离由手机、热点、Wi‑Fi Direct、障碍物和蓝牙/无线硬件决定。
- 不把 BLE 作为实时音频媒体通道。
- 不实现二维码、PIN/SAS、证书或强身份认证。
- 不保证 App 能自动打开 iPhone 个人热点。
- 不修改用户已有的系统热点密码。
- 不保证所有第三方音乐和导航 App 都按要求降低音量。
- 不把 `shortCode` 当作密码或认证凭据。

### 3.3 平台边界

Apple 点对点 Wi‑Fi 的链路协议只适用于 Apple 设备之间，不能用于 iOS↔Android。iOS↔iOS 建议使用 Network.framework 的点对点能力，并继续承载 MotoCom 自有 TCP/信令协议；不把 MultipeerConnectivity 的内部会话协议作为 Android 互操作协议。

## 4. 已确认的产品决策

| 决策项 | 已确认行为 |
| --- | --- |
| 对讲规模 | 第一版一对一 |
| 互联网 | 必须完全不依赖互联网 |
| Wi‑Fi | 没有现成路由器，但手机 Wi‑Fi 可以打开或已打开 |
| 网络角色 | 任意平台理论上都可以创建或加入；自动化程度按平台能力决定 |
| Android↔Android | 继续使用现有 Wi‑Fi Direct |
| iOS↔iOS | 无共同 LAN 时自动选择 Apple 点对点 Wi‑Fi |
| Android↔iPhone | 优先使用 Android 可自动创建的临时热点；iPhone 主机必要时走系统个人热点设置 |
| BLE | 用于发现和网络引导；蓝牙权限被拒绝时阻止自动建联 |
| 陌生配对 | 接收方点击“接受”即完成本地配对；不加 PIN、二维码或 SAS |
| 自动连接 | 已配对设备允许按现有规则自动连接；首选车友和普通已配对车友继续区分 |
| 音频 | 全双工实时对讲；优先蓝牙 HFP，缺失时手机扬声器/内置麦克风 |
| 音频优先级 | 电话 > MotoCom 对讲 > 导航/音乐共存 |
| 后台 | 允许后台和锁屏；App 被 iOS 系统终止后，重新打开 App 恢复 |
| 稳定性 | 真机连续对讲至少 10 分钟，不出现未预期断线 |
| 成功定义 | 双方实际收到远端音频帧并由当前音频路由播放 |
| 热点密码 | 优先使用系统返回或用户已有热点配置；App 不修改用户热点；固定 `123456789` 仅作为受平台限制的手动/受控兜底，不作为所有系统的强制配置 |

## 5. 核心概念与分层

### 5.1 四个独立阶段

```text
网络准备 Network Bootstrap
    ↓
设备发现 Discovery
    ↓
陌生配对 Pairing / User Consent
    ↓
媒体连接与音频就绪 Audio Ready
```

- **网络准备**：让两台手机获得可以互通的 IP 或点对点网络路径。
- **设备发现**：交换设备 ID、运行时会话、平台、协议和能力。
- **陌生配对**：接收方明确接受一次未知连接请求。
- **音频就绪**：控制信道、WebRTC、音频路由和远端音频帧均可用。

### 5.2 网络能力不是身份认证

`platform`、系统版本、传输能力和热点能力只用于选择连接方式。它们都可能被伪造，不得用于证明对端身份。

当前版本继续使用“用户确认式信任”：对方点击“接受”后，连接成功时双方各自保存本地配对记录。强身份认证不属于本版本。

## 6. 连接方式选择矩阵

| 设备组合/网络状态 | 首选方式 | 失败回退 | 用户操作 |
| --- | --- | --- | --- |
| 两台设备已在同一 LAN | Bonjour/NSD + TCP 8890 | BLE 提供 endpoint 后直接 TCP | 无额外操作 |
| Android API 26+ ↔ iPhone，无共同 LAN | Android `LocalOnlyHotspot` + iPhone 加入 | 手动热点/系统 Wi‑Fi 设置 | 尽量自动；系统确认不可绕过 |
| Android API 23–25 ↔ iPhone | 手动热点 | 重新选择可建网 Android 或结束 | 需要系统 Wi‑Fi 操作 |
| Android ↔ Android | 现有 Wi‑Fi Direct | LAN/手动热点 | 复用当前流程 |
| iOS ↔ iOS，无共同 LAN | Apple P2P Wi‑Fi（Network.framework） | 同一 LAN/热点 | 自动选择，不显示底层传输细节 |
| iPhone 主机 ↔ Android | iPhone 个人热点 | Android 主机临时热点 | iPhone 主机需要系统个人热点操作 |
| 无可用网络路径 | 不建立会话 | 显示失败原因和可行的设置路径 | 用户修复 Wi‑Fi/BLE/权限后重试 |

### 6.1 自动选择规则

1. 先检查是否已经存在共同 LAN；存在时不创建新热点。
2. iOS↔iOS 且没有共同 LAN 时，选择 Apple P2P。
3. Android↔Android 继续走 Wi‑Fi Direct，不改现有默认行为。
4. Android↔iPhone 时，如果 Android API≥26 且 Local-only Hotspot 可用，Android 自动作为主机。
5. 如果用户明确选择 iPhone 作为主机，允许 iPhone 手动打开个人热点，Android 加入。
6. Android API<26、OEM 拒绝热点或热点建立失败时，进入最短的系统手动设置路径。
7. 若双方能力相同且都请求主机角色，使用用户当前操作优先；没有用户操作时使用稳定 `deviceId` 的确定性排序，避免双方同时建网。

“任意平台可创建或加入”表示协议支持两种网络角色；不表示每个平台都能由 App 自动打开系统热点。

## 7. BLE 网络引导协议

### 7.1 作用

BLE 只负责：

- 发现附近运行 MotoCom 的设备；
- 交换平台和能力；
- 传递 App 创建的临时热点凭据；
- 传递临时 session、主机 IP 和 TCP 端口；
- 在 Bonjour 不可用时提供直接 endpoint 备用。

BLE 不负责：

- WebRTC 音频；
- 长时间维持对讲会话；
- 保存用户系统热点密码；
- 认证陌生设备身份。

### 7.2 广播字段

建议使用统一的 App-specific BLE Service UUID，应用层字段为：

```json
{
  "app": "motocom",
  "protocolVersion": 2,
  "platform": "ANDROID|IOS",
  "deviceId": "<stable UUID>",
  "sessionId": "<runtime UUID>",
  "capabilities": [
    "BLE_BOOTSTRAP",
    "LAN",
    "ANDROID_LOCAL_ONLY_HOTSPOT_HOST",
    "WIFI_JOIN",
    "ANDROID_WIFI_DIRECT",
    "IOS_PEER_TO_PEER"
  ],
  "networkRole": "HOST|JOINER|EITHER",
  "tcpPort": 8890
}
```

字段要求：

- `platform` 只用于连接方式选择。
- `deviceId` 稳定，`sessionId` 每次运行变化。
- 能力字段表示真实运行时能力，不根据手机型号静态猜测。
- Android 运行时使用 `Build.VERSION.SDK_INT` 判定 Local-only Hotspot 能力。
- iOS 运行时声明可用的 LAN、Wi‑Fi join、Apple P2P 和 BLE 能力。
- 热点 SSID/密码只在网络准备事务期间传递，内存使用后清理，不写入 `PairingRecord`。

### 7.3 权限行为

- BLE 权限被拒绝时，自动网络准备流程直接阻止，不偷偷降级为不可控的盲连。
- UI 必须说明需要蓝牙用于“发现附近车友和完成网络接入”，并提供进入系统设置的路径。
- 用户恢复权限后可以重新启动网络准备。

## 8. 热点与密码策略

### 8.1 Android App 创建临时热点

Android API 26+ 使用 `LocalOnlyHotspot`。系统返回的 `SSID`、安全类型和凭据作为权威值，由主机 App 通过 BLE 发送给 iPhone。

要求：

- 不修改用户已有个人热点配置。
- 会话结束时关闭本次 App 创建的热点 reservation。
- 用户停止对讲时，热点关闭与信令/音频资源释放必须有明确顺序。
- Android API 26–35 不承诺 App 可以强制指定 `123456789`；使用系统返回凭据。
- Android API 36+ 可评估自定义 Local-only 配置，但不改变低版本兼容策略。

### 8.2 用户已有热点

App 不假定能读取用户在系统设置中配置的个人热点密码。

- iPhone 主机：用户在系统设置/控制中心打开个人热点，Android 通过系统 Wi‑Fi 加入。
- Android 主机：如果使用已有系统热点而非 App 创建的 Local-only Hotspot，按系统可见信息和用户操作加入。
- App 不修改、不覆盖、不恢复写入用户原有热点密码。
- 如果系统要求输入密码，输入发生在系统 Wi‑Fi 流程中，不能把它伪装成 App 已自动完成。

### 8.3 固定默认密码

`123456789` 只允许作为以下兜底：

- 用户明确选择手动配置 MotoCom 热点；或
- 主机明确声明“无密码且由 MotoCom 管理”的受控网络。

不允许因为 App 假设密码为空，就覆盖或修改用户系统热点设置。

## 9. LAN 发现与信令兼容

### 9.1 LAN 发现

跨平台 LAN 路径统一使用：

- Bonjour service type：`_motocom._tcp.`；
- TCP 控制端口：`8890`；
- TXT/发现信息：`id`、`sessionId`、`name`、`deviceName`、`protocolVersion`、`platform`、`capabilities`；
- TCP framing：4 字节大端长度前缀 + UTF-8 JSON；
- 最大帧、SDP、Candidate 和 UUID 约束继续遵守现有 V2 规则。

iOS 需要声明 `NSLocalNetworkUsageDescription` 和 `NSBonjourServices`。iOS UDP broadcast/组播不作为跨平台主发现路径；Android 当前 UDP broadcast 可保留为 Android 兼容兜底，但 iOS 主路径使用 Bonjour 和 BLE endpoint。

### 9.2 V2 协议策略

- 不在当前 V2 `HELLO` payload 中强行新增未知字段，避免破坏现有严格解析。
- `platform` 和网络能力优先放在 BLE/Bonjour 发现数据中。
- `HELLO.capabilities` 继续承载能力集合。
- iOS↔iOS P2P 的内部传输类型需要在后续兼容协议版本或本地 transport adapter 中定义 `IOS_PEER_TO_PEER`；不得让旧 Android V2 客户端误解析该值。
- iOS↔Android 第一阶段继续以 `LAN` 作为 V2 信令 transport，媒体仍使用当前 host ICE 配置。

### 9.3 陌生设备连接

```text
发现未配对设备
→ 发起 CONNECT_REQUEST
→ 对端显示 INCOMING_CONFIRMATION
→ 接收方点击接受
→ CONNECT_ACCEPT
→ WebRTC Offer/Answer/Candidate
→ Audio Ready
→ 双方本地保存 PairingRecord
```

接收确认继续使用当前约 15 秒超时和现有拒绝/忙碌规则。网络准备完成不等于配对完成；陌生设备不能因为 BLE 或 Bonjour 被发现就自动接受。

## 10. 配对记录与自动连接

### 10.1 保存时机

只有以下条件全部满足时才保存配对记录：

- 对端 `deviceId` 和运行时 session 通过现有 `TargetLock`/socket identity 校验；
- WebRTC 媒体会话有效；
- 当前音频路由已就绪；
- 本地已收到远端音频帧；
- 用户没有在此期间取消连接。

这与现有 Android `SessionOrchestrator` 的“Connected 后再保存”原则一致，但需要把 Connected 的产品语义收紧为 Audio Ready。

### 10.2 本地数据

继续保存：

- 远端稳定 `deviceId`；
- 昵称和设备名；
- 首次配对时间；
- 最近连接时间；
- 最近使用的 transport；
- 本地别名、首选状态和失败计数。

`shortCode` 只作为显示标识，不作为密码或认证凭据。强认证密钥不属于本版本；Keychain/Keystore 只按现有规则保留未来扩展空间，不因本 PRD新增一套认证流程。

### 10.3 自动连接

- 已配对设备允许自动连接。
- 新发现的陌生设备必须走确认。
- 新配对成功后不自动修改首选车友状态，沿用现有 `isPreferred` 规则。
- 任一侧忘记配对记录后，下一次按陌生设备处理。

## 11. 音频会话与路由

### 11.1 音频成功条件

内部可以区分 `MEDIA_CONNECTED` 和 `AUDIO_READY`，但用户界面只在 `AUDIO_READY` 后显示“已连接/对讲中”。

`AUDIO_READY` 必须满足：

1. 控制信道处于有效状态；
2. WebRTC PeerConnection 已建立媒体路径；
3. 本地录音已打开；
4. 本地播放已打开；
5. 当前输出/输入路由已验证；
6. 已收到远端音频帧并交给音频输出链路。

不要用 TCP 建连、`CONNECT_ACCEPT` 或 ICE connected 单独伪造 Audio Ready。

### 11.2 iOS 路由

默认策略：

- 蓝牙 HFP 耳机存在：耳机输入和输出；
- 没有蓝牙耳机：手机扬声器输出、手机内置麦克风输入；
- 仅有 A2DP 播放能力但没有通信输入能力时，不把它当作完整对讲耳机；
- 路由断开时进入恢复状态，不把用户配对记录删除。

iOS 使用 `AVAudioSession` 的 `playAndRecord`/`voiceChat` 语义，并监听 route change、interruption 和 media services reset。

### 11.3 电话、音乐和导航

```text
蜂窝电话 RINGING/OFFHOOK > MotoCom 对讲 > 音乐/导航共存
```

- 电话期间暂停 MotoCom 录音和播放，保留连接和恢复意图。
- 电话结束后，在电话状态空闲、路由和焦点可用时恢复。
- 导航和音乐只要求“尽量共存”；不保证第三方 App 一定 duck。
- iOS/Android 各自使用平台音频会话策略，不跨平台伪造全局音量控制。

## 12. 状态机与生命周期

### 12.1 产品状态

在现有 Android 状态机和 iOS 对应状态中增加网络准备语义：

```text
OFFLINE
  ↓
NETWORK_PREPARING
  ↓
DISCOVERING
  ↓
INCOMING_CONFIRMATION / CONNECTING
  ↓
MEDIA_CONNECTED
  ↓
AUDIO_READY（对外显示 CONNECTED）
  ↓
RECOVERING（网络、音频、电话或后台恢复）
```

所有状态转换必须串行化。Android 继续由 `SessionOrchestrator` 单一写入产品状态；`NetworkBootstrapCoordinator` 不直接写 UI 状态，只发出事件和执行平台资源副作用。

### 12.2 网络变化

如果设备切换到不同 Wi‑Fi、热点停止或 Wi‑Fi Direct/P2P 被系统关闭：

1. 记录网络断开原因；
2. 释放失效的 LAN endpoint；
3. 保留用户对讲意图和配对记录；
4. 重新进入网络准备和发现；
5. 按现有恢复规则重新连接；
6. 新的音频帧到达后重新发布 `AUDIO_READY`。

无互联网时不尝试云端恢复。

### 12.3 后台和终止

- 对讲期间允许后台和锁屏继续运行，前提是系统允许音频后台能力。
- 用户主动停止后，迟到的后台/网络回调不得重启对讲。
- iOS App 被系统终止后，重新打开 App 读取本地配对记录并恢复网络准备；不承诺被终止期间保持连接。
- Android Service/前台服务继续承担运行时资源生命周期；iOS 采用对应的音频后台能力和前台生命周期管理。

## 13. iOS 功能需求

### FR-IOS-01：iOS 工程与运行时能力

1. 创建独立 iOS 工程；当前仓库没有现成 iOS 工程，不能假设存在 SwiftUI/Xcode 代码。
2. 第一阶段最低支持 iOS 16.0，重点真机为 iPhone X/iOS 16.7.x。
3. 运行时广播平台、协议版本和能力，不仅广播机型名称。
4. 统一生成稳定 `deviceId` 和每次运行的 `sessionId`。

### FR-IOS-02：BLE Bootstrap

1. 使用统一 MotoCom BLE service UUID。
2. 发现附近设备后显示平台、昵称、设备名和网络准备状态。
3. 接收 Android App 创建的热点凭据和临时 endpoint。
4. BLE 被拒绝时阻止自动连接并显示设置引导。
5. BLE 连接关闭后，媒体连接不得依赖 BLE 保活。

### FR-IOS-03：LAN Bonjour/TCP

1. 使用 Network.framework `NWBrowser`/`NWListener`/`NWConnection` 或等价官方网络 API。
2. 浏览和发布 `_motocom._tcp.`。
3. 对齐 Android TCP 8890 和现有 V2 framing。
4. 处理本地网络权限提示、拒绝、恢复和网络切换。
5. Bonjour 失败时尝试 BLE 传递的临时 endpoint。

### FR-IOS-04：iOS↔iOS P2P

1. 作为 P2 阶段传输 adapter 实现，不改变用户界面和配对语义。
2. 使用 Network.framework 的 Apple peer-to-peer 能力；Apple P2P 只在 iOS↔iOS 路径启用。
3. 保留相同的 TCP framing、V2 信令语义和 WebRTC Offer/Answer/Candidate。
4. P2P 不可用时按能力矩阵回退到共同 LAN 或热点。
5. P2P 连接不绕过陌生设备确认。

### FR-IOS-05：iOS 音频

1. 使用 `playAndRecord` 和 `voiceChat` 语义建立双向语音会话。
2. 优先使用蓝牙 HFP；无耳机时回退内置麦克风和扬声器。
3. 监听电话、音频中断、路由变更、蓝牙断开和系统音频服务重置。
4. 首次远端音频帧到达前，不发布对外“已连接”。
5. 电话结束后，仅在用户仍保持对讲意图时恢复。

## 14. Android 协调改造需求

### FR-AND-01：网络准备协调器

新增 `NetworkBootstrapCoordinator` 或等价职责组件，负责：

- 检查现有共同 LAN；
- 读取运行时 Android API 和网络能力；
- API≥26 时请求 Local-only Hotspot；
- 获取系统返回的 SSID/安全类型/凭据；
- 通过 BLE 发送临时网络信息；
- API<26 或失败时进入手动热点/Wi‑Fi Direct 回退；
- 会话结束时关闭 App 创建的 reservation；
- 监听热点停止、网络切换和客户端隔离失败。

### FR-AND-02：发现能力扩展

在 `LanRiderDevice`、`RiderPresence` 和 BLE bootstrap model 中增加平台/能力字段；保持旧 V2 Envelope 的严格兼容，不直接向旧 `HELLO` 注入未声明字段。

### FR-AND-03：音频就绪语义

将真实远端音频帧和当前音频路由作为连接成功的必要条件。现有 `SessionOrchestrator` 的配对保存逻辑继续保留，但保存前需要满足 `AUDIO_READY`。

## 15. 权限与兼容性

### 15.1 Android

- 当前工程 `minSdk=23`、`targetSdk=36`。
- API 23–25：不能使用 Local-only Hotspot 自动主机能力；支持手动热点/Wi‑Fi Direct 回退。
- API 26+：尝试 Local-only Hotspot；需要对应附近 Wi‑Fi 权限。
- Android 12+：申请 `NEARBY_WIFI_DEVICES`；旧版本按平台要求处理位置/Wi‑Fi 权限。
- 继续使用麦克风、蓝牙、电话状态和前台服务相关权限。

### 15.2 iOS

- 最低产品目标暂定 iOS 16.0；iPhone X/iOS 16.7.x 为重点测试组合。
- `NSLocalNetworkUsageDescription`：说明发现和连接附近车友。
- `NSBonjourServices`：声明 `_motocom._tcp.`。
- 麦克风权限：说明实时骑行对讲用途。
- 蓝牙权限：说明发现附近设备和完成临时网络引导。
- 后台音频能力：用于锁屏/后台对讲；系统终止不承诺持续连接。
- 网络扩展/热点配置能力仅用于加入已知 Wi‑Fi，不将其描述为自动开启个人热点。

## 16. 用户流程与文案

### 16.1 自动网络准备

```text
点击开始对讲
→ 检查 Wi‑Fi/BLE/麦克风权限
→ 检查是否已有共同网络
→ 选择 LAN / Wi‑Fi Direct / iOS P2P / 临时热点
→ 显示“正在准备骑行网络”
→ 等待另一台设备加入
→ 进入发现车友
→ 陌生设备确认
→ 显示“正在建立音频”
→ 首帧远端音频到达
→ 显示“对讲已连接”
```

### 16.2 必须人工操作时

文案必须说明具体动作，不得显示泛化的“连接失败”：

- “请打开 iPhone 个人热点，然后返回 MotoCom。”
- “请在 Wi‑Fi 设置中加入骑行网络。”
- “当前 Android 版本不支持 App 自动创建临时热点。”
- “蓝牙权限被拒绝，无法发现附近车友。”
- “热点禁止设备互通，请更换主机或关闭客户端隔离。”

### 16.3 平台展示

设备卡片可以显示 Android/iPhone 平台图标和当前连接方式，但平台图标只用于识别和诊断，不显示为安全认证标识。

## 17. 验收标准

### 17.1 自动化验收

- BLE 广播/解析覆盖 platform、sessionId、能力集合和版本不兼容。
- API 版本路由覆盖 API 23–25、26+。
- 网络策略覆盖共同 LAN、Android host、iPhone host、Android Wi‑Fi Direct、iOS P2P。
- 旧 session、旧 endpoint、错误 target、错误 transport 的事件不能污染当前连接。
- `CONNECTED` 只在 `AUDIO_READY` 后发布。
- PairingRecord 只在 Audio Ready 后保存。
- 用户停止、权限拒绝、热点停止、App 重启后的迟到回调不会恢复已取消会话。
- 固定密码逻辑不得修改用户系统热点配置。

### 17.2 真机验收矩阵

| 编号 | 组合 | 前置条件 | 预期 |
| --- | --- | --- | --- |
| T1 | Android API 28 ↔ iPhone X/iOS 16 | Android 无共同 LAN | Android 自动创建临时热点，iPhone 加入，进入 Audio Ready |
| T2 | Android API 25 ↔ iPhone X/iOS 16 | Android 无共同 LAN | 自动热点能力被拒绝，进入最短手动设置路径 |
| T3 | iPhone X ↔ Android | iPhone 主机 | iPhone 系统打开个人热点，Android 加入，不修改密码 |
| T4 | Android ↔ Android | 无共同 LAN | 保持现有 Wi‑Fi Direct 流程 |
| T5 | iPhone ↔ iPhone | 无共同 LAN | 自动使用 Apple P2P；复用信令和 WebRTC |
| T6 | Android ↔ iPhone | 蓝牙权限拒绝 | 阻止网络准备，显示权限引导 |
| T7 | Android ↔ iPhone | 热点创建失败/客户端隔离 | 显示可执行回退，不伪造 Connected |
| T8 | 任意组合 | 连接后切换网络/关闭热点 | 进入 Recovering；重新建网后恢复 |
| T9 | 任意组合 | 无蓝牙耳机 | 手机扬声器输出、内置麦克风输入 |
| T10 | 任意组合 | 蓝牙 HFP 耳机 | 耳机输入输出；断开后回退手机路由 |
| T11 | 任意组合 | 电话响铃、接听、结束 | 电话期间暂停对讲；结束后恢复 |
| T12 | 任意组合 | 锁屏/后台 | 持续对讲；系统终止后重新打开可恢复 |

### 17.3 硬性通过条件

- 第一台设备点击接受后，双方最终都收到远端音频。
- 连接成功定义为实际音频就绪，不允许“界面已连接但无声”。
- 真实设备连续运行 10 分钟无未预期断线。
- 电话期间不录音、不播放 MotoCom 音频、不抢占电话路由。
- 蓝牙耳机和无耳机两种路由都能建立双向音频。
- 网络切换或热点停止后，不删除配对记录；恢复成功后重新进入 Audio Ready。
- 结束会话不修改用户原有热点密码。
- Android↔Android 现有 Wi‑Fi Direct 回归不能被跨平台改造破坏。

## 18. 诊断与日志

记录不包含音频内容和密码的诊断事件：

```text
bootstrap_started
bootstrap_capability_received
bootstrap_path_selected(path, platform, apiLevel)
hotspot_started / hotspot_failed(reason)
wifi_join_requested / wifi_joined / wifi_join_failed(reason)
bonjour_started / bonjour_found / bonjour_failed(reason)
ble_endpoint_used
transport_connected / transport_lost
incoming_confirmation(shown|accepted|rejected|expired)
webrtc_media_connected
audio_route_ready / audio_route_lost
audio_ready / audio_not_ready(reason)
pairing_saved
recovery_started(reason) / recovery_succeeded / recovery_failed
```

日志不得记录：

- Wi‑Fi 密码；
- 音频内容、录音数据或远端语音；
- 电话内容、联系人和第三方 App 私有信息。

## 19. 风险与回退策略

| 风险 | 影响 | 回退 |
| --- | --- | --- |
| Android API<26 | 无法自动创建 Local-only Hotspot | 手动热点或 Android↔Android Wi‑Fi Direct |
| OEM 禁止热点 | 无法建立临时 LAN | 另一台设备作为主机或系统手动设置 |
| iPhone 无法自动打开个人热点 | iPhone 主机需要用户操作 | 系统个人热点引导 |
| App 不能读取已有热点密码 | 无法自动提交凭据 | 使用系统 Wi‑Fi 加入流程 |
| 热点客户端隔离 | TCP/Bonjour 不通 | 更换热点主机或关闭隔离 |
| Bonjour 被过滤 | 发现失败 | BLE endpoint 直接连接 |
| 蓝牙权限拒绝 | 无法可靠完成网络引导 | 阻止自动连接并进入设置引导 |
| iOS 后台被系统终止 | 实时连接丢失 | 重新打开 App 后读取配对记录并恢复 |
| 音频路由切换 | 连接存在但无声 | 重新激活 AVAudioSession/Android AudioSession，等待 Audio Ready |
| 第三方 App 不支持 duck | 音乐/导航未降低 | 继续对讲，记录诊断，不承诺强制音量 |
| 设备电量/温度限制 | 10 分钟以上稳定性下降 | 真机长时测试和明确电量/温度诊断 |

## 20. 实施拆分

### P0：Android↔iPhone LAN/临时网络闭环

- 创建 iOS 工程和基础权限声明。
- iOS BLE discovery/bootstrap。
- Android API 26+ Local-only Hotspot 协调器。
- iPhone 加入 Android 临时网络的最短流程。
- iOS Bonjour/TCP 8890/V2 framing。
- iOS WebRTC Offer/Answer/Candidate 和 Opus 音频。
- iOS 音频路由：HFP、手机扬声器和内置麦克风。
- 陌生确认、Audio Ready、PairingRecord 对齐。
- Android API 28/iPhone X 真机首次闭环。

### P1：手动回退、低版本和恢复

- Android API 23–25 版本判断和手动热点回退。
- iPhone 作为主机的系统个人热点引导。
- BLE endpoint 作为 Bonjour 备用。
- 热点停止、网络切换、客户端隔离检测和自动恢复。
- 后台、锁屏、电话抢占和蓝牙断开/重连。
- 小米 6 Android 7.x/9 双版本真机测试。

### P2：iOS↔iOS Apple P2P

- Network.framework Apple P2P transport adapter。
- iOS P2P endpoint/Bonjour 发现。
- P2P 与 LAN/热点自动选择和回退。
- 复用现有 TCP framing、V2 信令和 WebRTC。
- iPhone X↔iPhone X 真机 Audio Ready 和 10 分钟稳定性验收。

### P3：兼容性与体验

- 更多 Android OEM 热点行为矩阵。
- iOS/Android 权限拒绝和恢复体验。
- 网络准备页面、平台图标、诊断日志和错误文案。
- 电量、热量、锁屏、弱信号和长时间对讲测试。

## 21. 成功指标

- Android API 28 ↔ iPhone X 在无互联网、无现成路由器条件下完成真机对讲。
- 从网络准备成功到 Audio Ready，不出现“连接成功但无声”。
- 首次配对后，双方本地保存记录，后续已配对设备按现有规则自动连接。
- 每个支持路径至少通过一次 10 分钟真机连续对讲。
- Android↔Android Wi‑Fi Direct 现有功能不回归。
- iOS↔iOS P2P 后续实现不改变 Android↔iPhone 的 LAN/热点协议。

## 22. Definition of Done

- [ ] iOS 工程可编译并运行在 iPhone X/iOS 16.7.x。
- [ ] Android API 26+ 临时热点路径已真机验证。
- [ ] Android API 23–25 回退行为明确并通过真机验证。
- [ ] iPhone 主机手动热点路径可用，且不修改用户热点密码。
- [ ] BLE 权限、网络权限、麦克风权限和后台音频声明完整。
- [ ] iOS LAN Bonjour/TCP/V2/WebRTC 闭环完成。
- [ ] 陌生设备确认和 Audio Ready 保存配对记录完成。
- [ ] 蓝牙 HFP 和手机扬声器/内置麦克风两种音频路由完成。
- [ ] 电话抢占、恢复、后台、锁屏和 App 重启恢复完成。
- [ ] T1–T12 真机矩阵完成并保留日志。
- [ ] 任一失败路径不会显示伪成功状态。

## 23. 参考资料与当前工程

### 23.1 官方平台资料

- [Android Local-only Hotspot](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot)
- [Android `WifiManager.LocalOnlyHotspotCallback`](https://developer.android.com/reference/android/net/wifi/WifiManager.LocalOnlyHotspotCallback)
- [Android `WifiManager`](https://developer.android.com/reference/android/net/wifi/WifiManager)
- [Apple TN3151：Choosing the right networking API](https://developer.apple.com/documentation/technotes/tn3151-choosing-the-right-networking-api)
- [Apple `NWBrowser`](https://developer.apple.com/documentation/network/nwbrowser)
- [Apple `NEHotspotConfigurationManager`](https://developer.apple.com/documentation/networkextension/nehotspotconfigurationmanager)
- [Apple `AVAudioSession` voiceChat](https://developer.apple.com/documentation/avfaudio/avaudiosession/mode-swift.struct/voicechat)
- [Apple iPhone X 技术规格](https://support.apple.com/en-lamr/111864)
- [Apple 安全更新记录](https://support.apple.com/en-mide/100100)

### 23.2 当前工程资料

- `docs/releases/v1.1.0.md`
- `docs/product/2026-08-13-motocom-multi-audio-intercom-prd.md`
- `app/src/main/java/com/kuma/motointercom/LanDiscoveryCoordinator.kt`
- `app/src/main/java/com/kuma/motointercom/SignalingProtocolV2.kt`
- `app/src/main/java/com/kuma/motointercom/SessionOrchestrator.kt`
- `app/src/main/java/com/kuma/motointercom/PairingRecord.kt`

## 24. 发布确认

最终产品验收和发布确认人：需求方本人。
