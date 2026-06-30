# 参考项目横向对比

对比快照：

- Walkie-Talkie：`58c759b`，MIT
- Nojre：`178fb25`，AGPL-3.0

> “参考价值”表示可以独立重新实现其设计思想，不表示可以直接复制源码。Nojre 受 AGPL-3.0 约束。

## 核心对比

| # | 对比维度 | Walkie-Talkie | Nojre | 对 MotoCom 的判断 |
|---:|---|---|---|---|
| 1 | 项目语言 | Java | Kotlin，使用 Compose | Nojre 更接近 MotoCom 技术栈 |
| 2 | 最后维护时间 | 2020-01-20 | 2023-09-05 | Nojre 更新，但两者都不是 Android 16 方案 |
| 3 | Android 现代化程度 | 低：targetSdk 28、旧 Support Library | 中：targetSdk 33、AndroidX、Compose、前台服务 | Nojre 较现代；MotoCom 的 API 36 适配仍应自行维护 |
| 4 | 核心网络协议 | Wi-Fi Direct 上的 TCP Socket，传裸 PCM | 同一 Wi-Fi 上的 UDP multicast，传加密裸 PCM | 两者都不应替换 WebRTC RTP/UDP + Opus |
| 5 | 是否需要同一 Wi-Fi | 否 | 是 | MotoCom 的无路由器场景更接近 Walkie-Talkie |
| 6 | 是否能无路由器直连 | 能，通过 Wi-Fi Direct | 不能可靠做到；Soft AP 主机存在 multicast 接收问题 | 公路前后座主链路应保留 Wi-Fi Direct |
| 7 | 是否使用 Wi-Fi Direct | 是 | 否 | Walkie-Talkie 有建链参考价值 |
| 8 | 是否使用 UDP multicast | 否 | 是 | Nojre 适合路由器模式的多人实验，不适合作为唯一链路 |
| 9 | 是否使用 TCP / Socket | 使用 TCP `ServerSocket/Socket` | 使用 UDP `MulticastSocket`，无 TCP 建链 | MotoCom 当前 TCP 只做信令更合理 |
| 10 | 是否支持自动发现 | 支持，WifiP2pManager Peer 扫描 | 支持，周期 multicast advertise | 两者思路 MotoCom 已分别通过 Wi-Fi Direct、UDP/NSD 覆盖 |
| 11 | 是否支持加密 | 不支持 | 支持 AES-256-GCM | WebRTC 已有 DTLS-SRTP；Nojre 加密不应重复套在媒体上 |
| 12 | 是否支持多人广播 | 不支持 | 支持，multicast + 本地混音 | 只有产品明确需要多人时才参考 Nojre |
| 13 | 是否适合 1 对 1 前后座 | 拓扑适合，媒体实现不适合 | 有路由器时可用，无路由器不可靠 | MotoCom 现有 Wi-Fi Direct + WebRTC 更合适 |
| 14 | 是否适合持续全双工通话 | 不适合，产品按 TALK/OVER PTT 设计 | 适合，持续采集、发送、混音和播放 | Nojre 的持续采集原则值得参考 |
| 15 | 是否适合 PTT 按住说话 | 适合 | 不适合，默认持续广播 | MotoCom 当前目标是免按键全双工，不迁移 PTT |
| 16 | 延迟预期 | 网络正常时可低延迟；TCP 重传会造成延迟突增 | 通常低延迟；无成熟 jitter buffer，队列靠丢弃控制延迟 | WebRTC 的抖动与时钟处理更可靠 |
| 17 | 丢包风险 | TCP 不丢应用数据，但队头阻塞会损害实时性 | UDP 会直接丢包，没有 FEC/NACK/PLC | 两者都弱于 WebRTC Opus/FEC/抖动缓冲 |
| 18 | 回声风险 | 高；无 AEC，但 PTT 可减少同时回声 | 很高；持续全双工且无 AEC，手机外放容易回灌 | 保留 WebRTC AEC，不采用裸 PCM 全双工 |
| 19 | 音频路由参考价值 | 低，仅使用 `STREAM_MUSIC` 和普通 AudioRecord | 中，区分输入/输出设备并设置 preferred device | 只参考 Nojre 的设备枚举思想 |
| 20 | 蓝牙耳机参考价值 | 几乎没有，没有 SCO 路由 | 中，检测 SCO、监听状态并重建 Audio I/O，但 API 已过时 | 不复制实现；Android 12+ 应使用 communication device 路由 |
| 21 | 对 MotoCom 当前 WebRTC 主线的替代价值 | 很低，会失去 Opus、3A、jitter、SRTP | 很低，会失去 Opus、3A、jitter、FEC、SRTP | 两者都不应替代 WebRTC |
| 22 | 对 MotoCom 备用音频引擎的参考价值 | 低，只适合作为最简 PTT 原型 | 中，可作为同一 Wi-Fi 多人 PCM 实验参考 | 当前不新增备用引擎；先完成现有主链路验收 |
| 23 | 对 MotoCom Wi-Fi Direct 建链的参考价值 | 高：GO/Client 角色和 Socket 建链清晰 | 无 | Walkie-Talkie 的有效部分 MotoCom 已基本实现 |
| 24 | 对 MotoCom 蓝牙耳机路由的参考价值 | 无 | 中：设备枚举和路由变化后重建 I/O 值得研究 | 下一阶段可参考思路，但必须按 Android 12+/16 重写 |

