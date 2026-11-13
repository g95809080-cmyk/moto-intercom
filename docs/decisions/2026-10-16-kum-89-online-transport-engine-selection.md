# ADR：公网对讲通信引擎选型评估（自建 WebRTC 扩展 vs 腾讯云 GME）

* **状态**：已采纳（Accepted）
* **日期**：2026-10-16
* **核验基线**：主工作区 `MotoIntercomApp` 的 `main` 分支（HEAD `a72cea7`，获批功能 Source `f587472e251b59f3c25e8cad5e483c8e0afc887a`，版本 `1.2.3`，`versionCode 7`）


* **关联议题**：`KUM-37`（在线音频会话所有权）、`KUM-76`（骑行修复与群组音频所有权）



---

## 一、 背景与问题定义

摩声（`MotoIntercomApp`）当前在 `main` 分支维护双人近场 `1.x` 产品线，在 `four-person` 分支维护四人 `2.x` 产品线。为解决骑行超出近场（LAN / Wi-Fi Direct）物理覆盖范围后的通信中断问题，并评估后续多人公网组队能力，需在以下两条技术路线之间做出架构裁定：

1. **路线 A（复用现有 WebRTC 栈扩展）**：基于项目已集成的 `stream-webrtc-android 1.3.9`（对应 `GetStream/webrtc m125.4`），配合自建带鉴权的信令服务、`coturn`（STUN/TURN）及后续开源 SFU。


2. **路线 B（引入商业游戏语音 SDK）**：接入腾讯云 GME（游戏多媒体引擎）SDK 作为公网对讲通道。

---

## 二、 核心对比矩阵（经源码与样本双向核验）

