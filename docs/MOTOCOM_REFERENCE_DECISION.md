# MotoCom 参考架构决策

决策日期：2026-06-27

依据：

- MotoCom 当前双真机验证结果。
- Walkie-Talkie `58c759b` 源码审计。
- Nojre `178fb25` 源码审计。

## 总结结论

1. **MotoCom 正式主线继续使用 WebRTC。** 当前 WebRTC 已解决 AAR 缺类，双端完成 PeerConnection、SDP、ICE、录音和播放，并保持超过 60 秒。现在换引擎没有收益。
2. **Nojre 的 UDP multicast 只适合作为未来同一 Wi-Fi 多人模式的隔离实验，不进入当前主线。**
3. **Walkie-Talkie 适合作为无路由器 Wi-Fi Direct 建链的概念参考，不适合作为媒体实现参考。**
4. **无路由器、两台手机直连参考 Walkie-Talkie 的网络拓扑，但继续使用 MotoCom 的 WebRTC 媒体。**
5. **同一 Wi-Fi 下多人骑行对讲参考 Nojre 的发现、节点存活和混音产品模型，但不能直接复制其 AGPL 源码或裸 PCM 实现。**
6. **前后座一对一优先级：WebRTC > Wi-Fi Direct Socket 实验媒体 > UDP multicast。** Wi-Fi Direct 是连接方式，不应被误认为必须替换 WebRTC 媒体。
7. **OPPO Enco X3/头盔耳机问题只有 Nojre 有部分参考价值。** 可借鉴输入输出设备枚举和路由变化后重建音频 I/O 的思想；旧 SCO API 不可复制。
8. **降低 WebRTC 依赖风险应使用独立 Git 实验分支，而不是在主线引入双引擎抽象。** 先做最小原生 AudioRecord/AudioTrack 验证，再决定是否加入 Opus 和 UDP。

## 推荐路线

### 正式主线

```text
Wi-Fi Direct + LAN/NSD 双轨发现
-> 首条成功链路胜出
-> 4-byte length + JSON 的 TCP 信令
-> WebRTC host ICE candidate，无公网 ICE Server
-> Opus 32 kbps + AEC/NS/AGC + RTP/UDP + DTLS-SRTP
-> 前台 Service 持有完整生命周期
-> AudioRouteController 负责 Android 通信设备路由
```

正式主线继续保留当前层次：

- `WifiDirectTunnel`：无路由器 Wi-Fi Direct 建组及信令 Socket。
- `IntercomService`：前台保活、双轨发现、链路择优和状态管理。
- `IntercomManager`：TCP 信令帧、身份、Offer/Answer/Candidate 交换。
- `RiderAudioEngine`：WebRTC、Opus、3A、媒体状态和音频轨。
- `AudioRouteController`：手机外放与蓝牙通信设备路由。
- `MainActivity`：权限、用户控制和状态展示。

### 无路由器一对一

推荐组合：

```text
参考 Walkie-Talkie 的 Wi-Fi Direct GO/Client 拓扑
+ MotoCom 现有 WifiDirectTunnel
+ MotoCom TCP 信令
+ MotoCom WebRTC 媒体
```

不采用 Walkie-Talkie 的 TCP 裸 PCM、静态 Socket、PTT 默认模式或普通后台 Service。

### 同一 Wi-Fi 多人模式

未来确认产品需求后，可开独立实验：

```text
参考 Nojre 的 multicast advertise / lastSeen / peer volume 产品模型
-> 先验证路由器和厂商 ROM 的 multicast 可达性
-> 媒体仍优先使用压缩编码
-> 明确人数、带宽、混音和回声策略
```

该模式不能取代无路由器主线，因为 Soft AP 和部分 AP 会过滤 multicast。

## 三条路线排序

针对“前后座一对一实时通话”：

