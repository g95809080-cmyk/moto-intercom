# MotoCom Android 跨平台互联开发文档

## 0. 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档类型 | Android 端跨平台互联技术开发文档 |
| 依据 | [iOS 跨平台建联、陌生配对与音频会话 PRD](../product/2026-08-22-motocom-ios-cross-platform-pairing-prd.md) |
| 当前基线 | Android v1.1.0，分支 `feat/multi-audio-intercom` |
| 当前工程 | `MotoIntercomApp-release-v1` |
| SDK | `compileSdk=37`、`minSdk=23`、`targetSdk=36` |
| 第一版范围 | 一对一、无互联网、Android↔iPhone；Android↔Android 保持 Wi‑Fi Direct |

## 1. 结论：Android 需要改动

需要改动，而且改动集中在“网络准备、跨平台发现、能力协商和连接成功语义”四个层面；现有 WebRTC 媒体链路、V2 信令、`SessionOrchestrator` 单状态写入原则和 Android↔Android Wi‑Fi Direct 不应推倒重来。

当前工程已经具备：

- LAN NSD/UDP 发现和 TCP `8890` 信令通道；
- Android Wi‑Fi Direct 建组、发现和 Socket 信令；
- 严格解析的 Signaling V2、WebRTC Offer/Answer/Candidate；
- `RiderAudioEngine` 的远端音频轨回调和 `AudioSessionController`/蓝牙路由；
- 连接后保存 `PairingRecord` 的基础逻辑。

当前工程缺少或不满足跨平台要求：

- 没有 MotoCom BLE 扫描、广播、GATT bootstrap 实现；Manifest 目前只有 `BLUETOOTH_CONNECT`，不是完整 BLE 建联权限集；
- 没有统一的网络准备协调器，无法在 Android API≥26 时自动创建 Local-only Hotspot 并把系统凭据交给 iPhone；
- LAN/Presence 发现模型没有平台和运行时网络能力字段；
- `Transport` 当前只有 `LAN` 和 `WIFI_DIRECT`，没有“热点准备”和后续 iOS P2P adapter 的边界；
- 当前配对保存以产品 `Connected` 和 Socket 身份验证为条件，还没有把“远端音频帧已经到达并且本地音频路由已就绪”作为 `AUDIO_READY` 条件。

## 2. 不变的现有边界

1. Android↔Android 继续使用当前 Wi‑Fi Direct；跨平台功能不得让该路径默认改走热点。
2. WebRTC 继续作为媒体层；热点、LAN、Wi‑Fi Direct 只是 IP 网络或信令承载。
3. `SessionOrchestrator` 继续是唯一产品状态写入者；`IntercomService` 继续负责生命周期、资源和副作用。
4. BLE 只用于发现、能力交换、临时网络凭据和 endpoint 引导，不承载音频，也不作为长时间保活链路。
5. 接受陌生连接后才算配对意图成立；`shortCode` 仅用于展示和定位，不是认证码，不新增 QR、PIN、SAS 或账号系统。
6. 不保存热点密码到 `PairingRecord`，不修改用户已有个人热点密码。

## 3. 端到端连接路径

```text
读取 Android 运行时能力
    ↓
BLE 发现并交换 platform/capabilities
    ↓
选择共同网络路径
    ├─ 已在同一 LAN：Bonjour/NSD + TCP 8890
    ├─ Android API≥26 可创建热点：Android Local-only Hotspot → iPhone 加入
    ├─ iPhone 主机：用户打开个人热点 → Android 走系统 Wi‑Fi 加入
    ├─ API<26 或 OEM 拒绝：最短手动热点/Wi‑Fi 设置路径
    └─ Android↔Android：保留 Wi‑Fi Direct
    ↓
LAN/临时网络上的 Signaling V2
    ↓
陌生设备确认 → WebRTC Offer/Answer/Candidate
    ↓
远端音频帧到达 + 本地路由就绪 = AUDIO_READY
    ↓
双方保存 PairingRecord
```

## 4. 建议的 Android 模块拆分

新增模块名可以调整，但职责必须保持单一：

