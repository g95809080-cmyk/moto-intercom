SessionOrchestrator仍为产品状态唯一writer。当前owner的EOF/send failure统一走connectionLost；关闭开关不得从这两条路径创建恢复attempt。

未来追连意图和当前有界轮次分开。actor保存原verified deviceId和不复用的意图代次；第三个失败只发一次原Reset，exact ResetCompleted后设置固定30秒门槛。只有门槛之后当前producer真正收到的NSD/UDP/TXT/v2观测可消费；完整cached列表、Room更新、expire、requestPeers重放和时钟单独前进不能启动连接。必要探测只触发真实发现。等待期间只能追原deviceId，不能退回其他preferred。

采用新轮次前捕获Token、process runtime归属、意图代次、producer实例/序号、真实receivedAt和精确候选。工厂与clock在锁外；一次性admission短锁内复核并提交纯actor图，采用后完成，不安装ownedPresenceAdmission。后续Recovering重试factory因此不进入请求短锁。新TargetLock只取本次remoteRuntime，不把Presence当verified Peer。

关闭开关、manual/forget/stop/主动断开入口同步撤销尚未采用的授权，再排精确actor意图取消。Room await前按captured目标/代次撤权；旧A清理不能伤B。当前轮次和未来意图的取消边界明确分开；late ResetComplete或回执不能复活取消的意图。实际开启Wi-Fi新Socket/HELLO时重新建立origin，不能仅复用旧SIGNALING_READY布尔值。

ResetCompleted 的探测 effect 只在原意图代次、reset attempt、固定门槛、runtime 和授权仍匹配时执行。门槛到达后每30秒重新注册实际 NSD discovery 并请求 Wi-Fi Direct service discovery；扫描本身不创建 attempt，也不使用缓存合成观测。服务暂时 refresh 时取当前 token，旧 runtime、取消和关闭设置会使定时回调失效。

追连等待期间，原 verified deviceId 的已配对车友可通过明确 USER 入站请求提前建立连接。只有该请求成功采用精确 wire ID/TargetLock、进入 Connecting/Optimizing，才清除旧追连目标；超时拒绝、其他车友及 AUTO_PAIRED/RECOVERY 入站均保留目标。真实发现观测还必须与当前 selectable snapshot 的精确 deviceId/remoteRuntime/endpoint 一致，不能用另一 producer 已淘汰的候选。

Socket reader、writer 和 SDK 断线 producer 在投递 Main 前捕获当时完整 source attempt 与授权。off→on 不恢复旧票据；开启后发生的新断线可以取得新票据。actor 不得因迟到事件重新查询并补授旧失败授权。

发现列表不得注册新 remote runtime。Main 保存各 transport 的原始缓存，只有当前 source 的真实 receipt 与缓存中的精确候选匹配后，才能提交全局 runtime 准入。跨 producer 按实际 receivedAt 排序；明确较旧的不同 runtime 记为 retired，同毫秒换代等待下一报文。普通列表、expiry、Room 刷新仅发布已准入身份的可用性。最终采用同时检查全局当前 observation，工厂阻塞期间另一 producer 已换代或移除候选时，旧请求不能采用。短锁顺序为 policy→source→global discovery admission→Service request admission（仅 preferred）→session token；clock、factory、publish 和 I/O 均在锁外。

NSD receipt 带实际 serviceName 的 revision；当前 onServiceLost 先撤销该 key，再删除缓存。迟到 resolve 与已经排队的最终采用都复核 revision，Lost 不影响独立 UDP 观测，也不制造新观测。
