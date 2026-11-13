# MotoCom iOS 跨平台互联开发文档

## 0. 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档类型 | iOS 端跨平台互联技术开发文档 |
| 依据 | [iOS 跨平台建联、陌生配对与音频会话 PRD](../product/2026-08-22-motocom-ios-cross-platform-pairing-prd.md) |
| 当前仓库状态 | 没有现成 iOS 工程、Swift 源码、Xcode project 或 iOS CI |
| 第一阶段最低版本 | iOS 16.0 |
| 重点真机 | iPhone X，优先验证 iOS 16.7.x |
| 第一版范围 | 一对一、无互联网、Android↔iPhone；iOS↔iOS Apple P2P 作为后续阶段 |

## 1. iOS 端目标

iOS 端需要新建独立 App 工程，但不需要重新设计产品连接语义。它必须与 Android 共享以下规则：

- BLE 用于发现和网络 bootstrap，不承载音频；
- 无互联网时完成陌生设备发现、接受和配对；
- Android API≥26 主机时，接收 Android Local-only Hotspot 凭据并加入；
- iPhone 主机时，用户通过系统个人热点流程准备网络，App 不假装可以替用户打开或读取系统热点密码；
- LAN 信令使用 `_motocom._tcp.`、TCP `8890`、4 字节大端长度前缀 + UTF-8 JSON；
- 复用 Signaling V2 和 WebRTC Offer/Answer/Candidate；
- 只有远端音频真正到达且本地音频路由就绪后，才显示连接成功和保存配对；
- iOS↔iOS Apple 点对点 Wi‑Fi 放在独立 transport adapter，不能破坏 Android↔iPhone LAN/热点路径。

## 2. 工程与模块结构

建议创建 Swift Package/模块边界如下，首版不需要复杂插件化：

| 模块 | 职责 |
| --- | --- |
| `App` | SwiftUI 入口、权限引导、前后台生命周期 |
| `SessionOrchestrator` | 唯一产品状态写入者、连接流程和恢复 |
| `PlatformCapabilityProvider` | iOS 版本、BLE、LAN、Wi‑Fi join、Apple P2P 能力 |
| `BLEBootstrapCoordinator` | CoreBluetooth 扫描、广播、GATT 消息和临时凭据 |
| `NetworkBootstrapCoordinator` | LAN、Android 热点加入、iPhone 手动热点回退、P2P 选择 |
| `BonjourTransport` | `NWBrowser`、`NWListener`、`NWConnection` 和 TCP framing |
| `ApplePeerToPeerTransport` | iOS↔iOS 的 `includePeerToPeer` 传输 adapter，P2 阶段实现 |
| `SignalingV2Codec` | 与 Android 严格一致的 JSON envelope 和 message codec |
| `WebRTCSession` | PeerConnection、音频轨、SDP、ICE |
| `AudioSessionController` | AVAudioSession、HFP/扬声器、电话中断、路由变化 |
| `PairingStore` | PairingRecord 等价数据、preferred 状态、失败次数 |

推荐 SwiftUI 负责 UI，Swift Concurrency 负责异步流程；系统回调进入 actor/串行队列后再改变状态，避免 BLE、网络、音频回调互相覆盖。

## 3. 稳定身份与运行时能力

### 3.1 身份

- `deviceId`：首次启动生成稳定 UUID，保存在 Keychain 或受保护的本地存储；卸载重装是否改变属于后续产品决策；
- `sessionId`：每次 App 运行生成新的 UUID；
- `deviceName`：系统设备名或 App 展示名，不能作为稳定身份；
- `shortCode`：仅用于显示/定位，不用于认证，不做 PIN/QR/SAS。

### 3.2 能力集合

iOS 必须广播真实运行时能力，例如：

```text
BLE_BOOTSTRAP
LAN
WIFI_JOIN
IOS_PEER_TO_PEER
IOS_PERSONAL_HOTSPOT_MANUAL
```

能力不能只根据 iPhone 型号推断；需要同时检查系统版本、权限、网络状态和当前系统回调。平台字段只用于选择连接方式，不是安全认证。

## 4. BLE Bootstrap

### 4.1 角色

使用 CoreBluetooth 实现统一 MotoCom BLE Service：

