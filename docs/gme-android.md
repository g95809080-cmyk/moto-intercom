# 腾讯 GME 云端对讲

当前为实验实现，云端入口默认关闭。Phase 0–4 的控制面、邀请、降级、云端音频和稳定回切已接入本地代码。新云栈尚未部署，GME 双真机语音及回切时延尚未验收。

目标链路：

- 本地对讲继续使用 Wi-Fi Direct / LAN、WebRTC 和原有音频路由。
- `IntercomService` 统一托管本地和 GME 音频所有权；`CloudSessionCoordinator` 管理骑行、邀请、降级和回切。
- 不复制 ASMAX `motohud` 的 BLE PCM 网关，也不把 GME 当成第五种本地 `Transport`。
- GME AppKey 只保存在 AWS Secrets Manager。Android 只拿到短期 `authBuffer`、`roomId`、`openId`。

## 本机准备

```powershell
.\scripts\gme\install-sdk.ps1
```

SDK 二进制放在 `app/libs/gme/`，该目录被 Git 忽略。

在未入库的 `local.properties` 中提供：

```properties
gme.enabled=true
gme.control.plane.url=https://<new-api-id>.execute-api.ap-southeast-2.amazonaws.com
gme.websocket.url=wss://<new-ws-id>.execute-api.ap-southeast-2.amazonaws.com/live
```

不设置 `gme.enabled` 时入口隐藏，设置云端网址不会自动启用功能。实验构建还需要用户在设置中阅读隐私说明并启用；两种网址齐全后才连接控制面。旧的 `gme.ticket.url`、`gme.client.token` 已不再读取；静态令牌不进入 `BuildConfig`。服务端契约与验证方法见 [控制面说明](gme-control-plane.md)。

## 邀请与配对

1. 双方使用支持云端配对的实验构建。先建立本地对讲，在设置 → 云端对讲中重新配对；接收方需要确认。旧 Room 配对和自动重连不会直接取得云端授权。
2. 新的明确本地配对，只有双方已同意云端隐私说明并交换了支持能力时，才可自动交换公钥和双方签名。未同意的一方会看到完整说明并自行确认。
3. 设备使用 Android Keystore EC P-256 密钥。双方各自重建 transcript，绑定本地请求、两端会话和随机挑战；完整双签独立保存，不修改 Room 表。
4. 退出本地对讲，首页选择已完成登记且在线的车友发起邀请。等待接受期间不取票。对方接受后，协调器才生成批准骑行并启动 GME SDK。
5. App 前台使用弹窗，已有前台服务时可用通知动作。点击通知正文只打开提示；接受按钮返回可见 Activity 后再检查麦克风和电话状态权限。电话状态只用于本机来电中断，不上传号码或通话内容。通知权限关闭时仍可在前台响应。

可选择仅 Wi-Fi。应用退到后台且没有对讲前台服务时断开在线连接；没有 FCM 或冷启动推送。取消、结束、关闭云端和解除配对先保存待同步命令，再释放会话；离线命令在重新启用并联网后同步。

进程重建后只展示“继续本次骑行”，用户点击后才重新连接。4 小时额度耗尽时展示手动延长入口。恢复和延长仍使用服务端状态重新核验，缓存不能直接授权进房。

## 会话规则

- 云端目标必须完成公钥和双签配对；当前旧配对仅支持本地，不能作为云端授权。
- 每次骑行由服务端分配随机房间；同次骑行断线和降级复用房间。
- 进房使用 `FLUENCY`，双方都是 `ANCHOR`；准备阶段静音会保留到进房后。
- 本地直连与云端音频互斥；合法降级等待本地原生音频和路由释放，其余并发启动会被拒绝。
- GME 初始化、进房、退房在主线程执行，`Poll()` 每 33ms 驱动一次。
- 关闭云端会话时保留进程级运行所有权，直到 SDK `ExitRoom` / `Uninit` 和路由清理完成；释放失败会阻止第二套音频启动。
- `GmeCloudSession.start` 只接受 `ApprovedCloudRide`。该对象要求隐私同意时间戳、未失效的双人骑行及服务端已确认的云端状态；签名票据接口再次核验。
- 60 秒无车友自动退出并显示手动继续，不结束骑行，也不自动重新进房；只接收票据指定的对端身份。适配器用单调时钟执行服务端给出的云端截止时间。

