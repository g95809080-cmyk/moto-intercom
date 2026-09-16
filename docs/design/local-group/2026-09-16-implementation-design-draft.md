# 四人离线对讲：实施前设计草案

日期：2026-09-16。状态：DRAFT / 未批准源码门禁。

需求依据：`docs/product/2026-09-16-motocom-local-group-intercom-prd.md`。
Base / Head：`0f808ad44f70976624f69d242bfb0a72aa3f77f2`，源码基线 `d479ec1dec5c4c197785d69b88d0872a366946cb`。

本稿把已确认需求落实到实现边界，不创建第二套产品规则。Linear 在首次真实查询即返回 `UNAUTHORIZED / oauth_token_invalid_grant`，无法核实当前 issue、依赖和 Exit Criteria。因此本稿尚未绑定 issue/Rasen change/实现分支；不虚构编号，不据此启动行为变更。仅在指定 worktree 创建本地未提交设计与核查记录，恢复授权后迁入真实 issue 的 change。

## 1. 已核实源码事实

| 事实 | 证据与影响 |
| --- | --- |
| 产品事件串行消费 | `SessionOrchestrator.kt` 使用 Channel；仍作为唯一产品状态写入者。不能额外建一个会独立写产品状态的群组 Service。 |
| 音频上下文是单连接 | `AudioSessionController.kt` 只有 activeLease/activeSession；`RiderAudioEngine.kt` 的 PeerConnection、sender、remoteDescriptionSet、pendingRemoteCandidates 和 activeSession 都是单例字段。必须整体分离每对成员上下文，不能仅删除断言。 |
| 电话协调也假定单会话 | `CommunicationAudioCoordinator.beginMediaSession()` 检查 mediaActive，endMediaSession 会释放焦点、暂停路由和引擎。群组必须按公共媒体需求的 0→1 / 1→0 转换调用，不能每个 peer 各调用一次。 |
| 当前 ID 是安装稳定 ID | `DataStoreLocalIdentityStore.getOrCreateDeviceId()` 通过 DataStore 原子 edit 持久化 UUID；进程重启不重新生成。它是身份声明，不是签名密钥，不证明持有者真实性。 |
| V2 严格校验 | `SignalingV2Codec` 版本固定 2，requireKeys 拒绝未知字段。新增群组协议需要独立入口和 codec。 |
| 已有 P2P DNS-SD | `WifiDirectTunnel` 发布 app/version/device/nickname/runtime，随后按单目标 attempt 建连；该适配器的清组/重连行为不能直接用于正在运行的四人队伍。 |
| Rasen root 正确 | 带 DO_NOT_TRACK=1、RASEN_TELEMETRY=0 执行 context --json，root 为当前 worktree；未新建 change。 |

## 2. 组件与资源所有权

保留旧双人协议、恢复及配对路径。SessionOrchestrator 的串行循环内增加互斥模式与群组 reducer；UI 只投递意图，IntercomService 只执行带上下文的 effects。群组 reducer 为纯状态转换，不启动线程、不碰 Android、不写数据库。

| 所有者 | 资源 | 释放边界 |
| --- | --- | --- |
| Service 当前 runtime | 模式、generation、前台服务 | 用户停止或 Service 销毁 |
| 当前 RoomInstance | 房间网络、发现、控制监听、成员表、准入/限制、心跳 | 房主结束或本机明确离队；电话不释放 |
| 公共 GroupAudioSession | 一份 ADM/factory/source/local track、RTC executor、VOX、焦点/路由 | 无媒体需求时停止采集；全组退出释放资源 |
| 每个 PeerLink | PeerConnection、sender、remote track、SDP/ICE 队列、retry/lease | 仅此 peer 的失败、离队、移除或重建 |

公共音频构造必须默认禁止采集，再由有效远端媒体需求、焦点/路由、用户静音/VOX共同控制。仅把 local track disabled 或 ADM mute 当作“不采集”证据不够；空队必须观察 recording callback/实际 AudioRecord 生命周期。实现前先验证已用 WebRTC 版本的公共录音开关语义，防止某条 PeerConnection 调用 setAudioRecording(false) 暂停其他成员。

