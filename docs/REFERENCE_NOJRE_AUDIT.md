# Nojre 参考项目审计

## 审计信息

- 上游仓库：`https://github.com/hurui200320/Nojre`
- 本地快照：`reference_repos/Nojre`
- 审计提交：`178fb257e646302f4dd766e938ba7068ce9d74a2`
- 最后提交日期：2023-09-05
- 版本：1.5.3
- 许可证：AGPL-3.0

Nojre 代码受 AGPL-3.0 约束。除非 MotoCom 准备履行对应开源义务，否则不能直接复制或修改其源码。本文只提取可独立重新实现的工程思想。

## 审计范围

本次实际阅读了：

- `README.md`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `NojreForegroundService.kt`
- `NojrePeer.kt`
- `Utils.kt`
- `MainActivity.kt`
- `BroadcastDetailActivity.kt`
- `NojreAbstractActivity.kt`

## 1. 项目核心目标

Nojre 是一个为骑行群组设计的同一 Wi-Fi 局域网对讲 App。它不依赖互联网服务器，但依赖所有参与者连接同一个 Wi-Fi 网络。

项目重点包括：

- UDP multicast 自动发现和媒体广播。
- 多人同时讲话和本地混音。
- 密码派生密钥与 AES-GCM 包加密。
- 前台服务持续运行。
- 有 SCO 设备时尝试使用蓝牙麦克风。

## 2. 核心技术路线

```text
所有设备加入同一 Wi-Fi
-> 加入固定 UDP multicast 地址和端口
-> 周期广播昵称
-> AudioRecord 持续采集 PCM
-> PCM 加密后通过 multicast 发给所有节点
-> 每个远端节点维护 PCM 队列
-> 混音线程合成所有远端声音
-> AudioTrack 持续播放
```

Service 内部创建发送、接收、节点清理和混音四条执行链。

## 3. 设备发现方式

- 没有 Wi-Fi Direct，也没有 NSD。
- 所有节点加入同一个 multicast group。
- 每隔约 5 秒发送一次 advertise 包，内容为昵称。
- 接收方以来源 IP 作为节点 key。
- 节点超过 10 秒没有数据就从列表删除。

这是“媒体广播同时承担发现”的模型。它简单且适合多人，但依赖网络设备允许 multicast。

## 4. 建链方式

没有传统的一对一建链或 TCP 握手。

- Service 创建 `MulticastSocket`。
- 加入用户配置的 multicast 地址和端口。
- 所有节点直接向 multicast group 发包。
- 收到正确密码可解密的数据后，自动创建或更新 Peer。

该设计没有明确的 CONNECTED 状态，也没有链路级握手、候选地址协商或单节点连接确认。

## 5. 音频采集方式

- 使用 `AudioRecord.Builder`。
- AudioSource 为 `VOICE_COMMUNICATION`。
- 采样率 16 kHz。
- 单声道、PCM 16-bit。
- Buffer 为最小缓冲区的两倍，上限 60 KB。
- 创建后立即 `startRecording()`。
- 发送线程设置 `THREAD_PRIORITY_URGENT_AUDIO`。
- 录音持续运行，不依赖 VOX 或 PTT 开关。

持续采集是它对 MotoCom 当前 VOX 问题最有价值的启示：检测器不能通过关闭自己的采样源实现静音。

## 6. 音频播放方式

- 使用 `AudioTrack.Builder`。
- 输出为单声道 PCM float。
- 播放采样率固定为 16016 Hz，略高于采集的 16000 Hz，用于逐步追赶积压。
- 有 SCO 设备时使用 `USAGE_VOICE_COMMUNICATION`，否则使用 `USAGE_MEDIA`。
- 每个远端 Peer 有独立样本队列。
- 混音线程每次生成 256 个 float 样本，将所有节点样本相加并归一化后阻塞写入 AudioTrack。

固定增加播放采样率属于经验性时钟补偿，不如 WebRTC jitter buffer 和自适应时钟处理稳健。

## 7. 音频编码/格式

- 没有 Opus 或其他压缩编码。
- 网络格式为 16 kHz、单声道、16-bit little-endian PCM。
- 单节点媒体约 256 kbps，未计算 UDP 和 AES-GCM 开销。
- 播放前转换为 PCM float。

与 MotoCom 的 Opus 32 kbps 相比，不适合 Wi-Fi Direct 边缘距离或公路抖动环境。

## 8. 传输协议

### 网络层

- UDP multicast。
- 单个组地址和端口承载发现与音频。
- 没有 ACK、重传或排序。

### 应用层

- 加密包首字节 `0x01` 表示 AES-GCM。
- 后续为 12-byte IV 和密文。
- 解密后的首字节区分 advertise `0x01` 和 audio `0x02`。
- 密钥为密码 UTF-8 字节的 SHA-256 结果。

AES-GCM 能保护内容完整性和机密性，但密码派生没有 salt、迭代或慢 KDF，也没有重放保护。

WebRTC 已有 DTLS-SRTP，MotoCom 不应再复制这层媒体加密。

## 9. 是否支持全双工

支持。AudioRecord、UDP 收发和 AudioTrack 混音持续并行运行，多人可以同时讲话。

但它没有 AEC。手机外放时，扬声器声音可能再次被麦克风采集并广播，造成回声或啸叫。

## 10. 是否支持一对一

支持一对一作为多人广播的子集，但没有“只连接指定对象”的隔离机制。同一组地址、端口和密码的节点都会收到媒体。