## 当前边界

`ControlPlaneClient` 实现 HTTP 原始正文签名；`CloudControlSocket` 只接收事件；`DefaultCloudSessionCoordinator` 处理邀请、幂等启动、取消、快照及手动恢复；`CloudIntercomRuntime` 在主线程串行接入服务，密钥、HTTP 和存储切到 IO。

本地连接真正进入 `Connected` 后才登记骑行，双方提交 `/local-established` 后服务端才批准自动降级。沿用本地连接的短暂自愈窗口；确认的传输故障才提交 fallback，同次骑行复用房间。用户结束、拒绝、首次连接失败和已结束骑行不会授权自动进房。旧连接的迟到回调、取消中的交接和旧骑行持久化不得覆盖新骑行。

`CloudVoxController` 每 20ms 读取 `GetMicLevel()`，使用现有 `VoxGate` 的灵敏度、攻击、hangover 和背景学习，通过 `SetMicVolume(0/100)` 门控。VOX 关闭门时继续采样，手动静音始终优先；关闭 VOX 后持续开麦。GME 不运行本地 RNNoise，设置中隐藏该开关。SDK 不支持听筒，云端只展示蓝牙和扬声器，原听筒偏好映射到扬声器。

`CloudAudioSessionController` 复用 `CommunicationAudioCoordinator`、`AudioRouteController`，不创建 WebRTC ADM 或自定义 PCM。首次初始化和每次进房/重连后均需重新验证焦点与路由。来电、焦点丢失会关采集/播放并调用当前 Android SDK 提供的 `ITMGContext.Pause()`，保留房间；重新获焦并验证路由后才 `Resume()`。60 秒无对端和云端时长上限在中断期间仍生效；进房的 20 秒网络超时预算在电话暂停期间冻结。云端到期前 30 分钟提示，延长仍需用户操作和服务端批准。