旧双人路径依然限定一个 PeerLink。群组最多三条本机 peer link；容量规则集中为 GroupPolicy(maxMembers=4)，不在 UI/codec 中散落固定四席位。

## 3. 身份与消息边界

新增 RoomInstanceId（每次创建随机 UUID）、HostRuntimeId、RoomRosterRevision（名单变化递增，仅用于名单新旧排序）、MembershipIncarnation（每名成员每次准入独立生成）、LocalIntentGeneration、PeerLinkGeneration。所有异步回调携带不可变上下文 `(localRuntime, room, localIntentGeneration, localIncarnation, peerId, peerRuntime, peerIncarnation, peerLinkGeneration)`，effect 开始和资源转交前都校验。结束时先撤销资格再异步释放，迟到 success 只能关闭自己创建的资源。

新群组服务类型和 magic/version 独立于旧 V2，不向旧发现入口投递群组广告。群组 envelope 至少含 version、room、host runtime、sender、recipient、两个端点的 membership incarnation、message type、sequence；codec 严格拒绝不支持版本、未知类型、重复字段、非法长度、越界容量与不匹配目标。明确 max frame（初拟 64 KiB）、握手期限（初拟 10 秒）、未认证并发上限（初拟 4），在对应 issue 测试中固化；消息中不记录明文口令。

准入前只允许有界握手/版本交换。房主当前控制通道验证口令及 socket 上的当前身份后，原子裁决容量和限制，签发本次运行时成员资格。SDP/ICE 只允许有效名单中两个端点互发，房主仅转送信令、不转送音频。接收方必须绑定已认证控制通道和最新名单；成员不能伪造 END、REMOVE、UNBLOCK 或任意替换 sender。过期 revision 的名单不覆盖新状态，序号/nonce 防止旧消息重放。

重新使用现有 deviceId 维护移除限制。正常 App 进程重启仍受限制。若要抵御主动冒用 deviceId，需要设备持有证明及绑定机制；现有 UUID 不足以作该承诺。密钥、凭据握手与发现流程必须一起审查，不能在未定身份认证时先打开媒体。

## 4. 无共同 IP 网络的凭码发现与建网

首选候选网络：房主主动创建 Wi-Fi Direct group，同时作为产品房主和 GO；成员连接同一 group。房主只提供协调服务，媒体仍 full mesh。客户端间 IP/UDP 可达性、三个客户端容量和持续发现是三机/四机探针门禁，尚无真机证据。若某 OEM 隔离客户端，显示网络不支持，不伪造互通；是否切换 local-only hotspot 路径需要相同人数/可达性验证，不能静默变成云转发或路由器前提。

已确认普通用户只输入六位码，无普通房主逐人审批。操作系统的网络确认是单独阶段，超时和权限拒绝应返回网络准备状态。

**尚未收敛的实现阻断点：预 IP 凭码筛选。** 现有 DNS-SD 能发现服务，但不能单凭匿名广告安全地验证六位码。不能公开六位码；也不能把 SHA256(code + public salt) 或 HMAC(code, public nonce) 当成保密方案：六位空间可离线穷举。未验证这一点前不批准广播口令派生 verifier。

待验证方案是只广播不含口令验证材料的短期房间发现身份，通过预 IP 双向通道做成熟 PAKE/等价口令认证，成功后才把候选标成匹配，再请求系统加入 P2P 网络。BLE GATT 是可调查的预 IP 通道，不是已确定依赖；必须核实 Android 23+ 权限、外设支持、蓝牙耳机共存、同码多队时多候选握手以及合适的密码库。另一候选是先经系统临时加入候选 P2P 网络验证，但它可能造成逐个候选的系统操作，需证明不破坏已确认的普通入队体验。不能把设计未收敛转嫁为未经请求的新产品规则。