| 评估维度 | 路线 A：复用现有 `m125.4` WebRTC + 自建信令/`coturn` | 路线 B：引入腾讯云 GME SDK | 评审裁定 |
| --- | --- | --- | --- |
| **1. 双网共存与网卡绑定可控性** | 信令与 DNS（`Network.getAllByName`）可显式绑定目标 `Network`；原生媒体可通过 `libwebrtc` 的 `android_network_monitor` 与候选策略控制，并由 `selectedCandidatePair` 与抓包实测验证。 | GME 底层 `liteav` 虽包含 `com.gme.liteav.base.networkbinder.NetworkBinder`（支持按接口名查找 `Network` 并绑定 socket fd），但公开 API（`ITMGContext`/`ITMGRoom`/`ITMGAudioCtrl`/`ITMGPTT`）**零暴露绑定入口**。在 Wi-Fi Direct 存活、路由表变化时，其内部选网策略对宿主为不可控黑盒。 | **路线 A 胜出**<br> <br>（双网兜底要求路由可控、可观测） |
| **2. 端侧音频资源所有权与 3A 链路** | 100% 复用 `KUM-37` 已建立的 `JavaAudioDeviceModule`（ADM）、`RiderAudioEngine`、`VoxGate`（25ms attack 能量门控）与 `PcmTransmitGate`（静音门关闭时清零 PCM + 32kbps Opus DTX）。 | Android 10+ 虽允许并存采集，但存在硬冲突：<br> <br>1. **蓝牙 SCO 路由与 `AudioFocus` 抢占**（**前提条件**：GME 一旦开启采集/播放即会强制抢占并使现有路由失效；若仅信令待命而不开采集则传不了音频，陷入不可用死局）；<br> <br>2. **双套 3A 串联劣化**（WebRTC APM 硬件 AEC 优先+软件 NS与 GME AEC/NS 级联导致呼吸效应）； <br> <br>3. **四开关无法跨引擎混用**。 | **路线 A 胜出**<br> <br>（避免推翻 `KUM-37` 音频所有权设计） |
| **3. 切换时延与后台热备可行性** | 信令可低成本常驻；近场断线后按 `KUM-37` 顺序替换媒体会话。 <br> <br>• **通话端到端延迟**：预期量级 150–250 ms（TURN 中继），**待实测**。<br> <br>• **断线恢复耗时**：P50/P95 与重连成功率**待实测**。 | **“进房即计费”机制导致后台提前进房热备策略失效**。<br> <br>• **冷启动切换耗时**：近场断线后才发起鉴权与 `EnterRoom`，预期额外引入 1–3 秒进房握手量级，**待实测**。<br> <br>• **通话端到端延迟**：云链路+自适应抖动缓冲预期量级 200–400 ms，**待实测**（逼近 300ms 慢半拍感知阈值）。 | **路线 A 胜出**<br> <br>（GME 若用于兜底只能退化为 `ITMGPTT` 语音条降级） |
| **4. 变动流量成本 vs 固定运维投入** | **胜出但非零成本（变动转固定）**：<br> <br>• **TURN 流量费口径细分**：纯编码负载约 **57.6 MB/h/对**；计入协议头（IPv4/UDP/RTP/SRTP/TURN）后的 4 段总包吞吐约 **93.6–116.6 MB/h/对**；仅计入国内云单向出网计费的 2 段出网流量约 **46.8–58.3 MB/h/对**（开启 VOX DTX 后随说话占空比进一步下降），按 0.8 元/GB 折合云出网网费约 **0.037–0.045 元/小时/对**。 <br> <br>• **运维与基建投入**：需承担云主机固定月费，并自行维护 `coturn` 证书轮换、TURN REST 鉴权、安全配额与带宽监控。 | • **免费额度**：每日 700 分钟（月 21,000 分钟），**按 AppID 全局汇总计量且按自然日清零、不累积**（如 40 人同时在房，17.5 分钟即耗尽当日全网免费额度）。<br> <br>• **超额单价**：0.0059 元/分钟，双人在房折合 **0.708 元/小时/对**（约为自建 TURN 出网网费的 15 倍左右，且静音不免单）。 <br> <br>• *注：GME 另有按 PCU 峰值并发计费模式，规模化后单价可降，但不改变架构否决结论。* | **路线 A 成本更优**<br> <br>（需同步建设自动化运维与监控） |
| **5. 客户端包体积增量** | **0 MB**（直接复用现有依赖）。 | 解压后增加 **约 12.4 MB**（`libgmesdk.so`：`arm64-v8a` 7.6 MB + `armeabi-v7a` 4.8 MB，APK 压缩包约增 5–6 MB）。 | **路线 A 胜出** |
| **6. 多人车队现成特性 (`2.x`)** | 需在端侧改造会话管理并对接 SFU 信令/Token 协议与麦权控制。 | 现成具备 `SetRangeAudioMode`（范围语音）、`EnableSpatializer`（空间方位感）、`SetRecvMixStreamCount` 及 `ITMG_ROOM_TYPE_FLUENCY`（低码率抗丢包语音房）。 | **路线 B 现成度高**<br> <br>（但端侧架构割裂代价过大） |

---

## 三、 架构决议与关键前置约束

### 决议 1：`1.x` 双人主线（`main`）公网兜底采用「现有 WebRTC + 鉴权信令 + `coturn`」

1. **明确否决 GME 作为双网兜底通道**。
2. **先做单链路强制 `relay` 验证，再做自动切换**：


* 服务端启用 TLS（HTTPS/WSS），实施连接身份认证、配对授权校验与 TURN REST 动态凭证签发。


* 客户端核实 DNS、信令与 `libwebrtc` 原生媒体在 Wi-Fi Direct 存活期间对蜂窝网络（`TRANSPORT_CELLULAR`）的实际绑定情况；**iOS 端需同步补齐 `AVAudioSession` 配置、`UIBackgroundModes: audio` 后台音频模式、TURN 凭据注入，并解除 `GoogleWebRTCEngine.swift:33` 的非 host-only ICE 限制**。




3. **坚持单媒体会话顺序替换（Break-Before-Make）**：在未修改 `RiderAudioEngine.kt:223` 单媒体会话契约前，严禁在代码中硬塞 `activePc + standbyPc` 双轨并行逻辑。



