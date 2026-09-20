# KUM57 修复设计
Base 5457da4。保留writer独占状态、当前lease/attempt检查。

双人：IntercomManager在其当前media生命周期内每秒采样已有queryEvidence；AudioSessionController提供当前实际route evidence。独立小判定器要求PC connected/remote track/native IO/route+同流RTP双向增长，校验查询前后route及gate/native代次；静音/VOX后保留已验证就绪，I/O或route失效/PC断开则撤销。Service发布具名onAudioReadyChanged并在绑定回放，Activity转发到screen。关闭和旧回调不得重新激活。

群组：writer提交快照后先Publish，再执行可同步Stop效果，确保Service清空runtime前收到IDLE。Service释放回调仍复制runtime最终快照作为防线。同步scan失败、候选耗时/无回调有总体60秒期限；不得将未完成集合中的首个匹配静默选中。阶段进度回传writer，cancel先撤销operation再关闭异步对象。

BLE：请求MTU247，回调前不开放应用交换，2秒无回调采用默认20字节；MTU安全取min(mtu-3,244)。server每peer跟踪MTU后分片；assembler接受5..244长度、连续offset、总长<=8192；不降低PAKE/AEAD要求、不延长20秒单候选auth期限。协商失败默认20可明确超时，不能无限等待。新增阶段日志不含房间码、凭据或认证字节。

验证：可控同步结束→Service detach、取消/latecallback、分片20/244/边界、真实PAKE跨分片、双人缺证据/增长/静音/旧回调/route变更。全量JVM/Lint/APK及API36 CI。A可用设备验证；B尚未连接，实际互听不虚报。
