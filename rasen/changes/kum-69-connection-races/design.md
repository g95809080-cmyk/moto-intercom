SessionOrchestrator继续是产品状态唯一writer。Channel失败只移除当前候选；若还有live候选、opened但未failed/retired传输或planned尚未opened工作，保留原deadline/TargetLock及T+5/T+3 milestone。SELECTING/OPTIMIZING的cohort失效时重新选择或等待工作；MediaChannelSelected必须匹配当前不可变cohort，旧null结果不能终止新的选择。

Socket连接、HELLO和主线程登记均持有pending lease，短锁只改变归属；实际read/write、callback、socket.close不持生命周期锁。绝对frame最长1秒、整个HELLO最长2秒，均受原attempt截止限制；watchdog关闭其精确socket。主线程map登记与pending移交在短锁内竞争，截止/关闭已先赢则不登记，移交先赢则旧watchdog不能关闭已登记socket。

进程级runtime令牌直到Wi-Fi异步close完成才释放，后续同实例或新Service实例不能提前启动。IncomingConfirmation投递前核验running、runtime、pending attempt/channel/nonce/surface/peer/deadline。请求回执以捕获的runtime、请求代次和精确目标隔离，不能让旧拒绝清除新选择。UI第一次缺席安排固定2秒timer，相同目标的无关刷新不重置它。

请求授权捕获原 Service Token、local runtime 和完整 UI request。工厂与时间读取在授权短锁外；实际采用、同目标 glare child 转移及 Connected/SUCCESS 提交与 revoke 共用短锁。glare 保持原 TargetLock/deadline，回执跟随实际 child；存在授权 sidecar 时不匹配一律拒绝。Connected 先提交即完成请求授权，既有通话随后按原 active-session 生命周期管理。

已有请求授权的普通 actor 决策也与撤销互斥，包括 EOF、send failure、deadline、graph 清理与终态提交；使用锁外一次取得的单调时钟，锁内只处理纯数据。精确 Cancel、显式 Stop 与不改变 owner 的开关更新可在撤销后处理。原始 Socket 的 originatingAttempt 不改写；同一授权原 attempt 的兄弟 Socket 仅在当前 child 的匹配 wire/RESPONDER/peer/原目标与截止/双方 channel plan/选择或优化 phase 下交接。实际加入 media cohort 另由 actor 检查优化时限、原 attempt 截止、首选 transport 及重复 transport；Service 的 lineage guard 本身不读 clock。

撤销先于 Token 失效，关闭旧 I/O 的同时发送精确 PresenceConnectCanceled；Main 回执、媒体选路、StartWebRtc、targeted open 与 Socket admission 都复核当前归属。当前资源已经关闭时不重复提交 Abort，防止覆盖原 reset/manual refresh 的清理参数。物理关闭先于 actor 取消完成时，Discovering 的实际状态回调及精确取消完成会重新核验已完成的原 cleanup request；不改其延迟、reset 或 refresh generation。

终态先于 Service 清理、普通 Abort effect 后到的反向顺序也不能覆盖已经存在的清理请求；仅重新核验原 request。待资源实际关闭、actor 回到可恢复状态后再启动同 runtime 的新 Token。
