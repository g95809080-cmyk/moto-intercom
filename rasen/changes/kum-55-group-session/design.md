## Context
Base `7f8acec`。KUM-54本地664测试/Lint/APK通过，固定源码fa799a0已批准；云端CI35449893332进行中。遵循已确认四人PRD，用户暂无实体设备，先代码及自动化。

## Goals / Non-Goals
把认证、连接和域逻辑变成可运行的控制链路，提供群组媒体effects和只读UI snapshot；本issue不启用Activity入口、不创建第二个前台服务，不改变双人V2。后续独立issue完成Service模式互斥、音频协调和UI。

## Decisions
### 认证桥接
BLE广播仍只含固定UUID。GATT公开描述返回room instance、host runtime/device、昵称（最大64 UTF8字节）；它只是未认证候选。客户端用输入码依次验证最多16候选，完成全部本次候选后仅一个自动选中，多个交给UI选择，未响应候选标为未能验证而不是匹配；用户取消立即失效候选代际。

连接内消息严格顺序：Describe → Begin(context, client round1)/host round1 → client round2/host round2 → client round3/host round3 → encrypted request/encrypted network descriptor。主机保存下一轮输出；全程复用同一KUM53对象且20秒不重置。主机验证context room/host精确匹配本运行时；知道口令且双向最终确认后，才允许加密请求获取SSID/passphrase、数值IPv4和端口。网络描述不含GroupJoinProof，候选认证不占席位、不创建媒体。移除状态在发布网络描述前重新读取，避免移除后继续发凭据。

PAKE计算在有界worker执行，不占主Handler；至多4未完成交换，待处理队列有界。取消先以原子标记撤销current，计算返回后不得交付；密码/crypto仅在本次运行时，不写磁盘/日志。每个bootstrap会话及KUM54 BLE期限一致，peer关闭撤销对应auth。

### TCP与准入
入网后重新做独立TCP PAKE（全新握手UUID/密钥/计数），避免把BLE通道密钥复用到新socket。客户端逐socket绑定Network，房主server绑定实际GO IPv4；local loopback测试允许127.0.0.1注入。握手header有界，双方发送round1、读取对端并发送round2、读取并发送round3、读取确认。20秒绝对看门狗关闭socket，读取前验证长度，未验证连接数最多4；已认证成员最多3，全部连接最多7，不接受无界线程/队列。

握手成功回调携带不可变auth context、channel token和本运行时current谓词；只有群组产品writer可以转为10秒GroupJoinProof并调用GroupRoom.Join。主机每次重新核对removed、容量、room及runtime。通道建立不代表ADMITTED；welcome之后客户端才可提交普通控制。房间满返回加密FULL并关连接，客户端15秒低频重试同一房间，不挤占成员。出网/控制失联检测后，主机按第一次Lost保留60秒；重连新PAKE和control generation，旧socket永不控制新lease。

加密payload沿用KUM53 SecureGroupChannel，在单一发送队列内分配nonce/顺序；每连接最多64条待写消息，溢出关闭。读取线程与写队列各自有界，关闭socket解除阻塞，独立看门狗覆盖慢读写。控制心跳每2秒，8秒没有有效消息判为失联。所有异步事件携带socket对象token，主线程有限入口交付，超限终止该连接。关闭/结束效果可在短截止内flush最后消息，不能永久等待。

### 只读名单与单写者
新增GroupRoomView只读接口供媒体授权使用；GroupRoom实现它，客户端GroupRoster只读实现它，客户端不能通过反序列化构造可变权威域。名单编码有界、UUID规范、最多4占用席位/16跟踪记录/6链路、成员/链接一致，单独publication sequence每次发布递增（不依赖现有rosterRevision，因为link确认不增加该值）。完整名单仅接受当前已确认host socket，welcome必须包含自己的当前runtime lease；旧序号/错room/缺自己直接拒绝或按主机退出原因终止。

