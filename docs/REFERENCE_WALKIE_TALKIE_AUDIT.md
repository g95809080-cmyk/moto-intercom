# Walkie-Talkie 参考项目审计

## 审计信息

- 上游仓库：`https://github.com/murtaza98/Walkie-Talkie`
- 本地快照：`reference_repos/Walkie-Talkie`
- 审计提交：`58c759b33277ddabd38b26eea402cef5afe94d78`
- 最后提交日期：2020-01-20
- 许可证：MIT

## 审计范围

本次实际阅读了：

- `README.md`
- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `MainActivity.java`
- `WifiDirectBroadcastReceiver.java`
- `SocketHandler.java`
- `ChatWindow.java`
- `MicRecorder.java`
- `AudioStreamingService.java`

## 1. 项目核心目标

该项目旨在不依赖蜂窝网络或无线路由器，通过 Android Wi-Fi Direct 让两台手机建立连接，并传输实时语音。README 将灾害通信和摩托车骑行列为主要使用场景。

它更接近一个 Wi-Fi Direct + TCP 裸音频的教学原型，不是现代 Android 的生产级 VoIP 实现。

## 2. 核心技术路线

完整链路如下：

```text
WifiP2pManager 搜索附近设备
-> 用户点击目标设备
-> WifiP2pConfig 建立 Wi-Fi Direct 组
-> Group Owner 启动 ServerSocket
-> Group Client 连接组长 IP
-> 双方通过一个 TCP Socket 直接传输 PCM
-> TALK/OVER 按钮控制麦克风采集
```

媒体层没有编解码器、RTP、抖动缓冲、丢包恢复、AEC、NS 或 AGC。

## 3. 设备发现方式

- 使用 Android `WifiP2pManager.discoverPeers()`。
- 动态接收 `WIFI_P2P_PEERS_CHANGED_ACTION`。
- 收到广播后调用 `requestPeers()` 获取 `WifiP2pDeviceList`。
- UI 根据设备名称展示附近节点，用户点击设备后发起连接。

优点是无需路由器；缺点是完全依赖旧版 Wi-Fi P2P 权限和广播模型，没有 Android 13+ 的 `NEARBY_WIFI_DEVICES` 适配。

## 4. 建链方式

- 用户点击设备后创建 `WifiP2pConfig`，使用设备 MAC 地址调用 `WifiP2pManager.connect()`。
- `ConnectionInfoListener` 根据 `isGroupOwner` 判断角色。
- Group Owner 在端口 `9584` 上创建 `ServerSocket`，只接受一个连接。
- 组员使用 `groupOwnerAddress` 和 500 ms 超时建立 TCP Socket。
- Socket 被放入静态 `SocketHandler`，供 Activity、录音线程和播放 Service 共享。

这是一对一单连接模型。静态 Socket 缺少明确所有权，Activity 重建、异常断线和进程回收时容易泄漏或失效。

## 5. 音频采集方式

- 使用原生 `AudioRecord`。
- AudioSource 为 `VOICE_RECOGNITION`。
- 采样率 16 kHz。
- 单声道、PCM 16-bit。
- 缓冲区来自 `AudioRecord.getMinBufferSize()`。
- 录音线程设置为 `THREAD_PRIORITY_AUDIO`。
- TALK 按钮启动录音，OVER 按钮通过静态布尔值停止录音。

严重问题：每读取一个音频缓冲区，就新建一个线程写 TCP；同时复用同一个 `audioBuffer`。这会造成线程数量失控、缓冲区覆盖、数据竞争和发送乱序。

## 6. 音频播放方式

- `AudioStreamingService` 创建原生 `AudioTrack`。
- 使用 `STREAM_MUSIC`、16 kHz、单声道、PCM 16-bit、流式播放模式。
- 后台线程持续读取 TCP InputStream 并写入 `AudioTrack`。

播放代码没有使用实际 `bytes_read`，而是每次写入整个缓冲区。最后一次短读可能重复播放缓冲区尾部的旧数据。

## 7. 音频编码/格式

- 没有压缩编码。
- 格式为 16 kHz、单声道、16-bit signed PCM。
- 理论裸流带宽约为 `16000 * 16 = 256 kbps`，尚未计算 TCP/IP 开销。

与 MotoCom 的 Opus 32 kbps 相比，带宽、丢包适应性和风噪环境表现都明显更差。

## 8. 传输协议

