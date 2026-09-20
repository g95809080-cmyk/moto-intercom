## Context
Base `8bb8cb7`，KUM55源码ef4897d获批，全量694测试/Lint0错误/APK通过，PR30云CI35452416751进行中。Linear KUM-56。已确认PRD不变，实体设备不可用。

## Goals / Non-Goals
完成群组生产调用链与用户入口，保留旧双人路径；不发布、不合并main、不声称硬件验收完成。

## Decisions
### Service与生命周期
复用IntercomService，不创建第二个前台服务。GroupRuntime只执行GroupSessionOrchestrator effects并拥有适配器生命周期，所有产品写入仍在主线程writer。旧模式running/starting及PendingCloseOwner.hasPending、群组active/closing及GroupGoOwnership进程租约共同作为互斥门禁；拒绝切换时保持当前会话，不自动结束。群组退出先撤销意图并关媒体，控制terminal最多flush1秒，然后关网络；GO UNKNOWN保留清理对象和租约，定时retryCleanup，未确认释放不可开新模式。

GroupActivity为exported=false独立Compose页面，由主页入口启动。创建/加入仅来自可见页面的明确点击及权限结果，使用显式startForegroundService命令，Service重新检查权限和互斥状态。START_NOT_STICKY/null intent不恢复房间；Activity重建/后台只解除UI订阅，不发送Leave。回到页面读取当前snapshot；取消权限等待或页面销毁不迟到启动。通知点击回到群组；结束全队必须确认。稳定deviceId复用既有identityStore，群组runtimeId每次新runtime生成；昵称读取既有设置，不写配对数据。六位码仅运行时，不SavedState/磁盘/日志；分享只由用户点击触发。

### 网络效果
Host StartHost按networkAttempt创建GO，得到凭据后绑定TCP8899并启动BLE bootstrap，BLE ready后HostReady。房主失联保留room/code/runtime/intent；RecoverHost先关闭旧server/BLE并等待旧GO确认释放，再创建新GO，同一房间可能取得新SSID/密码。每次网络操作绑定networkAttempt及实际adapter对象；所有迟到回调必须同时通过两层校验。

客户端初次Search完整验证候选，多个显示选择。JoinNetwork使用所选匹配网络；首次失败或网络断开后的恢复重新BLE验证，只接受原room+host（不能因相同码跳入别队），更新认证后的网络描述再发起系统Wi-Fi请求。writer新增当前attempt的网络描述更新事件，保留原participation。有限搜索结束未找到则NetworkFailed，受控重试。FULL只低频重连当前网络，不反复系统弹窗。系统请求一次最多45秒，BLE验证各候选20秒；取消关闭scope、GATT和网络请求。

TCP控制每channel有限主线程入口64条，认证事件先于消息排入同一入口；溢出只关闭该channel。GroupControlCodec解码可在读取线程完成，再交付不可变消息；关闭后撤销队列。transport回调携带root operation/网络代次/client controlAttempt以及channel对象。只有writer发AdmitChannel才markAdmitted。Send在socket串行writer内构建序号；末条flush不延长退出。每秒Tick仅active时运行。

### 音频与证据
每runtime一个GROUP RiderAudioEngine、AudioRouteController、CommunicationAudioCoordinator、GroupMediaController。每peer一个PC，共享factory/ADM/source/track。aggregate demand第一次peer开始/最后peer结束通知coordinator；主机独处不采集。局部电话/焦点/路由暂停发布AudioAvailable(false)，控制网络继续；恢复保留自静音和本机屏蔽。使用现有路由选择和VOX设置；界面可切换蓝牙/手机路由及VOX。GroupMessage SDP/ICE在runtime转换现有JSON API，所有回调绑定lease。

RiderMediaSession增加默认无操作的证据查询，GROUP每秒至多一个未完成getStats。读取当前PC audio inbound-rtp.bytesReceived/outbound-rtp.bytesSent，保持同一lease前后样本，counter倒退重置，不跨代次。PC connected与remoteTrack、当前AudioIoGate授权、JavaAudioDeviceModule真实AudioRecord/AudioTrack start/stop/error回调、近期采集PCM共同形成I/O证据（本地jar已确认setAudioRecording/Playout返回void，不能当成功布尔）。路由ready只在AudioRouteController已验证回调后成立，选择/失联/电话即撤销。查询完成再确认current后才提交GroupVoiceEvidence；无数据/静音/VOX不触发断网，不制造全队ready。I/O中断撤销就绪，重新产生证据才能恢复。

### UI与自动化
页面提供创建/六位码加入、候选选择、最多四席的名字/房主/恢复中/音频暂不可用状态、就绪对数、码复制、自静音、本机成员静音、房主移除/解除、结束确认和普通离开。窄屏可滚动，大字不裁切，48dp触摸目标、明确语义与系统insets。无普通入队审批；不展示电话原因给其他人。权限拒绝显示可重试或设置入口。

验证实际effect执行路径的注入适配器测试、Service模式门禁/后台/重建/进程重启、UI操作与权限取消、真实stats解析和ADM/路由证据组合、无路由器凭码顺序及取消、旧双人回归。全量JVM/Lint/APK与云模拟器仪器测试，固定SHA审查；输出A01–A15证据对应及D01–D12 NOT RUN。

## Risks / Trade-offs
OEM Wi-Fi Direct/蓝牙/系统弹窗与四机听音须后续实机；保留明确恢复和退出路径。自动化证明实现和生命周期，不替代射频、音质和长时功耗。