### 决议 2：`2.x` 多人分支（`four-person`）严格区分「复用 ADM 资源」与「重构会话管理」，并与 `KUM-76` 边界一致

在 `2.x` 多人扩展（或未来任何需要多条 `PeerConnection` 并存的场景，包括双 PC 先通后断）中，**“采集/播放硬件资源可复用”绝不等于“现有会话管理可直接复用”**。本决议在群组音频所有权上**与 `KUM-76` 的群组/双人音频所有权边界保持严格一致**：

* **现状约束**：`KUM-37`（`docs/decisions/2026-07-19-kum-37-online-audio-session-ownership.md:15`）与 `RiderAudioEngine.kt:223` 强制约束**同一时刻最多允许一个活动媒体会话**，第二次调用 `openSession` 会直接失败。


* **2.x 必做前置改造项（`ADM 单例 + PC 多实例 / 多 Track 拓扑`）**：
在接入多人架构前，必须先重构 `RiderAudioEngine` 的会话生命周期管理，并通过以下 **3 条硬性验收标准**：
1. **采集单读者保证**：在多 `PeerConnection`（`four-person` 架构或 SFU 多路订阅）并发时，底层 `AudioRecord` / `JavaAudioDeviceModule` 依然维持单一采集读者，不发生重复初始化或句柄竞争。


2. **上行门控独立/一致生效**：`VoxGate` 与 `PcmTransmitGate` 的静音清零门控对每条活跃上行流正确生效，且不会因某一条流关闭而误停全局采集回调（防范 `AudioState::ShouldRecord()` 陷阱）。


3. **路由切换与 ICE 解耦**：蓝牙通信路由（`CommunicationDevice` / SCO）发生切换或系统电话中断恢复时，仅在 ADM 音频层消化，**严禁触发所有关联 `PeerConnection` 同时重建 ICE 或重协商**。





### 决议 3：单独立项评估 `2.x` SFU 选型（本 ADR 不锁定具体 SFU 供应商）

* 虽然 LiveKit 与 mediasoup 均属开源 SFU，但两者的**客户端接入改造量相差一个数量级**：


* **LiveKit**：官方 Android SDK 底层同样基于 `stream-webrtc`，与摩声当前 `stream-webrtc-android 1.3.9` 同源，更容易实现自定义 ADM 注入与单 PC 轨管理。


* **mediasoup**：需在客户端自行实现完整的信令状态机、`Device` 能力协商、Transport 建立与 Producer/Consumer 订阅管理。




* **行动项**：另行创建独立评估议题 **后续独立议题：2.x SFU 选型与 ADM 注入验证（待排期，不自动创建）**，将“客户端改造量与现有 `RiderAudioEngine` ADM 的兼容度”列为第一权重指标，而非单纯比较服务端转发性能上限。
* **备选降级预案**：若未来在特定独立产品形态中仍需使用 GME，房间类型必须强制指定为 **`ITMG_ROOM_TYPE_FLUENCY`**（严禁照搬 `ASMAX` 使用的 `HIGHQUALITY` 高码率音乐模式），且切换策略须改为“近场断线后先降级为 `ITMGPTT` 语音条留言，再择机进房”，以规避冷启动进房的延迟峰值。

---

## 四、 后果与技术权衡（Consequences）

采用本 ADR 路线 A 将带来以下明确的架构取舍，评审各方需知悉并接受：

1. **切换期听感中断（Break-Before-Make 取舍）**：因坚持单媒体会话顺序复用架构，当近场 Wi-Fi Direct 断线触发切入 4G 公网时，客户端需经历约 **1 秒级的听感静默/中断窗口**。此取舍换取了端侧音频状态机与蓝牙 SCO 资源管理的绝对确定性，避免了双 PC 并发带来的不可控冲突。
2. **运维复杂度上升**：变动成本转为固定运维成本，项目组需指派专人维护 `coturn` 集群、SSL 证书轮换及端到端网络质量监控。

---

## 五、 实测通过标准阈值表（To-Be-Tested Thresholds）