| 排名 | 路线 | 判断 |
|---:|---|---|
| 1 | WebRTC | 正式主线。已有双真机成功证据，具备 Opus、3A、jitter、FEC/PLC 和 SRTP |
| 2 | Wi-Fi Direct Socket 实验媒体 | 仅作为诊断或应急实验；网络可无路由器，但不能使用 TCP 裸 PCM 作为正式方案 |
| 3 | UDP multicast | 不适合一对一无路由器主场景；价值主要在同一 Wi-Fi 多人广播 |

这里的 Wi-Fi Direct Socket 与 WebRTC 并不冲突：Wi-Fi Direct 提供 IP 网络，WebRTC 在该网络上发送媒体。MotoCom 当前就是这种组合，只是 Socket 负责信令而非裸音频。

## 蓝牙耳机路线

### 参考价值

- Walkie-Talkie：没有有效参考价值。
- Nojre：有中等设计参考价值，包括：
  - 分别枚举 SCO 输入和输出设备。
  - 设置 preferred device。
  - 监听音频路由状态变化。
  - 路由变化后重建录音和播放对象。

### 不能复制的部分

- `startBluetoothSco()`。
- `isBluetoothScoOn`。
- SCO 连接后切换 `MODE_NORMAL`。
- 未申请 `BLUETOOTH_CONNECT` 的权限模型。

MotoCom 后续应基于 Android 12+ 的 `availableCommunicationDevices`、`setCommunicationDevice()` 和设备回调单独实现，并继续让 WebRTC 管理录放音。

## 降低 WebRTC 依赖风险的实验分支

只有出现新的、可重复的 WebRTC 依赖或机型兼容故障时，才创建实验分支，例如：

```text
experiment/native-audio-transport
```

实验分支遵循最小路径：

1. 复用现有 Wi-Fi Direct/LAN IP 链路，不改发现协议。
2. 使用原生 `AudioRecord` + `AudioTrack` 验证持续双向 PCM。
3. 使用 UDP unicast，而不是 TCP 裸 PCM或 multicast。
4. 第一阶段只验证采集、发送、接收、播放和线程生命周期。
5. 只有原生链路稳定后，再评估独立 Opus 编解码。
6. 不在正式主线预先加入 `AudioEngine` 工厂、双实现接口、运行时切换 UI 或额外配置。
7. 实验成功且确有生产价值后，再设计最小合并边界。

这样可以验证“WebRTC 之外是否存在可行媒体链路”，同时避免把实验复杂度带入已工作的主线。

## 当前主线必须保留

1. WebRTC + Opus 32 kbps。
2. AEC、NS、AGC 和通信音频模式。
3. Wi-Fi Direct 与 LAN/NSD 双轨发现。
4. 首条 TCP 信令隧道成功后关闭另一发现轨道。
5. 4-byte length + JSON 信令帧。
6. IDENTITY 昵称交换。
7. PeerConnection 真连接状态驱动 UI，禁止 TCP 假阳性。
8. 前台服务管理硬件和媒体生命周期。
9. Android 12+/14+ 权限和前台服务声明。
10. 当前 WebRTC Maven 依赖及完整初始化日志。
11. VOX 可回滚旁路，直到正确门控方案验证完成。

## 应放入实验分支

1. UDP multicast 多人发现和广播。
2. 多 Peer 音频队列、独立音量和本地混音。
3. 原生 AudioRecord/AudioTrack 备用媒体引擎。
4. 非 WebRTC Opus 编解码链路。
5. Wi-Fi Direct UDP unicast 应急媒体通道。
6. PTT 模式。
7. 新的 VOX 门控实现，先在实验分支验证不会停止采集。

蓝牙路由不是备用媒体引擎，应在独立修复分支中处理，不与上述实验同时进行。

## 不推荐路线

1. 不推倒当前 WebRTC 主线。
2. 不将 Nojre 的 UDP multicast 裸 PCM 合入正式版。
3. 不将 Walkie-Talkie 的 TCP 裸 PCM作为备用正式引擎。
4. 不增加运行时三引擎切换器。
5. 不同时开发 VOX、蓝牙、多播和原生备用引擎。
6. 不复制 Nojre AGPL 源码。
7. 不复制两个项目中的旧 Android 权限、Service 或 SCO 实现。
8. 不为了“可能以后多人”提前加入混音器和多节点媒体状态机。