- `CBCentralManager` 扫描 Android/iOS MotoCom 广播；
- `CBPeripheralManager` 广播本机平台、身份和能力；
- GATT characteristic 传输小型 JSON bootstrap 消息；
- 对大消息采用分片、序号、长度和校验，不能假定一次 characteristic 写入即可承载所有字段；
- BLE 连接关闭后，TCP/WebRTC 不依赖 BLE 保活。

消息类型与 Android 对齐：`BOOTSTRAP_REQUEST`、`BOOTSTRAP_CAPABILITIES`、`HOTSPOT_READY`、`WIFI_JOIN_RESULT`、`ENDPOINT_READY`、`BOOTSTRAP_CANCEL`。

### 4.2 权限行为

在 Info.plist 提供 `NSBluetoothAlwaysUsageDescription`，文案说明“用于发现附近车友并完成离线网络接入”。

蓝牙权限被拒绝或状态不可用时：

1. 自动网络准备停止；
2. UI 显示权限原因和进入系统设置操作；
3. 不降级为不可控的盲目扫描或直接显示 Connected；
4. 用户恢复权限后重新开始 bootstrap。

BLE 只传递本次网络事务的 SSID、认证类型、密码和临时 endpoint；iOS 不把 Android 热点密码写入配对记录。

## 5. LAN Bonjour/TCP

### 5.1 服务发布与发现

使用 Network.framework：

- `NWListener` 发布 `_motocom._tcp.`；
- `NWBrowser` 浏览 `_motocom._tcp.`；
- `NWConnection` 连接已解析的 endpoint；
- 服务 TXT/metadata 与 Android 对齐：`id`、`sessionId`、`name`、`deviceName`、`protocolVersion`、`platform`、`capabilities`；
- TCP 控制端口默认 `8890`；
- iOS 主路径使用 Bonjour + BLE endpoint，不把 UDP broadcast 作为跨平台必需能力。

Info.plist 至少配置：

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>用于发现附近车友并建立离线对讲连接</string>
<key>NSBonjourServices</key>
<array>
    <string>_motocom._tcp</string>