## 关键取舍

### Walkie-Talkie 的定位

适合作为 Wi-Fi Direct 教学样例和 PTT 原型。它证明了无路由器时 Group Owner/Client + Socket 的最小闭环，但媒体实现存在 TCP 队头阻塞、裸 PCM、线程爆炸、静态 Socket 和现代权限缺失等问题。

### Nojre 的定位

适合作为“同一 Wi-Fi 多人持续对讲”的产品思路参考。它的持续采集、节点超时、延迟积压丢弃、每节点音量和输入输出设备枚举值得研究；裸 PCM、multicast 媒体、自建混音、固定播放追帧和旧 SCO 代码不适合迁移。

## 最终结论

### 1. 哪个更适合直接参考

**技术设计上 Nojre 更值得参考，但只能参考思想，不能直接复制 AGPL 源码。**

如果只看 Wi-Fi Direct 建链，则 Walkie-Talkie 更直接；如果看持续骑行通话、后台服务和多人体验，则 Nojre 更完整。

### 2. 哪个更适合作为备用链路

**Nojre 更适合作为“同一 Wi-Fi/随身路由器模式”的备用链路研究对象。**

它不适合作为无路由器备用链路，也不建议直接使用其裸 PCM 实现。真正实验时应保留 Opus 或 WebRTC 媒体能力。

### 3. 哪个更适合作为 Wi-Fi Direct 建链参考

**Walkie-Talkie。**

其 Group Owner 启动服务端、组员连接组长 IP 的角色划分清晰。不过 MotoCom 的 `WifiDirectTunnel` 已经实现同类能力，当前没有必要重复迁移。

### 4. 哪个更适合作为骑行产品体验参考

**Nojre。**

原因是它以持续全双工、前台服务、在线节点、多人混音和蓝牙麦克风为产品目标，更接近骑行对讲体验。实现细节仍需现代化重写。

### 5. MotoCom 是否应该继续 WebRTC 主线

**应该。**

当前 WebRTC 已在两台真机进入 CONNECTED，并持续完成录音和播放。WebRTC 已提供 Opus、AEC、NS、AGC、jitter buffer、FEC/PLC 和 DTLS-SRTP，替换它会重新制造两个参考项目已经暴露的问题。

### 6. MotoCom 是否应该新增 UDP multicast 实验引擎

**当前不应该。**

只有同时满足以下条件时再开实验分支：

- 产品明确需要三人以上群组对讲；
- 用户接受必须连接同一 Wi-Fi/随身路由器；
- 实测当前 WebRTC mesh 或发现机制无法满足需求。

即使实验，也应优先复用 Opus，不采用 Nojre 的裸 PCM 媒体格式。

### 7. MotoCom 是否应该新增 Wi-Fi Direct Socket 实验引擎

**不应该。**

MotoCom 已经拥有 Wi-Fi Direct Socket 信令链路。再增加 TCP 裸 PCM 音频引擎只会重复 Walkie-Talkie 的缺陷，并扩大维护面。只有未来 WebRTC 在明确机型上无法工作且证据充分时，才评估一个独立、压缩编码、UDP 优先的应急媒体通道，而不是复制现有参考实现。

## 决策摘要

- 保留：MotoCom 的 Wi-Fi Direct/LAN 双轨发现、TCP 信令、WebRTC/Opus 媒体、前台服务。
- 借鉴：Nojre 的持续采集、节点超时、低延迟丢弃原则和音频设备枚举思想。
- 不迁移：TCP 裸 PCM、UDP multicast 裸 PCM、自建 sample 队列混音、旧 SCO 路由、静态 Socket。
- 当前优先级：完成连续双向听音验收，然后单独修 VOX，再单独修 Android 12+/16 蓝牙通信路由。
