# MotoCom iOS

这是 MotoCom iOS 跨平台互联的 Swift Package 基线，最低支持 iOS 16。当前仓库运行环境为 Windows，没有 Xcode、Swift toolchain 或 iOS Simulator，因此本目录优先提供可被 Xcode 导入的 Swift 源码、协议 fixtures 和测试；macOS 上需要用 Xcode 创建一个 iOS App target，并把本 package 作为 local package 加入。

## 目录

- `Package.swift`：iOS 16 Swift Package manifest。
- `Sources/MotoComIOS`：核心模型、V2 codec、framing、bootstrap、Network.framework/CoreBluetooth/AVFAudio 适配、SwiftUI UI 和会话状态机。
- `App/MotoComApp.swift`：由 Xcode iOS App target 使用的 `@main` 入口示例。
- `Tests/MotoComIOSTests`：协议、framing、BLE 分片、网络策略、配对保存和 Audio Ready 测试。
- `Resources/Info.plist.example`：App target 必须合并的权限与 Bonjour 配置。

## macOS/Xcode 构建

在 macOS 上：

1. 在 Xcode 中打开 `ios/Package.swift`，或创建 iOS App target 后添加该本地 package。
2. 将 `Resources/Info.plist.example` 中的权限键合并到 App target 的 Info.plist。
3. 将 App target 的 deployment target 设置为 iOS 16.0，并按 `Info.plist.example` 配置后台 Audio。
4. 将经过审核的 `WebRTC` 模块链接并暴露给 `MotoComIOS` target；模块可导入时默认选择 `GoogleWebRTCEngine`，否则显式使用 `UnavailableWebRTCEngine` 并拒绝伪造连接成功。本 package 不偷偷绑定未经确认的第三方二进制。
5. 先运行 `swift test`/Xcode Tests，再使用 iPhone X 或 iOS 16 Simulator 做 BLE、Bonjour、音频和后台验证。

## 与 Android 的 wire contract

- TCP 控制端口：`8890`。
- framing：4 字节大端无符号长度 + UTF-8 JSON。
- Signaling protocol：`2`。
- BLE 使用 Android 当前相同的 Service `C001`、RX `C002`、TX `C003` UUID；分片头为 12 字节大端 `messageId/index/total/messageBytes`，默认按 Android 的 20 字节包长和最多 512 片组包，并在 CoreBluetooth 背压回调中排队发送。
- BLE JSON 根字段严格为 `protocolVersion`、`type`、`requestId`、`fields`、`capabilities`；热点字段使用 Android 的 `passphrase`/`expiresAtMs` 命名。
- HELLO 不增加 Android 当前严格 parser 未声明的字段；平台和网络能力通过 BLE/Bonjour metadata 传递。
- iOS↔iOS 优先使用已发现的共同 LAN 端点；只有没有 LAN 端点且双方声明 `IOS_PEER_TO_PEER` 时才使用 Apple P2P，P2P 失败会尝试禁用 P2P 回退 LAN。
- WebRTC 首版使用 host ICE，不依赖公网 STUN/TURN；`OFFER`/`ANSWER` 的字符串内部使用 Android 同样的 `{"type":"offer|answer","sdp":"..."}` JSON，ICE candidate 使用 `sdpMid`、`sdpMLineIndex`、`candidate` 三字段，并强制 Opus 32 kbps FMTP。
- 实际 SDK 的远端音频渲染/解码回调必须调用 `GoogleWebRTCEngine.markRemoteAudioFrameDecoded()`，之后才会满足 `AUDIO_READY`。
- 只有远端首个可播放音频帧、本地音频路由和非电话中断状态同时满足时才发布 `AUDIO_READY`。
- 媒体开始后 10 秒内未满足 `AUDIO_READY` 会关闭当前控制/媒体会话并显示失败，不会把 TCP 或 ICE 成功误报为已连接。

## 当前无法在本机完成的验证

- Xcode 编译、iOS Simulator UI、CoreBluetooth 真机扫描/广播。
- Network.framework Bonjour 和 Local Network permission 弹窗。
- AVAudioSession HFP、手机扬声器回退、电话中断和锁屏后台行为。
- 实际 WebRTC iOS SDK 的编解码、Opus、ICE 和双真机听音。