| 模块 | 职责 | 所有者 |
| --- | --- | --- |
| `PlatformCapabilityProvider` | 根据真实 API、权限、Wi‑Fi、BLE、Wi‑Fi Direct 状态计算能力 | `IntercomService` 创建，Coordinator 使用 |
| `BleBootstrapCoordinator` | BLE 广播、扫描、GATT 连接、消息编解码、临时数据清理 | `IntercomService` |
| `NetworkBootstrapCoordinator` | 判断共同 LAN、选择主机/加入方、创建或加入网络、释放 reservation | `IntercomService` |
| `BootstrapPolicy` | 根据双方能力和用户操作选择路径，不直接操作系统 API | 纯 Kotlin |
| `NetworkBootstrapState` | 对外发布准备阶段和失败原因 | `SessionOrchestrator` 读取并写状态 |
| `AudioReadyTracker` | 合并 WebRTC 远端首帧、音频路由和通话占用状态 | `RiderAudioEngine`/`AudioSessionController` 回调 |
| `CrossPlatformDiscoveryAdapter` | 将 BLE/Bonjour/UDP 得到的能力映射到现有 `PresenceAggregator` | Discovery 层 |

不要让 `BleBootstrapCoordinator` 或 `NetworkBootstrapCoordinator` 直接写 `IntercomState`；它们应通过事件/效果回调交给现有状态架构。

## 5. BLE Bootstrap

### 5.1 统一应用层数据

使用统一的 App-specific BLE Service UUID。广播或 GATT 消息至少支持以下 JSON 字段：

```json
{
  "app": "motocom",
  "protocolVersion": 2,
  "platform": "ANDROID",
  "deviceId": "stable-uuid",
  "sessionId": "runtime-uuid",
  "capabilities": [
    "BLE_BOOTSTRAP",
    "LAN",
    "ANDROID_LOCAL_ONLY_HOTSPOT_HOST",
    "WIFI_JOIN",
    "ANDROID_WIFI_DIRECT"
  ],
  "networkRole": "HOST|JOINER|EITHER",
  "tcpPort": 8890
}
```

网络准备事务中，再通过受保护的当前 GATT 会话发送临时消息：

- `BOOTSTRAP_REQUEST`：请求对方能力和可用路径；
- `BOOTSTRAP_CAPABILITIES`：回复平台、API 能力和角色；
- `HOTSPOT_READY`：发送本次 App 创建热点的 SSID、认证类型、密码、主机地址和过期时间；
- `WIFI_JOIN_RESULT`：报告加入成功、失败或需要系统设置；
- `ENDPOINT_READY`：发送临时 TCP endpoint；
- `BOOTSTRAP_CANCEL`：用户取消或 session 失效。

密码只存在内存和当前 bootstrap 事务中；日志必须脱敏，PairingRecord 不得新增密码字段。

### 5.2 Android 权限和生命周期

按实际 `targetSdk=36` 配置并运行时申请：

