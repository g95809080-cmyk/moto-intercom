# KUM-69 固定提交审查与验证

Base SHA `d0655b5ae736a9c85c0a0f92688408e411a8c8b0`；源码/测试 Head SHA `58fa051c61a2d367322c07917a09859aa94768a3`。

独立只读 motointercom-product-architect：APPROVED；P0=0，P1=0，findings=[]。Next gate allowed：创建 Draft PR、运行 CI，并继续独立 KUM-70。Non-blocking：远端 CI 待提交，用户明确暂缓双机和蓝牙实机验收。允许 In Review，不代表 Done、合并或发布。

官方 OCR 选中 16/16；默认排除的 15 个 unit test 文件全部补审；共 31 reviewed、0 skipped。10 个新 blob 重新审读；21 个与此前 ba78a33 审查对象一致的 blob 经 Git 比较后复用。逐路径实际阅读深度见 coverage.json；diff/context 审查没有冒充完整文件或全项目逐行审查。

原 ba78a33 审查的 REQUEST CHANGES 已整改：fresh A 工厂返回晚于同 runtime Token B 时，必须拒绝 A；同一个请求的授权在原子采用、glare child 转移、普通 graph/terminal 决策和 Connected/SUCCESS 提交之间保持可撤销。clock、ID factory、数据库、Socket I/O 与外部回调均在短锁外。采用先于撤权时精确取消实际 child；Connected 先完成时请求授权完成，通话继续按 active-session 生命周期管理。

后续审查发现的原请求 sibling 错拒、普通失败抢先清 sidecar，以及迟到 Abort 覆盖已有 cleanup 均关闭。真实 Socket 的 originatingAttempt 不改写；只有原授权 lineage 和当前 wire/RESPONDER/peer/目标/截止/plan/phase 匹配才交接。实际加入媒体 cohort 的时间和重复 transport 仍由 actor 校验。撤权后排队的 EOF/send failure 不改 graph；迟到 Abort 仅重新核验已有 cleanup，保留对象身份与全部参数。

完整门禁：88 suites / 671 JVM tests，failure/error/skipped=0；Lint 0 errors / 70 warnings；Debug 与 AndroidTest APK 构建成功 1m48s。Lineage 五项使用真实 TCP HELLO、actor 和实际媒体 owner 选择，但 CONNECTED 通知由测试注入；Service 三项保持原 effect collector，覆盖真实 HELLO、lease 移交、实际 reader EOF/writer failure 和实际 Abort 投递。Activity 回归使用 fresh A/B IDs，覆盖同目标、异目标、adopt-first 撤权及物理清理先完成的顺序。

API 36 原生 OK (27 tests) /10.341s；adaf06f 到最终 Head 仅增加 JVM 测试，app/src/main 与 app/src/androidTest 完全相同，复用其原生证据。缺少 peer 的网络探针按原 assumption 跳过；不据此声称双机、Wi-Fi P2P 射频或蓝牙听音验收通过。没有改发行版本、合并、发布或部署。