除常规功能测试外，上线前必须通过实车与模拟环境完成以下指标的闭环验证，各项数据需严格符合判定标准方可签收：

| 实测指标 | 实测通过标准阈值（Threshold） | 依据与背景说明 |
| --- | --- | --- |
| **公网 relay 通话延迟 P95** | ≤ 300 ms | 人类实时语音对讲的舒适对话门槛，超过此值即产生明显的“慢半拍”违和感。 |
| **断线到双向可听恢复 P95** | ≤ 3 s | 低于用户因掉线而开始重复大声呼叫的心理容忍时间上限。 |
| **重连成功率（连续 10 次断线测试）** | ≥ 9/10 成功 | 公网兜底通道在弱网或突发网络切换时的基本可靠性底线。 |
| **长静默 30 min 后首包时延** | ≤ 500 ms 且无破音/丢字 | 验证 `VoxGate` + `PcmTransmitGate` 归零 + Opus DTX 在长时间无 RTP 发送后的链路唤醒干净度与零吞字能力。 |
| **双网切换期静默时长** | ≤ 1.5 s（已知取舍，仅监控不阻塞） | Break-Before-Make 机制在实际环境下的听感代价上界，用于量化架构取舍。 |

---

## 六、 证据来源与时效免责声明（Evidence Disclaimer）

1. **本项目代码证据范围**：本 ADR 中涉及 `MotoIntercomApp` 的代码行号、提交哈希（`a72cea7` / `f587472`）及 WebRTC `m125.4` 行为结论，均以 2026-10-15《逐项核验报告》所检视的本地仓库快照与源码为准。


2. **第三方 GME SDK 样本时效限制**：本 ADR 中关于腾讯云 GME 内部实现（包括但不限于 `com.gme.liteav.base.networkbinder.NetworkBinder` 符号、12.4 MB `.so` 体积、`EnableAudio*` 四开关行为及 `ITMG_ROOM_TYPE_FLUENCY` 枚举）的核验结论，均提取自竞品 `ASMAX` 安装包内嵌的 GME SDK 样本（对应 `v1.1.19` 时期构建），**并非来自腾讯云公开源码承诺**。
3. **重入复核红线**：若未来任何分支决定正式引入腾讯云 GME，在编写业务代码前，必须针对届时实际拉取的目标版本 `.aar` / `.so` 重新执行符号与行为复核，确认以下三项前提未随版本升级发生变更：
* `NetworkBinder` 的存在性及是否有新开放的宿主绑定 API；
* `EnableAudioCaptureDevice` / `EnableAudioPlayDevice` / `EnableAudioSend` / `EnableAudioRecv` 四个开关是否仍保持独立解耦；
* `ITMG_ROOM_TYPE_FLUENCY` 枚举及对应的低码率抗丢包策略是否保留。
## 本次实施与证据边界（2026-10-17）

- 实施议题：[KUM-89](https://linear.app/kuma999/issue/KUM-89)。关联 [KUM-37](https://linear.app/kuma999/issue/KUM-37)、[KUM-76](https://linear.app/kuma999/issue/KUM-76)；[KUM-37 所有权决议](2026-07-19-kum-37-online-audio-session-ownership.md)。
- 上文商业 SDK 价格、体积与底层行为是选型时的历史样本；预期时延和切换代价不属于本次实测结果。正式采购或采用其他产品形态时需重新核验。
- 首版采用 Node.js 24 标准库 HTTPS 长轮询、coturn REST 凭证、现有 Android WebRTC 与 typed iOS TURN 配置。交付开发验证入口，先验证单条 relay 链路，再评审生产 coordinator 接入与自动切换。
- 每设备仍然只有一个音频 owner、一个 ADM、一个活动 PC。开发探针与生产 Service 互斥，不改变配对库、V2 协议或产品状态写者。
- [运行、接口与验收说明](../../tools/relay/README.md)。本机双模拟器不能替代蜂窝+Wi-Fi Direct、实车 P95、30 min 静默、iOS 原生 SDK/后台验收。