- Android 12/API 31+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE`；
- Android 11/API 30 及以下：声明 `BLUETOOTH`、`BLUETOOTH_ADMIN`，并按系统版本申请 BLE 扫描所需位置权限；
- BLE、附近 Wi‑Fi、麦克风和前台服务权限分别处理，不能把“蓝牙耳机音频权限”误当作“BLE 建联权限”；
- 蓝牙权限被拒绝时，自动网络准备直接阻止，显示进入设置的引导；恢复权限后允许重新开始；
- BLE 断开不应关闭已经建立的 TCP/WebRTC；它只触发必要的 endpoint 恢复流程。

### 5.3 串行化和安全边界

- 单个 `runtimeSessionId` 只允许一个有效 bootstrap generation；旧回调必须丢弃；
- 连接前验证 `app`、协议版本、`deviceId`、`sessionId`、目标设备和消息长度；
- BLE 发现只表示“附近存在 MotoCom”，不表示对方可信；陌生设备仍必须经过 `CONNECT_REQUEST → 接受 → CONNECT_ACCEPT`；
- 设备离开、用户取消、热点失败和 Service 停止时关闭 GATT、扫描、广播和 reservation。

## 6. 网络准备策略

### 6.1 选择矩阵

| 条件 | Android 行为 | 对端行为 |
| --- | --- | --- |
| 已有共同 LAN | 不创建热点，发布/发现 `_motocom._tcp.` | iPhone 用 Bonjour，必要时用 BLE endpoint |
| Android API≥26 且 Local-only Hotspot 可用 | 自动作为主机，使用系统返回 SSID/认证/密码 | iPhone 通过系统网络流程加入 |
| iPhone 明确作为主机 | Android 不抢主机角色 | 用户在系统设置/控制中心打开个人热点 |
| Android API 23–25 | 不假设存在自动 Local-only Hotspot | 进入最短手动热点/Wi‑Fi 设置路径 |
| OEM 拒绝热点、客户端隔离或加入失败 | 保留失败原因并给出可执行回退 | 不发布虚假的 Connected |
| Android↔Android | 沿用当前 Wi‑Fi Direct | 沿用当前 Wi‑Fi Direct |

共同能力相同且双方都想当主机时，优先采用用户当前操作；没有用户操作时用稳定 `deviceId` 排序，保证只有一方创建网络。

### 6.2 Android Local-only Hotspot

`NetworkBootstrapCoordinator` 在 API≥26 时调用 `WifiManager.startLocalOnlyHotspot()`，并以系统回调返回的配置为准：

1. 检查 Wi‑Fi、附近 Wi‑Fi 权限、热点占用和当前网络；
2. 请求 Local-only Hotspot；
3. 读取系统返回的 SSID、认证类型、密码和 reservation；
4. 通过 BLE 发送给 iPhone；
5. 等待 iPhone 加入并通过 BLE/TCP 回报；
6. 通过 Bonjour/endpoint 建立 TCP 8890；
7. session 结束或被取消时关闭 reservation。

Android API 26–35 不承诺 App 可以把密码强制设置为 `123456789`，必须使用系统返回凭据。`123456789` 只作为用户明确进入手动 MotoCom 热点配置，且当前系统确实允许自定义时的兜底值；不能覆盖用户已有热点。

### 6.3 已有热点和手动回退

Android App 不应假定能够读取用户在系统设置中保存的个人热点密码。需要密码时，输入发生在系统 Wi‑Fi 流程或用户明确的手动配置流程中；不能在 App 中要求用户重复输入作为主路径。

当自动路径失败，UI 应明确显示：

```text
无法自动准备网络，请打开系统 Wi‑Fi/个人热点设置。
完成后返回 MotoCom，应用会继续发现和连接。
```

## 7. 与现有发现、信令和传输的衔接

### 7.1 发现模型扩展

在 `LanRiderDevice`、`DiscoveryIdentityClaim`、`RiderPresence` 和跨平台 BLE model 中增加：

- `platform`：`ANDROID`/`IOS`；
- `capabilities`：真实运行时能力集合；
- `networkRole`；
- 可选的临时 endpoint 来源和过期时间。

Android 当前 NSD TXT 和 UDP `MOTOCOM_HELLO` 至少追加 `platform`、`capabilities`，并保持旧字段兼容。旧 Android 节点缺少新字段时按未知平台处理，不得因为缺字段崩溃。

### 7.2 Signaling V2 兼容

当前 `SignalingProtocolV2` 对 HELLO 使用严格允许字段集合；旧客户端收到未知字段会拒绝。因此：

- 第一阶段不要直接向未升级的 V2 HELLO 注入 `platform` 等未知字段；
- 平台和网络能力优先放在 BLE/NSD/Bonjour 发现数据中；
- HELLO 的现有 `capabilities` 继续使用，但若要新增必需字段，必须升级协议版本并提供兼容策略；
- Android↔iPhone 第一阶段仍把信令传输视为 `LAN`，Local-only Hotspot 只是让双方进入同一 IP 网络；
- 后续 iOS↔iOS P2P 使用独立 transport adapter，不能让旧 Android `Transport` 枚举直接收到未知值。

TCP framing 继续为：4 字节大端长度前缀 + UTF-8 JSON；继续复用现有最大帧、SDP、ICE Candidate 和 UUID 校验。

### 7.3 不要把热点作为新的媒体层

不要新增裸 UDP 音频或第二套 WebRTC。热点/LAN/Wi‑Fi Direct 只负责网络可达性和 Signaling V2；音频继续走现有 WebRTC + Opus + SRTP 路径。

## 8. 连接成功和配对保存

当前 `SessionOrchestrator` 在 `Connected` 状态和远端 Socket 身份验证通过后保存 `PairingRecord`。跨平台适配要增加一个内部门槛：

```text
Socket identity verified
    AND WebRTC PeerConnection usable
    AND remote AudioTrack 已启用
    AND 本地音频路由可用
    AND 当前未被电话中断
    = AUDIO_READY