腾讯文档规定 `GetMicLevel` 为 0–100，未承诺与本地 PCM dB 刻度相同，也没有证明在 `SetMicVolume(0)` 时仍能独立测量声源。当前把 SDK 分数直接作为门输入，不能宣称已完成阈值校准或零上行验证；真实静音、漏字、爆音和回声必须在两台真机上测量。当前 JAR 的 `Uninit()` 会隐藏底层停止返回值，客户端的释放结果只代表可见 SDK 契约已执行，不能替代实际录音设备释放验收。参考腾讯官方[实时语音接口](https://cloud.tencent.com/document/product/607/15210)与[音频问题说明](https://cloud.tencent.com/document/product/607/51463)。

## 稳定回切与恢复（Phase 4）

云端通话期间，`LocalShadowProbe` 使用现有 LAN / Wi-Fi Direct 发现和 V2 信令建立独立探测连接；不会启动普通本地运行时或取得第二份焦点/路由所有权。LAN 只绑定当前实际 Wi-Fi；从蜂窝加入 Wi-Fi、IP 改变会重建对应探测器。P2P 仅在默认网络为蜂窝、权限齐全时尝试，随网络变化重新检查条件；已提交的本地 P2P 会话允许继续使用。没有可用权限或原生能力时继续云端。

两端必须协商 `CLOUD_SHADOW_V1`。探测帧由配对私钥逐帧签名，绑定当前骑行、双方设备与运行会话、传输类型、连接 attempt 和双方新挑战；序号及双向认证防止旧连接帧重放。影子信令与普通连接请求互斥；未认证连接 3 秒内关闭。发现收据只使用 3 秒内的新观测，失败传输退避 5 秒，旧 attempt 的迟到回调不能关闭新连接。

WebRTC 预先完成 SDP / ICE 协商，录音和解码播放保持关闭。双方对相同探测标识持续上报健康状态，由服务端计算 **10 秒稳定窗口**；任一上报间隔超过 3 秒就重新计时。只有双方 PREPARED、服务端 ARMED、双方 READY 后，确定的发起方发送 GO。每次骑行最多进入 3 次切换尝试，失败后需要新的稳定窗口。

`RideAudioArbiter` 保留同一焦点、电话和路由宿主，先暂停 GME，再确认 SDK 采集/播放开关关闭及系统活动停止，才授权本地音频。系统检查只比较 GME 初始化前的录音/播放配置，不读取其他应用音频；未知的新活动会推迟回切。该额外确认需要 Android 8.0 / API 26；更旧系统继续云端。成功确认前不执行 GME ExitRoom / Uninit，失败后可恢复同房云端。

本地启用后必须在同一授权代次看到原生 PCM、实际播放及双向 RTP 计数在本次窗口内增长，才提交 COMPLETE；两端 COMPLETE 后才提交本地状态并退出 GME。为使双方静音或 VOX 关门时仍可验证新 RTP，这条回切会话关闭 Opus DTX，持续发送静音包；静音/VOX 门控仍生效，普通本地会话保留原 DTX 设置。300ms 为一次本地启用与证据确认的共同预算，超时即关闭本地原生音频、等待释放并恢复 GME；**尚未用两台真机测得端到端断音 ≤300ms**。

原生停止屏障保留旧采集线程和全部解码播放租约，覆盖延迟构造、迟到回调和释放失败。Wi-Fi Direct 清理也传回实际结果，失败不能假报释放成功。失败后先关闭探测及其原生重试，再释放媒体；提交后的本地断链通过同一次 ride / room 再次降级。云端时长在服务端本地提交时停止累计，腾讯实际计费仍以 SDK 退房及腾讯账单为准。

进程重建、服务重建或对端离开后的快照只提供手动继续。用户继续时核验服务端有效期，未完成的切换先撤销；保留同次骑行和房间。结束、关闭云端或配对撤销同时关闭探测、本地媒体和 GME，迟到结果不能重新授权采集。

AWS 测试栈和线上控制面已完成运行联调；GME 实际双端通话、蜂窝和头盔蓝牙仍未验收。

## 本地验证

先在 `backend/gme-control-plane` 执行 `npm ci --ignore-scripts`，再跑完整 Gradle 门禁。`CloudHttpContractTest` 会启动仅监听回环地址的 Node 测试服务，使用真实 Java ECDSA、HTTP 和 WebSocket；只有 GME 签票结果与音频宿主为测试桩，不读取 AWS 凭据。

`DeviceKeyStoreInstrumentedTest` 在 Android 环境验证私钥编码为 null、重建适配器仍使用同一公钥及签名可验证。`CloudAudioPlatformInstrumentedTest` 使用静默目标验证真实 Android 焦点、扬声器路由与释放次序。`LocalShadowMediaInstrumentedTest` 使用两端真实 WebRTC、合成 PCM 和手动静音，验证预热不录音、授权后的新 RTP/播放证据、暂停原生停止和同连接恢复；不发起腾讯语音。GME 时序测试使用替身 SDK；双端合同测试使用真实 Node HTTP/WebSocket，覆盖 10 秒稳定、双方提交、本地计时停止和重建后手动继续。完整结果见 `gme-validation-2026-11-10.json`。

## Phase 5 测试栈与模拟器验证

2026-11-11 的 Phase 5 构建已在未入库的 `local.properties` 中启用 `gme.enabled=true`，并连接已部署的 `motocom-gme` 测试栈。HTTP 为 `https://1nzpbifxo6.execute-api.ap-southeast-2.amazonaws.com`，WebSocket 为 `wss://0v8zmyfkuk.execute-api.ap-southeast-2.amazonaws.com/live`；旧静态票据地址和 client token 不再存在。

API 36 AVD 上执行 53 项 instrumentation，失败 0、错误 0、跳过 2。应用安装和冷启动成功；首次引导、云端隐私说明、同意后启用入口以及未配对状态均完成 UI 冒烟。当前通用 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，SHA256 为 `6574afcd1061550b3d33b31c470510abbc9802961dc4b4064123839838e0e2e3`，已通过本机已知旧凭据扫描。

仪器测试必须从 `G:\` 映射运行 Gradle。直接从中文绝对路径运行会触发 CMake 生成 JSON 的转义错误；映射盘下三套 ABI 均构建成功。模拟器证据仍不包含真实腾讯 GME 双向语音、纯蜂窝设备、标准蓝牙头盔、来电/锁屏、弱网、长时和实际 300ms 回切。