</array>
```

本地网络权限拒绝时，保留可操作的系统设置引导；权限恢复后重启 `NWBrowser`/`NWListener`，不复用已失效的 endpoint。

### 5.2 TCP framing

完全复用 Android framing：

```text
4-byte big-endian unsigned length
→ UTF-8 JSON envelope
→ 循环读取直到完整 frame
```

必须限制帧大小、等待超时、SDP/ICE 字段长度和 JSON 类型；不以一次 `receive` 回调等同于完整 JSON。每个连接绑定当前 `sessionId`、目标 `deviceId` 和 attempt token。

## 6. Android 热点和 iPhone 主机路径

### 6.1 Android 主机

当 BLE 能力显示 Android API≥26 且 `ANDROID_LOCAL_ONLY_HOTSPOT_HOST` 可用时，Android 发送 `HOTSPOT_READY`。iOS 端：

1. 展示即将加入 MotoCom 临时网络的系统网络操作提示；
2. 使用系统允许的 Wi‑Fi 加入流程接入 SSID；
3. 加入后等待 Bonjour 或 BLE 传来的 TCP endpoint；
4. 通过 TCP 8890 开始 V2 信令；
5. 网络加入失败时报告明确原因，不显示连接成功。

App 不把 Android 系统返回的密码写入 `PairingStore`，不在日志中打印明文密码。

### 6.2 iPhone 主机

iOS 无法可靠地由第三方 App 自动打开或读取用户个人热点密码，因此采用用户主导的最短系统设置路径：

```text
用户选择“iPhone 作为主机”
→ 引导打开个人热点/允许其他人加入
→ 用户返回 MotoCom
→ Android 在系统 Wi‑Fi 流程加入
→ 双方用 Bonjour 或 BLE endpoint 建立 TCP
```

App 不要求用户为了主连接再次在 App 输入热点密码；若系统流程确实需要输入，由系统 Wi‑Fi 设置完成。用户已有热点密码不能被 App 覆盖、恢复或改成 `123456789`。

## 7. Signaling V2 实现

### 7.1 兼容约束

Android 现有 `SignalingProtocolV2` 对 HELLO 使用严格允许字段集合，并拒绝未知字段。因此 iOS 第一版必须复制现有 wire contract：

- envelope 字段、message type、`requestRole`、`capabilities`、`CONNECT_REQUEST`、`CONNECT_ACCEPT`、`OFFER`、`ANSWER`、`CANDIDATE` 语义一致；
- HELLO 不直接增加未在版本中声明的字段；
- platform/capabilities 优先放在 BLE/Bonjour discovery metadata；
- 若未来必须修改 HELLO，先升级协议版本并为旧 Android 保留 parser/路由；
- `preferredTransportHint` 只发送双方共同理解的值；iOS P2P 不应直接发送给旧 Android `Transport` 枚举。

### 7.2 陌生配对流程

```text
发现未配对设备
→ CONNECT_REQUEST
→ iOS 显示来电式确认页
→ 用户点击接受
→ CONNECT_ACCEPT
→ Offer/Answer/Candidate
→ AUDIO_READY
→ 保存 PairingRecord
```

点击“接受”是配对意图确认；在 WebRTC 音频就绪以前不得落盘为已连接配对。

## 8. WebRTC 音频会话

### 8.1 媒体配置

- 使用经过审查的 WebRTC iOS SDK；版本、二进制架构、许可证和 NOTICE 在实现 PR 中固定；
- 与 Android 使用同一套 host ICE 假设，首版不依赖公网 STUN/TURN；
- 音频使用双向 RTP，优先协商 Opus，保持当前 Android 编码约束；
- 实现 `onTrack`/远端音频轨回调、连接状态、ICE candidate、失败和关闭回调；
- 任何回调都带当前 attempt/session token，迟到回调直接丢弃。

### 8.2 `AUDIO_READY`

不能把 TCP 连接、ICE connected 或 PeerConnection connected 直接显示为成功。iOS 需要合并：

```text
PeerConnection 可用
AND 远端 AudioTrack 已到达
AND 远端首个可播放音频帧已确认
AND AVAudioSession 输入/输出路由可用
AND 当前没有电话中断
= AUDIO_READY
```

`AUDIO_READY` 后才：

- 发布 Connected UI；
- 播放“连接成功”提示音；
- 保存对端 PairingRecord；
- 更新 `lastConnectedAt`/`lastTransport`。

## 9. iOS 音频路由和系统中断

### 9.1 AVAudioSession

建议使用：

- category：`playAndRecord`；
- mode：`voiceChat`；
- 根据当前 route 设置 Bluetooth HFP 和扬声器选项；
- 默认优先蓝牙 HFP；无蓝牙耳机时使用内置麦克风和扬声器；
- 监听 route change、interruption、media services reset 和蓝牙设备断开。

“蓝牙耳机输出”指实际系统通信路由可用，不等同于蓝牙开关开启。路由失败时回退手机音频，并给出可诊断状态。

### 9.2 电话、音乐和导航

- 电话优先级最高；来电/通话期间暂停 MotoCom 录音和播放；
- 电话结束后，只有用户仍保持对讲意图时恢复；
- 对讲优先于音乐/导航，但第三方 App 是否 duck 由系统和第三方实现决定，不作为绝对承诺；
- App 在后台维持对讲需配置后台音频能力，并在审核范围内说明用途；
- App 被系统终止后重新打开，进入恢复流程，不宣称无条件后台存活。

## 10. iOS↔iOS Apple 点对点 Wi‑Fi

### 10.1 实现阶段

P0/P1 先完成 Android↔iPhone 的共同 LAN、Android 临时热点和 iPhone 手动热点路径；P2 再实现 iOS↔iOS Apple P2P。这样不会让 P2P 的系统限制阻塞首个跨平台可用版本。

### 10.2 Transport adapter

`ApplePeerToPeerTransport` 负责：

- 使用 Network.framework `NWParameters.includePeerToPeer`（或当前 SDK 对应 API）允许 Apple P2P；
- 复用相同 TCP framing、V2 信令和 WebRTC；
- 只在对端平台为 iOS 且双方声明 `IOS_PEER_TO_PEER` 时启用；
- P2P 不可用时回退到共同 LAN/热点；
- 不绕过陌生设备接受流程；
- 不把 Apple P2P 作为 Android↔iPhone 的必需路径。

P2P 传输类型应在 iOS adapter 内部表示，或通过新协议版本协商；不要把未知枚举值发送给当前只理解 `LAN`/`WIFI_DIRECT` 的 Android V2 客户端。

## 11. 状态机与恢复

建议状态：

```text
Idle
→ Discovering
→ BootstrapNegotiating
→ NetworkPreparing
→ NetworkReady
→ AwaitingConfirmation
→ Signaling
→ MediaNegotiating
→ AudioReady
→ Connected
```

异常状态：`PermissionBlocked`、`ManualActionRequired`、`Recovering`、`Failed`、`Offline`。

网络切换、Android 热点停止、Bonjour 丢失、BLE 断开或音频路由改变时：

1. 不删除 PairingRecord；
2. 关闭失效 WebRTC/TCP；
3. 重新执行能力交换和网络准备；
4. 恢复到 Audio Ready 后更新连接记录；
5. 用户取消或系统终止时清理 GATT、浏览器、监听器、音频 session 和临时热点资料。

## 12. iOS 权限和配置清单

| 能力 | 配置/运行时要求 |
| --- | --- |
| 蓝牙 | `NSBluetoothAlwaysUsageDescription`；CoreBluetooth 状态授权后才能自动建联 |
| 本地网络 | `NSLocalNetworkUsageDescription`、`NSBonjourServices` |
| 麦克风 | `NSMicrophoneUsageDescription` |
| 音频后台 | Background Modes 中按需启用 Audio，确保用途与审核说明一致 |
| Wi‑Fi 加入 | 优先使用系统 Network.framework/系统设置流程；不能假设 App 可读个人热点密码 |
| 通知 | 仅在确实需要后台连接提示时申请，不把通知权限当网络权限 |

权限拒绝必须映射为可操作状态；禁止把系统权限失败吞成“附近没有设备”。

## 13. PairingStore

保存字段与 Android `PairingRecord` 对齐：

- `remoteDeviceId`；
- `remoteNickname`、`deviceName`、`localAlias`；
- `shortCode`；
- `pairedAt`、`lastConnectedAt`；
- `isPreferred`；
- `lastTransport`；
- `failureCount`。

可使用 SwiftData/Core Data；稳定身份或未来认证材料放 Keychain。当前版本不保存热点 SSID/密码、BLE GATT 数据或音频内容。只有 `AUDIO_READY` 后写入或更新连接记录。

## 14. 测试计划

### 14.1 协议和策略测试

- 与 Android 现有 V2 golden frames 互解：HELLO、CONNECT_REQUEST、CONNECT_ACCEPT、OFFER、ANSWER、CANDIDATE；
- 严格拒绝错误字段、错误长度、错误 UUID、错误目标和过期 session；
- BLE 分片、乱序、重复、超时、断开后重试；
- Bonjour metadata 解析和旧 Android 缺少新字段时的兼容；
- `BootstrapPolicy` 覆盖 Android host、iPhone host、共同 LAN、P2P 和手动回退；
- PairingStore 只在 Audio Ready 保存，首帧超时不保存。

### 14.2 真机验收

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| I1 | iPhone X/iOS 16 ↔ Android API 28 | 接收 Android 临时热点，完成双向对讲 |
| I2 | iPhone X 主机 ↔ Android | 系统打开个人热点，Android 加入，密码不被修改 |
| I3 | iPhone ↔ Android，拒绝蓝牙 | 自动建联阻止，显示权限设置引导 |
| I4 | iPhone ↔ Android，无耳机 | 内置麦克风/扬声器双向可听 |
| I5 | iPhone ↔ Android，HFP 耳机 | 耳机双向可听，断开后回退手机 |
| I6 | 电话中断 | 对讲暂停，电话结束后按用户意图恢复 |
| I7 | 锁屏/后台 | 连续 10 分钟稳定或得到可解释的系统终止结果 |
| I8 | 两台 iPhone，无共同 LAN | P2 阶段使用 Apple P2P；失败时按能力回退 |

### 14.3 Definition of Done

- iOS 工程可在最低支持版本构建并安装到 iPhone X；
- iOS 能通过 BLE/Bonjour 判断对端平台和能力；
- Android 主机临时热点和 iPhone 主机手动热点两条路径均能进入 TCP 8890；
- iOS 与 Android 完成陌生设备确认、V2 信令和 WebRTC 双向音频；
- 连接成功严格以 `AUDIO_READY` 为准；
- 蓝牙 HFP 和无耳机两种路由均有真机结果；
- 电话、后台、网络切换、权限拒绝和恢复行为有明确结果；
- iOS↔iOS P2P adapter 不影响 Android↔iPhone 与 Android↔Android；
- 两台真机连续对讲 10 分钟无未预期断线；
- 热点密码未落盘，用户系统热点配置未被修改。

## 15. 参考资料

- [Apple Network framework](https://developer.apple.com/documentation/network)
- [Apple `NWBrowser`](https://developer.apple.com/documentation/network/nwbrowser)
- [Apple `NWListener`](https://developer.apple.com/documentation/network/nwlistener)
- [Apple `NWConnection`](https://developer.apple.com/documentation/network/nwconnection)
- [Apple peer-to-peer networking](https://developer.apple.com/documentation/network/nwparameters/2998686-includepeer-to-peer)
- [Apple Core Bluetooth](https://developer.apple.com/documentation/corebluetooth)
- [Apple `AVAudioSession`](https://developer.apple.com/documentation/avfaudio/avaudiosession)
- [Apple `NSBonjourServices`](https://developer.apple.com/documentation/bundleresources/information_property_list/nsbonjourservices)
- [Apple `NSLocalNetworkUsageDescription`](https://developer.apple.com/documentation/bundleresources/information_property_list/nslocalnetworkusagedescription)
- [WebRTC native iOS documentation](https://webrtc.googlesource.com/src/+/refs/heads/main/docs/native-code/ios/)
- [Android 端配套开发文档](2026-08-22-motocom-android-cross-platform-development.md)

## 16. 与已实现 Android 端对接的 wire contract

Android 端已实现 BLE bootstrap 协调器；iOS 对接时必须使用上一节之外的以下固定线协议。BLE 仅用于发现和网络准备，不承载 WebRTC 音频。

- Service UUID：`0000c001-0000-1000-8000-00805f9b34fb`；
- RX：`0000c002-0000-1000-8000-00805f9b34fb`，支持 Write/Write Without Response；
- TX：`0000c003-0000-1000-8000-00805f9b34fb`，Read/Notify，并配置 CCCD `00002902-0000-1000-8000-00805f9b34fb`；
- Android 默认按 20-byte BLE packet 分片。每片头部为大端 `messageId:uint32`、`index:uint16`、`total:uint16`、`messageBytes:uint32`，共 12 bytes；接收端允许乱序/重复，5 秒无完整消息即丢弃；
- Bootstrap JSON 根字段必须严格为 `protocolVersion`、`type`、`requestId`、`fields`、`capabilities`，`requestId` 为 canonical UUID，不得添加未知根字段；
- `BOOTSTRAP_CAPABILITIES` 中的 `fields` 至少包括 `app`、`platform`、`deviceId`、`sessionId`、`networkRole`、`tcpPort`、`protocolVersion`；Android 的 TCP 端口为 `8890`；
- `HOTSPOT_READY` 使用 `ssid`、`passphrase`、`security`、可选 `hostAddress`、`tcpPort`、`ttlMs`；`ttlMs` 是发送方凭据剩余有效时长，接收方必须转换为自己的单调时钟，不能直接比较另一台设备的 `elapsedRealtime`；密码不得持久化，Android 不会修改用户已有热点密码；
- `ENDPOINT_READY` 至少携带 `hostAddress`、`tcpPort`、`deviceId`、`sessionId`、`platform`、`protocolVersion`、`networkRole` 和 `capabilities`；地址必须是当前离线网络的私有地址，Bonjour 不可用时接收方可将其加入定向 TCP 8890 fallback；
- `WIFI_JOIN_RESULT`、`ENDPOINT_READY`、`BOOTSTRAP_CANCEL` 的 `requestId` 和字段同样遵守严格版本与长度校验。

LAN/Bonjour 对接保持 `_motocom._tcp.`、TCP `8890`，发现元数据使用 `platform`、逗号分隔 `capabilities` 和 `networkRole`。这些平台字段放在 BLE/发现数据中；Signaling V2 的 HELLO 只能复用既有 `capabilities` 字段，不能新增 `platform` 等未知字段，否则当前 Android 严格 V2 parser 会拒绝。

Android 的 `AUDIO_READY` 需要远端音轨已到达、首个解码 PCM 帧已由 WebRTC `AudioTrackSink` 收到、本地输出/输入路由可用且没有电话中断；iOS 应在等价的远端音频渲染/解码确认点后再显示成功和写入 PairingStore。