匹配结果须绑定 `roomId + host runtime + 当前通道身份`，完成一个有截止时间的发现窗口后：零个匹配显示未发现；一个匹配自动继续；多个匹配显示房主昵称/短房间标识选择。网络发现超时、握手版本不兼容和明确的凭据拒绝分别呈现，不将未知候选直接报告口令错误。重连只查原 room/host runtime，不重复普通凭码选房。

网络与认证方案落实前，可以在正式 issue 下做不产生网络/媒体 effects 的域模型；不能将共同 LAN 原型标作完整产品。

## 5. 房主准入与成员状态

房主在 SessionOrchestrator 中串行裁决，时间使用单调时钟；名单包含房主、ADMITTED、RECONNECTING_RESERVED。未经认证临时握手不占席位，受独立资源上限与期限约束。认证完成后原子检查 `(room current, version supported, identity bound, not removed, capacity)` 再准入；并发最后一席只成功一个。重试使用 `(roomId, deviceId, clientRuntimeId, requestId)` 幂等，缓存只存当前房间，最多 128 项，单项自首次请求起 30 秒到期（均为待 issue 固化的参数），并受已有当前资格二次检查；终态移除/结束立即撤销成功重放资格，返回当前拒绝状态，不能重复增加成员或无限续期。

同 deviceId、新 clientRuntimeId 必须作为用户再次输入口令后的显式 JOIN，重新完成当前通道身份与口令验证；不是自动恢复。若原席位仍保留，原子替换这一席位并生成新 incarnation，不多占一席；若已释放，按现有容量重新裁决。旧 runtime 所有通道及回调撤销。被移除检查先于任何席位替换；同时存在两个 runtime 时只允许最新获准 incarnation，旧方收到 superseded 后停止恢复。`RESUME` 只接受同一 client runtime、原 room、当前 incarnation 与有效运行时参与意图。每个成员控制 socket 另设 ControlConnectionGeneration；替换连接时单调增加，所有 close/error/heartbeat 都绑定该 generation，旧 socket close 不改变新连接状态或重新启动占位计时。

成员生命周期：ADMITTED → RECONNECTING_RESERVED(deadline=firstDetectedLoss+60s) → RELEASED_WAITING；保留期间恢复沿用席位，deadline 不因重复掉线消息延后。边界 `now >= deadline` 先回收再准入。RELEASED_WAITING 不算占位，有空位可重新准入、满员等待，不驱逐新人。新准入只替换该成员的 membership incarnation，使相关旧 PeerLink 和迟到移除/离队回调无权操作新资格。RoomRosterRevision 增加不使其他端点的健康链路失效；例如 C 入队不能重建 A–B。

检测建议参数（待对应 issue 验证）：控制心跳 2 秒、连续 3 次未收到进入失联；网络明确丢失可立即进入。60 秒从检测事件算起，不从最后一个音频包算起；静音/VOX无声不能触发掉线。单条媒体失败仅重建这一对，不能直接把整名成员从房主名单踢下线。保留期重试建议 2/4/8 秒封顶；释放后 15 秒加最多 3 秒抖动低频寻找，队伍有效性/参与意图每次检查。

主动离队立即销毁本机恢复意图并清除本次成员静音；房主主动结束需 UI 确认，认证 END 终止所有成员资格和恢复。收不到 END 的网络失联只能显示恢复/重建建议。房主短暂失联不换主；房主进程重启创建全新 room/runtime，旧成员不得因同码自动迁入。

REMOVE 在房主串行循环中先加入 removedDeviceIds，再撤销名单、停止授权和通知；阻断重连和重新输码。UNBLOCK 只移除本次限制，不自动加入/开麦。进程重建仅恢复本机昵称/deviceId/既有个人设置，不持久化活动房间、口令、资格或恢复意图。

## 6. 媒体、来电与真实就绪

每对成员使用独立 PeerLink；offer 角色按已认证稳定身份排序确定，防止并发 glare。每个链路独立 SDP/ICE buffer、deadline 和恢复 generation。共享 local track 只挂到有效 peer，远端各 track 混音能力先以当前 WebRTC 版本三机验证，再四机验证。三人三对、四人六对；房主三条连接不代表其他三对已通。