- 使用单个 TCP Socket 双向传输裸 PCM。
- 没有应用层帧头、长度、时间戳、序号或媒体类型。
- 没有媒体加密或身份认证。
- TCP 重传会造成实时语音队头阻塞。

该方案不能替换 MotoCom 的 WebRTC RTP/UDP + Opus 媒体层。

## 9. 是否支持全双工

结论：**产品交互按半双工 PTT 设计，不应视为可靠全双工实现。**

TCP Socket 本身是双向的，两端理论上可以同时启动录音线程，但 UI 使用 TALK/OVER 模式，且没有回声消除、混音和通话模式路由。双方同时说话时无法保证可用体验。

## 10. 是否支持一对一

支持。Group Owner 的 ServerSocket 只接受一个 Socket，整体架构也是单对单。

## 11. 是否支持多人

不支持。没有多连接管理、节点路由、混音或会议拓扑。

## 12. 是否依赖路由器 Wi-Fi

不依赖。它使用 Wi-Fi Direct 建组。

## 13. 是否可在没有任何 Wi-Fi 路由器时使用

可以，这是该项目最核心的能力。但仍要求两台设备的 Wi-Fi/Wi-Fi Direct 芯片正常工作，并满足系统位置或附近设备权限要求。

## 14. 是否适合骑行前后座

概念上适合，README 也明确列出摩托车场景；生产实践上不适合直接采用，原因包括：

- PTT 半双工不适合持续免按键交流。
- TCP 裸 PCM 对抖动和掉包不友好。
- 没有 3A、风噪处理和蓝牙通话路由。
- 生命周期、断线恢复和后台保活不足。
- 代码基于 Android 2019/2020 年的权限和 SDK 模型。

## 15. 是否适合头盔蓝牙耳机

不适合。

- 没有检测 Bluetooth SCO 输入设备。
- 没有申请现代蓝牙运行时权限。
- 没有设置 `MODE_IN_COMMUNICATION`。
- 没有选择 communication device。
- 播放使用 `STREAM_MUSIC`，可能走 A2DP，但麦克风仍可能来自手机，无法保证全双工 SCO。

## 16. Android 12+ / 13+ / 14+ / 16 兼容风险

### Android 12+

- targetSdk 28，仍使用旧 Support Library。
- Launcher Activity 未声明 `android:exported`，提高 targetSdk 后无法通过清单校验。
- 缺少现代蓝牙权限。
- Service 被声明为 `exported=true`，暴露面不必要。

### Android 13+

- 缺少 `NEARBY_WIFI_DEVICES`。
- 动态广播、Wi-Fi P2P 权限和设备信息访问需要重新适配。

### Android 14+

- 不是前台麦克风服务。
- 缺少 `FOREGROUND_SERVICE_MICROPHONE` 和对应 service type。
- 后台启动和持续录音容易被系统限制。

### Android 16

- 直接切换 Wi-Fi 的旧 API 已不可依赖。
- 旧广播、权限和后台 Service 行为风险更高。
- 没有现代通信设备路由，蓝牙表现不可控。

## 17. 可以迁移到 MotoCom 的设计

这里只建议迁移设计思想，不复制代码：

1. Wi-Fi Direct Group Owner 做 TCP 服务端、组员主动连接组长。MotoCom 已经实现。
2. 对 Wi-Fi P2P 状态、Peer 列表、连接状态分别处理。MotoCom 已经实现，可继续保持分层日志。
3. 音频相关自建线程应使用音频优先级。MotoCom 当前由 WebRTC 管理媒体线程；只有未来增加自定义 PCM 处理线程时才需要。
4. 明确展示发现、连接、断开状态。MotoCom 已升级为“发现、信令、媒体初始化、语音已连接”四阶段，优于该项目。

## 18. 不建议迁移的设计

1. 不迁移 TCP 裸 PCM 媒体。
2. 不迁移半双工 TALK/OVER 作为默认模式。
3. 不迁移每个音频缓冲区新建线程的写法。
4. 不迁移静态全局 Socket。
5. 不迁移忽略 `bytes_read` 的播放循环。
6. 不迁移普通、可导出的音频 Service。
7. 不迁移旧 Support Library、targetSdk 28 和旧权限处理。
8. 不用它替换 MotoCom 已经跑通的 WebRTC、Opus、3A 和 RTP 链路。

## 最终结论

Walkie-Talkie 最有价值的是“无需路由器的 Wi-Fi Direct 角色划分”，但 MotoCom 已经具备且实现更完整。其媒体代码只能作为早期原型反例，不应进入 MotoCom。