## 11. 是否支持多人

支持。

- 每个来源 IP 对应一个 `NojrePeer`。
- 每个 Peer 有独立 PCM 队列和音量。
- Mixer 将所有 Peer 的样本相加。

风险是按单个 sample 使用 `ConcurrentLinkedQueue`，节点增加后对象、内存和 CPU 开销较大。

## 12. 是否依赖路由器 Wi-Fi

依赖同一个 Wi-Fi 广播域。正常用法是所有设备连接同一个路由器、随身 Wi-Fi 或热点。

## 13. 是否可在没有任何 Wi-Fi 路由器时使用

不能可靠使用。

理论上可以让一台手机开启热点，但 README 明确记录：Soft AP 主机设备无法收到 multicast 包，只有客户端正常。因此热点主机通常不能作为完整参与者。

它不具备 MotoCom Wi-Fi Direct 那种真正无路由器双端互联能力。

## 14. 是否适合骑行前后座

在有随身路由器或第三台热点设备时具有一定实用性：

- 持续全双工。
- 多人支持。
- 前台服务保活。
- 节点超时和低延迟优先策略。

对于仅两台手机、无任何路由器的前后座场景并不理想；裸 PCM 带宽、multicast 兼容性和缺少 3A 也是明显问题。

## 15. 是否适合头盔蓝牙耳机

有尝试，但不适合直接作为现代 Android 方案。

现有实现会：

- 检查 `isBluetoothScoAvailableOffCall`。
- 枚举输入设备中的 `TYPE_BLUETOOTH_SCO`。
- 为 AudioRecord 和 AudioTrack 设置 preferred device。
- 监听 `ACTION_SCO_AUDIO_STATE_UPDATED`。
- 调用 `startBluetoothSco()` 和 `isBluetoothScoOn=true`。
- SCO 状态变化时重建 AudioRecord 和 AudioTrack。

值得借鉴的是“输入和输出设备分别枚举，并在路由变化后重建 Audio I/O”的思想。旧 SCO API 和连接后切换 `MODE_NORMAL` 的实现不适合 Android 12+/16。

## 16. Android 12+ / 13+ / 14+ / 16 兼容风险

### Android 12+

- Manifest 没有 `BLUETOOTH_CONNECT`，但代码主动操作 SCO，存在权限异常风险。
- `startBluetoothSco()` 和 `isBluetoothScoOn` 已是旧路由方式。

### Android 13+

- targetSdk 33，已声明 `POST_NOTIFICATIONS`。
- 没有 Wi-Fi Direct，所以不需要 `NEARBY_WIFI_DEVICES`；但 multicast 仍依赖局域网和厂商网络策略。

### Android 14+

- Service 声明了 `foregroundServiceType="microphone"`，但缺少 `FOREGROUND_SERVICE_MICROPHONE`。
- Android 14 对后台启动麦克风前台服务限制更严格。
- 在 `onCreate()` 中直接启动前台并初始化部分硬件，状态恢复和权限时序需要重新审视。

### Android 16

- 旧 SCO API 路由可靠性更差，应改用 `setCommunicationDevice()`。
- multicast 在厂商 ROM、省电策略、热点和无互联网 Wi-Fi 下仍可能不稳定。
- Compose Snapshot State 容器被多个裸线程直接修改，存在并发一致性风险。
- `joinGroup(InetAddress)` 等旧 API 已过时，需要绑定正确 NetworkInterface 的现代写法。

## 17. 可以迁移到 MotoCom 的设计

只建议独立重新实现以下思想：

1. **持续采集原则**：VOX 不能关闭自己的 PCM 观测源。
2. **低延迟优先**：自定义队列积压超过阈值时丢弃旧音频，而不是追求完整播放。
3. **节点存活时间**：局域网在线列表可以使用 `lastSeen` 和超时淘汰。
4. **音频线程优先级**：仅用于 MotoCom 自建的 PCM 分析线程，不干预 WebRTC 内部线程。
5. **设备枚举思想**：蓝牙阶段先确认 SCO 输入和输出设备真实存在，再决定路由。
6. **每节点独立状态/音量**：仅在未来确认多人对讲需求时采用。
7. **MulticastLock**：只有未来确实引入 multicast 时才需要；当前 UDP broadcast/NSD 不应为了参考项目增加它。

## 18. 不建议迁移的设计

1. 不用 UDP multicast 裸 PCM 替换 WebRTC。
2. 不迁移 256 kbps/人的未压缩媒体格式。
3. 不复制 `REPLAY_RATE=16016` 的固定追帧方法。
4. 不复制按单个 sample 入队和轮询的混音器。
5. 不重复实现 WebRTC 已提供的抖动缓冲、Opus、AEC、NS、AGC、FEC 和 SRTP。
6. 不复制旧 `startBluetoothSco()`、`isBluetoothScoOn` 和切回 `MODE_NORMAL` 的路由流程。
7. 不把 multicast 作为无路由器的主连接方案。
8. 不直接复制 AGPL 源码。
9. 不在当前一对一验证阶段提前引入多人混音架构。

## 最终结论

Nojre 比 Walkie-Talkie 更接近持续全双工骑行对讲，值得借鉴实时性、持续采集、节点超时和设备枚举思想；但其裸 PCM、多播媒体、自建混音和旧 SCO 路由不应进入 MotoCom。MotoCom 应继续保留 WebRTC/Opus 主线。
