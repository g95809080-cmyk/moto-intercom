# KUM-68 验证与架构结论

Base SHA `6578f67499a3c1286cef60234575600138091b30`；源码/测试 Head SHA `25f6a9d760704e357065b6fb183da63c343d6cf8`。

独立只读 motointercom-product-architect：APPROVED；P0=0，P1=0，Non-blocking=远端CI与用户deferred实机验收；findings=[]；Next gate allowed=恢复KUM-69独立编码并等待CI。本结论允许In Review，不代表Done或实机验收通过。

官方OCR选中19/19，默认排除补审14/14，共33 reviewed、0 skipped。新增ASM与迟到callback/read原生测试全文重审；其他未变源码按blob对比复用原批准审查，并核验当前diff与producer/consumer、生命周期。逐路径记录见coverage.json，不表示全项目所有代码均已重新审查。

旧Head62932ff的两个P1已修复：pinned SDK停止后恢复缺少Init导致实际I/O不能重启；PCM尾部等注册锁与native Stop/join互等。完整测试又在ccbdecf暴露第三个P1：callback迟到返回仍进入已停止JNI，触发SIGSEGV；旧read和共享尾部也可能操作替代设备。该Head的远端CI37896699541虽绿色，审批仍改REQUEST CHANGES，未以一次CI成功掩盖崩溃。当前Head用同一Thread monitor互斥实际JNI与stopThread撤权、全run的immutable record/buffer、无效生产者立即退出及清理三重身份校验关闭该P1；没有放宽失败断言。

完整81 suites/631 JVM tests，failures/errors/skipped=0；Lint0 errors/70 warnings；Debug与AndroidTest APK构建成功4m42s。API36全量原生 OK (27 tests) /13.519s：超过SDK两秒join的旧callback、迟到read不清零B buffer、旧线程退出、B recorder继续录音、实际录音/播放线程及Android输出session ID重建、非零解码PCM写入、双向audio RTP继续增长、额外暂停恢复、原生故障与VOX回归。双设备网络探针缺peer按既有assumption跳过，不属于该P1的原生证据。最新远端CI：https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37898285291 。

SDK始终不实际播放，但仍可能初始化不播放的内部输出对象；NullAudioPoller保持解码与APM反向处理。路由受理/激活证据与实际录放音/RTP就绪分别核验；蓝牙提示不再提前声称音频就绪。native库、JNI ABI、版本和产品状态归属不变。

用户明确“没有条件复现，先搁置验证”。真实耳机听感、多应用SCO/A2DP共存及风噪/回声验收为deferred，不能由原生模拟器或配置证据判定通过。没有合并、发布或部署。


## 远端 CI 最终记录

[CI](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37898285291) 已完成success，全部2个job成功。审查源码 `25f6a9d760704e357065b6fb183da63c343d6cf8`；实际CI head `25f6a9d760704e357065b6fb183da63c343d6cf8`。实际CI head与审查源码完全相同。精确响应、job结果及差异路径见ci.json。

Draft PR #43和Linear保持InReview；实机蓝牙、多应用听音、双手机按用户要求deferred，iOS排除。未合并、改版本、发布或部署。