## 分阶段执行计划

### 阶段 0：锁定当前基线

- 保持 WebRTC 依赖不变。
- 保留已通过的 60 秒连续采集/播放证据。
- 将 VOX 旁路作为可回滚变更单独提交。

### 阶段 1：完成一对一主观听音验收

- 两台手机不连接蓝牙耳机。
- A 说话、B 听；B 说话、A 听。
- 静音 10 秒后再次说话。
- 保持至少 60 秒。
- 若无声，只增加 ICE 和 WebRTC `getStats` 观测，不换引擎。

### 阶段 2：单独修复 VOX

- 保持 PCM 检测源持续工作。
- 在不会停止采集的环节实施门控。
- 验证安静、起声、长静音恢复和弱声场景。

### 阶段 3：单独修复蓝牙通信路由

- 只处理 Android 12+/16 communication device。
- 使用 OPPO Enco X3 和头盔耳机真机验证输入、输出及断线回退。
- 不同时修改 WebRTC、网络或 VOX。

### 阶段 4：增强观测性

- 输出 ICE gathering/connection state。
- 输出 inbound/outbound RTP bytes、packets、packet loss 和 jitter。
- 记录真实输入和输出 AudioDeviceInfo。

### 阶段 5：需求驱动的实验

- 只有确认多人需求后才建 UDP multicast 实验。
- 只有 WebRTC 出现无法接受的重复故障后才建原生媒体实验。

## 风险清单

| 风险 | 影响 | 当前策略 |
|---|---|---|
| WebRTC AAR 版本或 ABI 再次不一致 | 媒体初始化失败 | 固定已验证 Maven 版本，不同时引入本地 AAR |
| Host ICE candidate 在部分厂商网络不可达 | 停在 CONNECTING/FAILED | 增加 ICE 与 RTP stats，按机型复测 |
| VOX 关闭本地音轨导致采集停止 | 静音后永久无法恢复 | 当前旁路，后续单独设计门控 |
| Android 16 蓝牙路由 API 变化 | 耳机已连接但仍走手机外放 | 独立使用现代 communication device API 修复 |
| 手机外放全双工回声或啸叫 | 骑行体验不可用 | 保留 WebRTC AEC，扬声器和耳机分别测试 |
| Wi-Fi Direct 厂商差异和 BUSY | 无法发现或建组 | 保留 LAN 双轨、复位和真机矩阵测试 |
| Multicast 被 AP/热点过滤 | 多人实验不可发现或收包 | 只作为同一 Wi-Fi 实验，不承诺无路由器 |
| 多人 mesh 带宽和 CPU 增长 | 掉帧、发热、延迟 | 没有明确需求前不实现 |
| 参考项目许可证 | 法律和发布义务 | Nojre 只参考思想，不复制 AGPL 代码 |
| 多分支同时改音频链路 | 难以定位回归 | 一次只处理一个变量，每阶段真机复测 |

## 下一步只允许做什么

下一步只允许完成以下一件事：

**在不连接蓝牙耳机的两台真机上，对当前 WebRTC + VOX 旁路版本完成主观双向听音验收。**

允许的动作：

- 保持当前 WebRTC 依赖、UI、连接协议和网络架构不变。
- 轮流说话、静音 10 秒后恢复、持续 60 秒。
- 抓取现有日志。
- 如果听不到，只增加 ICE 和 `getStats` 诊断日志。

当前不允许：

- 修蓝牙路由。
- 恢复或重写 VOX。
- 引入 UDP multicast 媒体。
- 引入 Wi-Fi Direct 裸 Socket 媒体。
- 更换 WebRTC 依赖。
- 增加多人混音或运行时多引擎切换。