自静音/VOX 作用于本地发送；blocklist 按稳定 peer ID 控制本机 remote track 音量，重连重新应用，不能通过禁用公共 playout 实现单人屏蔽。仅本机保存，不广播屏蔽对象。

电话/焦点丢失暂停公共录音和本机播放，保持 room、P2P、控制通道和其他设备健康 PeerLink。向房主发布通用 AUDIO_UNAVAILABLE，不能广播 PHONE_RINGING 等原因。恢复必须同时满足参与意图、当前 generation、系统焦点和已验证路由；不得清空原有静音设置。

状态分层：network preparing / joined / local audio unavailable / pair verifying / partial / all ready。每对保存双方 endpoint 的媒体传输、接收、音频 I/O 和路由确认及当前 lease；ICE/TCP connected 仅是候选可用，不足以 all ready。声音静默不构成失败，媒体证据过期/重建需重新验证。尚无音频证据显示“已连接，语音待确认”，不承诺自动检测真实耳机可听。最终验收人工逐对双向听音；就绪判定的 WebRTC stats/音频回调具体字段须由媒体探针确定。

## 7. 恢复授权后的独立审查单元建议

这是待与真实 Linear 比对的依赖建议，不是已建立的新路线，不新增虚构 issue。

1. 纯域模型：只在单测中运行，不接 Android/网络/媒体 effects，不新增 wire codec；输入抽象的已验证身份/凭据判定，不能由 UI 或未认证广告自行构造授权。覆盖口令生命周期、容量原子性、60 秒边界、removed 集合、generation、权限和部分就绪关系。依赖：真实 issue 绑定及本范围架构批准。wire codec 和真实准入认证作为后续明确范围，待握手方案/字段审定后实施。
2. LAN 三人再四人媒体：公共资源与 per-peer 上下文拆分、调用隔离、异常清理；依赖：1，以及三机/四机 native 探针。
3. 预 IP 认证发现与 P2P 建网：依赖：已审查凭码方案及设备矩阵；验证同码多队、旧版隔离、网络中断不清健康房间。
4. 独立多人 UI / 生命周期集成 / 回归：依赖前三项；其后做两小时和四小时硬件观察。

每次只有一个活动 issue/change/实现分支/PR，一个写入者。设计审查通过相应范围后再动源码；不得让尚未批准的后续阶段混进首个 PR。普通入队仍自动，不恢复早期审批。

## 8. 自动化与设备矩阵

域模型至少覆盖 A01–A07、A11、A12、A14：随机码保留前导零；相同码不同实例；并发最后席位；59999/60000/60001 ms；反复掉线不续期；释放后新人占位、旧成员回来等待；kick 后进程重启仍被拒绝；unblock 不开麦；离队后握手回调到达；旧 incarnation 或旧 control connection generation close 不关闭新连接；C 加入/退出/恢复时 A–B lease 和就绪证据保持；B 重新准入时旧 B 回调失效；非房主控制命令拒绝；满员不创建媒体。

音频 fake 回归覆盖 A08–A10、A15：删除一个 peer 保留另外两个；本机屏蔽跨重连；电话后静音不变；两个 peer 同时关闭仅一次公共 1→0；最后一名远端离开停止采集；重新加入重新建立有效上下文。codec fuzz/边界测试不得跳过严格身份与长度检验。A13 加旧 V2 原测试及新增模式忙碌路径。

设备 D01–D12 全部目前 NOT RUN。当前 `adb devices -l` 无设备；不拿单测或模拟器当听音、P2P 射频、蓝牙、功耗证据。先三机 LAN 通过再四机 LAN，再无路由器四机。记录每对两个方向、构建 SHA、机型/系统、耳机/路由、原生录音生命周期、客户端互达、加入/断线/来电、温度耗电。连续两小时通过后再四小时观察。