群组编排作为SessionOrchestrator的独立群组模式组件（GroupSessionOrchestrator），只在一个指定dispatcher调用dispatch写状态；Service仅执行effects。旧双人SessionOrchestrator与群组writer将由后续Service模式门禁互斥启停，绝不同时拥有房间/音频。网络回调不能直接修改GroupRoom、Participation或UI。跨线程媒体current使用writer发布的不可变快照。

主机派生每对ADMITTED成员的link generation，低deviceId发offer；客户端媒体effects只匹配当前local/peer/link/intent。成员信令先经KUM51主机入口检查，再由host转发；客户端验证原sender、recipient、lease、link及角色，不把host转发身份误当原sender身份。扩展必要的link confirmed/restart控制类型，同样校验lease与当前link；远程普通成员不能发End/Remove/Unblock。

PC connected + remote audio track仅表示媒体已连接，不能提交最终ConfirmLink。GroupVoiceEvidence必须携带当前intent/local/peer/link，分别证明当前代际下有效RTP发送和接收（前后样本计数增长）、已实际开启的音频I/O和已确认路由；两端各自满足后才允许报告ConfirmLink。缺任何证据保持“语音待确认”，不妨碍健康链路先通信。KUM55实现证据入口和自动化，KUM56接入真实WebRTC stats及音频/路由证据生产者；未接入时绝不自动确认全队语音就绪。静音或VOX无声不触发掉线；确认在当前link内保持，I/O/路由中断撤销就绪，恢复后重新提供证据。人工互听仍是单独实机验收。

所有名单广播先入发送队列，再发送引用该lease/link的信令；接收端遇到未知lease/link直接拒绝，不据此创建资格。安全channel计数和wire sequence均在同一个发送串行边界分配，frame顺序等于socket写出顺序。TCP看门狗覆盖20秒握手、10秒准入等待及慢写；首次入队使用独立attempt token，在尚无member lease时取消也能立即撤销旧认证回调。

自静音/本地屏蔽属于Participation，不改变全体准入；局部来电只发布AudioUnavailable并停止本机音频，控制连接保留，其他健康pair继续。主机独处仍广播/保持room，但无媒体demand。正常离开撤销intent和全部effects；主机End结束所有成员。进程重新创建默认idle，不恢复自动入队/录音。房主消失不选举新host，客户端保留当前room目标低频重试，用户可退出。

主机为避免反复离线身份无限增长，名单跟踪上限16；只可丢弃已释放席位的WAITING旧记录，不丢弃ADMITTED/RESERVED。被丢弃客户端重连仍按同room重新加入（其用户意图由客户端持有），不影响保留席位规则；removed集合运行时保留以防重新加入。

## Validation
真实PAKE经过bootstrap请求响应完成双向确认；错码/错context/取消/移除后取凭据拒绝；不占席位。真实loopback TCP三客户端入队、第四客户端FULL、加密控制往返、关闭和重连generation/旧消息拒绝。单writer fake effects覆盖六pair逐步就绪、主持/普通退出、60秒边界、局部音频中断与mute保留、多候选选择。全量JVM/Lint/APK及固定SHA复审，硬件全部未执行。

## Risks / Trade-offs
网络恢复使用独立 networkAttempt（区别于房间 operation 和 TCP controlAttempt）；HostReady、NetworkReady、NetworkLost、NetworkFailed 只作用于当前 networkAttempt。失联撤销旧代次；一次建网未完成时 Tick 不会再次发起。房主保留 room/code/intent，成员席位从首次失联计算60秒；客户端保留原队伍目标。RecoverHost 执行器必须先确认旧 GO 清理释放，UNKNOWN 期间只重试原 owner 清理。单条媒体或 socket effect 异常按 link/channel 定向处理，不终止健康成员。

BLE+TCP双次PAKE换取明确通道绑定和简单重连，真机性能待测。System Wi-Fi确认耗时不计入已完成的BLE握手，但TCP自己的20秒硬期限不能延长。只读视图和新控制消息会触及既有群组wire/media签名，需要维持旧单元测试与双人默认行为。