```

实现要求：

1. `RiderAudioEngine` 在 `onTrack`/`onAddTrack` 后发出远端音频轨事件，并在实际首个可播放音频帧达到时标记远端音频就绪；
2. `AudioSessionController` 提供当前输出/输入路由和电话占用结果；
3. `AudioReadyTracker` 合并两者后发事件；
4. 只有 `AUDIO_READY` 才允许 UI 显示“连接成功”、播放成功提示音和触发配对保存；
5. `shortCode` 仍由设备 ID 派生，仅用于展示；`isPreferred` 仍与是否配对分离；
6. 若首帧超时、路由失败或电话占用，连接失败或保持恢复态，不保存新配对。

音频路由保持产品约定：优先蓝牙 HFP；无蓝牙耳机时使用手机扬声器输出和内置麦克风输入；电话优先级高于对讲，对讲高于音乐/导航。

## 9. 生命周期、恢复和资源释放

### 9.1 后台与锁屏

- 使用现有前台 Service 持有 BLE、网络和音频资源；
- 锁屏/后台继续对讲时，不依赖 Activity 持有连接；
- iOS 或 Android App 被系统终止后，重新打开 App 进入恢复流程，不承诺完全无感恢复；
- 每个 bootstrap、网络加入、TCP、WebRTC 回调携带 session/generation，迟到回调直接丢弃。

### 9.2 网络变化

Wi‑Fi 断开、热点停止、客户端隔离、默认网络变化时：

1. 保留配对记录；
2. 关闭失效 TCP/WebRTC 资源；
3. 进入 `Recovering`；
4. 重新执行 BLE 能力交换和网络策略；
5. 恢复到 `AUDIO_READY` 后更新 `lastConnectedAt` 和 `lastTransport`。

### 9.3 停止顺序

```text
取消自动重试
→ 停止/关闭 WebRTC
→ 关闭 TCP/Bonjour/UDP
→ 关闭 BLE GATT、扫描和广播
→ 释放 App 创建的 Local-only Hotspot reservation
→ 清除内存中的 SSID/密码/endpoint
→ 发布 Offline
```

## 10. 需要改动的 Android 文件范围

以下是实现时的最小范围，实际类名可按项目风格调整：

| 范围 | 文件/模块 | 改动 |
| --- | --- | --- |
| 权限 | `app/src/main/AndroidManifest.xml`、权限策略/UI | 补齐 BLE 扫描/广播权限和按 API 分支的运行时请求；核对附近 Wi‑Fi 权限 |
| 新能力 | `PlatformCapabilityProvider`、`BleBootstrapCoordinator`、`NetworkBootstrapCoordinator`、`BootstrapPolicy` | 新增 |
| 生命周期 | `IntercomService.kt`、`SessionOrchestrator.kt` | 接入 bootstrap effect/event、取消、恢复和资源释放 |
| 发现 | `LanDiscoveryCoordinator.kt`、`LanRiderDevice.kt`、`PresenceAggregator.kt` | 增加 platform/capabilities/临时 endpoint，兼容旧字段 |
| 协议 | `SignalingProtocolV2.kt`、传输/能力模型 | 保持 V2 严格解析；只有协议升级时才增加必需字段 |
| 音频 | `RiderAudioEngine.kt`、`AudioSessionController.kt`、`IntercomService.kt` | 输出首帧/路由就绪事件，形成 `AUDIO_READY` |
| 数据 | `PairingRecord.kt`、`PairingRepository.kt` | 保持字段语义，不保存热点密码；配对触发点后移到 Audio Ready |
| 测试 | `app/src/test`、`app/src/androidTest` | 新增协议、策略、权限、恢复、真机互通测试 |

不建议为了这次适配修改现有 `PairingRecord` 的 `shortCode` 规则、删除 Wi‑Fi Direct 或重写 `RiderAudioEngine`。

## 11. 测试与验收

### 11.1 单元/集成测试

- BLE JSON 编解码：缺字段、超长字段、未知能力、旧版本、重复能力；
- `BootstrapPolicy`：共同 LAN、Android host、iPhone host、API 23–25、API 26+、权限拒绝、热点失败；
- generation/session：旧 BLE、旧 hotspot reservation、旧 endpoint 和旧 WebRTC 回调不能污染新会话；
- 发现兼容：新 Android 读旧 Android；新 Android 读 iOS Bonjour；缺 platform 时不崩溃；
- V2 golden frames：HELLO、CONNECT_REQUEST、CONNECT_ACCEPT、OFFER、ANSWER、CANDIDATE 与现有 Android fixtures 完全兼容；
- Pairing：只有 `AUDIO_READY` 保存，电话中断、首帧超时不保存；
- Android↔Android Wi‑Fi Direct 回归必须通过。

### 11.2 真机矩阵

| 编号 | 组合 | 预期 |
| --- | --- | --- |
| A1 | Android API 28 ↔ iPhone X/iOS 16 | Android 自动临时热点，iPhone 加入并听到远端声音 |
| A2 | Android API 25 ↔ iPhone X/iOS 16 | 进入最短手动网络设置路径，不假装自动成功 |
| A3 | iPhone 主机 ↔ Android | Android 通过系统 Wi‑Fi 加入，不改热点密码 |
| A4 | Android ↔ Android | 现有 Wi‑Fi Direct 不退化 |
| A5 | Android ↔ iPhone，拒绝 BLE | 阻止自动建联并显示权限引导 |
| A6 | 无蓝牙耳机 | 手机扬声器输出、内置麦克风输入，双向可听 |
| A7 | 蓝牙 HFP 耳机 | 耳机输入输出，断开后手机路由恢复 |
| A8 | 电话响铃/通话/结束 | 对讲暂停，通话结束后按用户意图恢复 |
| A9 | 锁屏/后台/网络切换 | 10 分钟稳定；网络恢复后再次 Audio Ready |

### 11.3 Definition of Done

- Android 能通过 BLE 判断对端为 iOS，并选择正确网络路径；
- Android API≥26 的可用设备能创建并释放 App-owned Local-only Hotspot；
- API<26 或 OEM 失败时能进入真实可操作的手动回退；
- Android 与 iPhone 完成陌生设备接受、V2 信令、WebRTC 双向音频；
- “连接成功”只在实际远端音频就绪后出现；
- 不修改用户已有热点密码，不把密码持久化；
- Android↔Android Wi‑Fi Direct、现有 LAN 和电话/蓝牙音频回归通过；
- 两台真机连续对讲 10 分钟，无未预期断线。

## 12. 参考资料

- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android BLE overview](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)
- [Android Local-only Hotspot](https://developer.android.com/develop/connectivity/wifi/local-only-hotspot)
- [Android Wi‑Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
- [Android `WifiManager.LocalOnlyHotspotCallback`](https://developer.android.com/reference/android/net/wifi/WifiManager.LocalOnlyHotspotCallback)
- [Android Audio Focus](https://developer.android.com/media/optimize/audio-focus)
- [当前工程 `LanDiscoveryCoordinator.kt`](../../app/src/main/java/com/kuma/motointercom/LanDiscoveryCoordinator.kt)
- [当前工程 `SignalingProtocolV2.kt`](../../app/src/main/java/com/kuma/motointercom/SignalingProtocolV2.kt)
- [当前工程 `SessionOrchestrator.kt`](../../app/src/main/java/com/kuma/motointercom/SessionOrchestrator.kt)
- [当前工程 `RiderAudioEngine.kt`](../../app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt)

## 13. Android v1.1.0 实现记录（2026-08-22）

本轮 Android 代码已落地以下跨平台闭环；`ios/` 源码不在本任务范围内。

### 13.1 运行时能力与网络路径

- `CrossPlatformCapabilities` 根据 Android API、Wi‑Fi 开关、Wi‑Fi Direct 设备能力和 BLE 运行时权限生成真实能力集合；
- `CrossPlatformBootstrapPolicy` 优先选择双方共同 LAN，其次选择 Android API≥26 Local-only Hotspot，再进入 iPhone 个人热点/Android 低版本系统设置回退；Android↔Android 仍保留 Wi‑Fi Direct；
- `AndroidNetworkBootstrapCoordinator` 使用系统返回的 Local-only Hotspot 凭据，不修改已有个人热点，不把 SSID/密码持久化；API<26、OEM 拒绝、客户端隔离、加入失败均发布可操作的手动回退或失败事件；
- Android Q+ 加入对端热点使用 `WifiNetworkSpecifier`，并在连接成功后绑定当前进程网络；
- 手动 MotoCom 热点流程的固定密码约定仍为 `123456789`，只供用户明确创建新热点时参考，不能覆盖或改写用户已有热点密码。

### 13.2 BLE wire contract（iOS 必须保持一致）

BLE 只用于发现、平台识别和网络 bootstrap，不承载音频。

| 项目 | 值 |
| --- | --- |
| Service UUID | `0000c001-0000-1000-8000-00805f9b34fb` |
| RX characteristic | `0000c002-0000-1000-8000-00805f9b34fb`，Write/Write Without Response |
| TX characteristic | `0000c003-0000-1000-8000-00805f9b34fb`，Read/Notify |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |
| 默认 BLE packet | 20 bytes |
| 分片头 | 大端 `messageId:uint32`、`index:uint16`、`total:uint16`、`messageBytes:uint32`，共 12 bytes |
| 分片超时 | 5 秒；允许乱序和重复分片，最多 512 片，完整消息最多 4096 bytes |

Bootstrap JSON 消息根字段必须严格为：`protocolVersion`、`type`、`requestId`、`fields`、`capabilities`。`requestId` 必须是 canonical UUID；未知根字段、重复 capability、错误版本或超长字段必须拒绝。支持的 `type` 为：

- `BOOTSTRAP_REQUEST`；
- `BOOTSTRAP_CAPABILITIES`；
- `HOTSPOT_READY`；
- `WIFI_JOIN_RESULT`；
- `ENDPOINT_READY`；
- `BOOTSTRAP_CANCEL`。

`BOOTSTRAP_CAPABILITIES` 的 `fields` 至少包含 `app=motocom`、`platform`、`deviceId`、`sessionId`、`networkRole`、`tcpPort=8890`、`protocolVersion`，能力集合包含真实运行时能力。热点消息使用 `ssid`、`passphrase`、`security`、可选 `hostAddress`、`tcpPort`、`ttlMs`；`ttlMs` 必须按接收方自己的单调时钟计算，不能传递或比较跨设备的 `elapsedRealtime`。`ENDPOINT_READY` 另外必须包含 `hostAddress`、`tcpPort`、`deviceId`、`sessionId`、`platform`、`protocolVersion`、`networkRole` 和 `capabilities`，接收方在 Bonjour/UDP 不可用时将合法私有地址加入定向 TCP fallback。密码只在内存和当前 BLE 消息中短暂使用，日志统一脱敏。

### 13.3 LAN、发现和 Signaling V2

- NSD 服务为 `_motocom._tcp.`，TCP 控制端口为 `8890`；
- NSD TXT 与 UDP `MOTOCOM_HELLO` 增加 `platform`、逗号分隔的 `capabilities` 和 `networkRole`；旧节点缺字段按未知平台兼容，不崩溃；
- Wi‑Fi Direct 的既有 `_motocom._tcp` 发现保留，并同步携带相同的可选平台能力字段；
- `SignalingSessionV2` 仅通过既有严格 HELLO `capabilities` 字段传递能力，不向 HELLO 添加 `platform` 等未知字段，旧 V2 解析规则保持不变；
- 网络切换后由 Service 重启当前 LAN 发现；BLE、热点、发现、信令和 WebRTC 回调均受当前 generation/session 校验。

### 13.4 AUDIO_READY 与配对触发

Android 现在按以下顺序确认成功：WebRTC PeerConnection 已连接、远端 `AudioTrack` 已到达、远端 `AudioTrackSink` 收到首个解码 PCM 帧、本地蓝牙 HFP/手机扬声器/听筒路由可用、且当前没有电话中断。只有 `AUDIO_READY` 被当前 `SessionOrchestrator` 接受后，才：

1. 显示首页绿色成功态和“语音通道已连接”；
2. 更新前台通知成功文案；
3. 保存或更新 `PairingRecord`；
4. 允许后续已配对自动连接继续使用现有规则。

首帧/路由确认超时为 10 秒；电话抢占、路由丢失、媒体关闭和旧 generation 回调都会撤销就绪态。公共 WebRTC API 能确认解码 PCM 到达，但是否真的从目标手机扬声器或蓝牙 HFP 出声仍必须由真机听音验收确认。

### 13.5 自动化验证记录

- `:app:compileDebugKotlin`：通过；
- `:app:compileDebugUnitTestKotlin`：通过；
- `:app:testDebugUnitTest`：通过（完整 JVM/Robolectric 单测）；
- `:app:lintDebug`：通过；保留既有平台弃用和依赖更新警告；
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`：已通过构建任务；最终交付前会再次执行组合命令确认产物。

当前环境没有可用的 `adb` 真机/模拟器目标，因此 Android↔iPhone BLE、热点加入、WebRTC 双向听音、HFP 和 10 分钟稳定性尚未宣称通过，必须按第 11 节真机矩阵验收。
