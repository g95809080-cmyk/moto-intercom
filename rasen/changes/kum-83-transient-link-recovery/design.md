## Observation and authorization

actor 内一个精确 sidecar：原Connected attempt、媒体channel/wire key、native source、窗口generation、首次loss时间/序号、2秒观察deadline、首轮10秒recovery deadline与首次票。产品仍为Connected，但媒体就绪撤销。native入口只冻结元数据；实际转移由actor/effect执行。

同source原生CONNECTED证明在窗口内登记后，timer最终claim必须承认该证明，即使RTC/Main迟到。自愈先于首次连接deadline/SUCCESS拒绝检查，保持connectedAt/配对/SUCCESS不变。已采用新owner后，旧source不能恢复旧通话。

重复loss保留原窗口及票；off→on不能补票。观察中off允许原链路自然自愈，过期未恢复走现有不重连清理。stop/manual/forget/owner替换立即撤权。恢复首轮deadline=首次loss+10秒，fallback=首次loss+3秒；后续原三轮/退避/Reset合同不变。

原生 source 的短锁同时保存 exact accepted Connected owner 的最早 producer loss（时间、冻结票、入口序号）及 hard-loss 标记。通用 capture 只生成快照；实际 native loss、精确 selected Socket EOF/send failure、IM failure 与经原生查询确认的 selected group loss 才记录。未选中 channel、缺少 channel 的旧回调、成功发送、无实际丢组证据的广播不记录。时间相同按入口序号保留早票，不能以交付顺序重新授权。

ID factory、时钟与 Service/Socket IO 在锁外。最终短 claim 使用 ledger 中同一条 frozen time+ticket 纯构造 immutable recovery attempt、fallback/race/effects，然后消费该票。factory 期间 producer 仍可录入更早 loss 或自愈；最终 claim 必须承认。hard loss 不能被迟到 CONNECTED 清除，soft loss 只由精确当前 owner 的自愈 claim 清除；若自愈交付前又出现新 native loss，必须另开有界窗口。

## Effects and transport

新增精确Schedule/Cancel观察effect和到期event，由Service管理timer。正式恢复先关闭原owner，再进行新transport工作，不清空继任入站channel。真实同peer RECOVERY经过HELLO与原glare，不能无身份捷径。

P2P failure仅重试仍确认匹配当前目标且有效的group，不续deadline；身份不符/错误group/实际无线丢失仍清理。native removeGroup屏障绑定manager/channel/lifecycle/removal generation；未完成前不发继任connect，不以迟到回调过滤替代原生串行。回执重新验证当前attempt，缺回执仅在原预算内失败P2P分支，不阻断可运行LAN。

实际 ACCEPT 已写入 Socket 而 actor send-completion 尚未采用时，selected 同一 candidate 的早到 Offer/Candidate 仅缓存；StartWebRtc 仍要求原完整正式 acceptance gate。stop/替换关闭对应 Socket 并清空缓存。双 origin HELLO glare 只保留已批准 R1→G lineage；P2P adapter 在最终媒体采用时验证实际 session 与 lease，再撤销原生 target R1。

同一个逻辑 attempt 最多两次普通 IO/EOF Socket 重试，重新调用 connect 或重新验证 group 不补次数；真正采用下个 attempt 才得到下一轮预算。协议/身份错误无此捷径。cancelConnect/removeGroup 真实 ACK 前阻挡新 SDK connect、group/Socket 验证；BUSY 重试与 replacement 都共享原绝对五秒清理等待期限，未 ACK 时只报告当前 P2P 分支失败，屏障保持。

## Evidence

实际actor、Service、原生observer、控制Socket/worker和SDK ActionListener顺序回归；定格Base/Head经官方OCR规则和只读架构审查。实机听音/骑行及首次无线失联的物理原因单独保留限制。
