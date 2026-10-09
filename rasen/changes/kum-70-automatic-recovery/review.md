# KUM-70 固定提交审查与验证

Base SHA `579b6415496bfd8ca8700618106e16257ee9ae0a`；源码/测试 Head SHA `99fc2bc3877a5d52d69d18f36b41c6bdc15102cb`。

三名独立只读 motointercom-product-architect reviewer 分别审查 actor/policy、实际发现与传输、Service 接线。根汇总：APPROVED；P0=0、P1=0、findings=[]。Next gate allowed：创建 Draft PR、运行远端 CI，并继续独立 KUM-71。Non-blocking：双手机、普通蓝牙、多应用听音和长期失联恢复按用户要求 deferred；允许 In Review，不代表 Done、合并或发布。

官方 OCR 固定范围共25项，选中12项全部审查，默认排除的9个测试和4份合同文档补审。25 reviewed、0 skipped；逐路径实际全文、差异/上下文与基线复用深度见 coverage.json。没有把本次差异覆盖冒充完整项目逐行审查。

当前控制 Socket reader、writer 和 SDK 断线 producer 在投递 Main 前捕获完整原连接和授权，off→on 不重新授权迟到失败。第三次失败仍使用既定 Reset；只有精确 ResetCompleted 才设置固定30秒门槛并保留原 verified deviceId。当前 adapter 真实发现报文在门槛后允许同一或新 remote runtime 开始新三次轮次，采用新 ID、不可变 TargetLock、10秒截止和未验证 Peer；仍须当前 Socket HELLO。

普通缓存、Room、expiry、requestPeers 回放或时钟前进不能创建恢复 attempt。真实 probe 每30秒只请求实际 discovery；停止、关闭或换 runtime 后旧 probe 失效。NSD Lost 先撤销 serviceName revision，迟到 resolve 与最终采用均重新检查。跨 LAN/P2P 观测按实际接收时间准入全局 runtime；旧缓存不能注册新身份，较旧 runtime 退役，工厂阻塞期间被新观测替换的请求不能采用。

关闭、手动选择、forget、stop 和主动断开在命令入口同步撤权；Room await 前完成，旧 A 的精确取消不能清 B。已经运行的 Recovering 轮次按原合同完成，未来意图取消。一次性 episode grant 不保留到后续重试；clock、ID factory、I/O、发布和外部回调均在短锁外。等待时第三方与 preferred 不能抢目标；原车友 USER 入站只有实际采用后才替换，超时请求保留目标。Wi-Fi READY 换 attempt 重新建立实际 Socket/HELLO/lease。

完整本地门禁：92 suites / 710 JVM tests，failure/error/skipped=0；Lint 0 errors / 70 既有 warnings；Debug 与 AndroidTest APK 构建成功2m40s。新增回归包含原 Service effect collector、实际 NSD/TXT 注册回调、真实 UDP/TCP Socket、HELLO、reader EOF/writer failure、factory 阻塞、Room await 前撤权、跨 producer 换代、Lost、精确 Reset/probe 及旧 adapter 刷新。SDK CONNECTED 通知为测试注入；Activity 的合成 receipt 仅用于单元排序，未作为真实发现证明。

同源码 SHA API36 原生 runner：`OK (27 tests)` /10.143s。其中 SharedNetworkNsdTest 与 SyntheticAudioNetworkTest 因缺少 `role=server|client` 双机参数按 assumption 跳过；不构成双机、P2P 射频或蓝牙验收。远端 CI 在 Draft PR 后运行并单独记录。没有改版本、合并、发布或部署。
