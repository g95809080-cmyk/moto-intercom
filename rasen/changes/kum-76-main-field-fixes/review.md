# KUM-76 固定源码架构与 OCR 复审

APPROVED（Android 整合源码；远端验证单独记录）。P0：0。P1：0。Findings：[]。

Base SHA：`0f5e7b778ee0b945725c9506055d6137a7faf546`。
Head SHA：`5b9b6a7d853446280403f20a1b0fed39f370308a`。

三位只读审查者使用项目 AGENTS、motointercom-product-architect、Linear KUM-76/Rasen 合同及官方 alibaba/open-code-review 1.12.13 delegate preview/rule。主 Agent 是唯一写者。

| 审查者 | 实际责任范围 | 最终阅读与结论 |
| --- | --- | --- |
| logging_architecture_review | Service、Socket admission、发现、恢复、UI、日志及 canonical owner | APPROVED。LAN/Wi-Fi 两个生产文件、两份真实 Socket 回归和 spec 重新全文读；80 个既有路径逐 blob 验证相同，保留此前 full/diff/context/reuse 深度 |
| ocr_app_support_audit | 共享采集、独立 renderer、PCM/VOX、群组媒体/房间、ASM/R8、原生生命周期 | APPROVED。7 个 native 文件重新全文读；其余42个源码/上下文与原批准 blob 相同。合计49个全文深度路径 |
| ocr_main_platform_audit | 主线 UI/诊断、平台/CI、BLE预算、Windows故障脚本及直接回归 | APPROVED。LAN fixture和spec全文重读；41个reviewed与两个context文件逐blob相同。个人清单为43reviewed、2context、69delegated/skipped |

CI `37949225378` 使此前 `44313de` 的批准重开。确认一类生产P1：并发pending registry的 `size == 1 → iterator.next()` 与真实 `onReleased` 删除最后一个lease交错，抛出异常后中断后续关闭。最终先取数组快照，两份确定性真实producer回归在旧源码红、修复后两个完整类13/13绿。真实Service/Tunnel消费链未改为等待callback来掩盖缺陷。

同一CI的原生失败来自跨fixture异步清理缺少结束屏障。最终每个engine保留自己的实际onDisposed回执、RTC终止、精确token、实际录音/输出线程和四个平台字段验证；先关闭全部自有资源再等待，收集清理异常，原fatal全局空断言仍保留，不清空全局owners。新增实际RTC/token阻塞负对照，健康B/C的PCM/RTP和所有权保持；A当时没有建立PC，不声称该例证明A的活动PCM退出。

首次整合另修复旧输出worker的迟到错误回执，以及群组全部成员就绪证明聚合、撤销后保留热媒体lease和要求新RTP；这些源码与最终Head逐blob相同，复用原实际阅读记录。

Non-blocking：KUM-77群组网络归属和KUM-78验收工具是独立开放的基线缺陷，本次源码批准不表示父KUM-64完成。普通蓝牙、双手机、导航/音乐混播和风噪听感按用户要求暂缓；iOS按用户要求排除。

Next gate allowed：固定 Head 的远端 Android CI 已全部通过（37955528528），证据完整，可进入 Draft PR 人工审阅；下一独立 KUM-77 可继续。本结论不授权合并、发布或部署。

`coverage.json` 记录固定Base→Head的114个变更路径：70默认选中、44默认排除后手动补审，全部有实际阅读记录。100%表示变更清单归账完整；full、完整diff/context、已核验此前全文复用分别保留，不表示未变文件全部重读或实机验收。
