# Android 审查与修复报告（KUM-64）

本轮完成六项故障对应的确定代码缺陷及后续 Android/诊断/验收工具缺陷修复，12 个独立修复任务均有固定源码 APPROVED、P0=0/P1=0 和成功远端 CI。全部保留为 Draft PR，未合并、发布或部署本轮修复。普通蓝牙、多应用听音、风噪、Android 9/16 双手机无线验收按用户要求暂缓，不能据自动化结果宣告六项现场表现全部消失。iOS 全部排除。

## 六项报告对应的处理

| 序号 | 问题 | 已完成的代码处理 | 证据与状态 |
| --- | --- | --- | --- |
| 1 | 多应用音频焦点与中断恢复 | 每次独立 focus listener/request，失效 revision 撤权；延迟焦点只在真实 GAIN 后恢复；永久失焦和结束释放当前焦点及通信模式，保留电话优先。 | [KUM-65](https://github.com/g95809080-cmyk/moto-intercom/pull/40)；实机效果暂缓 |
| 2 | VOX 连续检测及真正的上行静音 | 唯一采集器持续进行编码前 PCM 检测，关闭上行时真正清零；区分噪声学习和灵敏度，保留手动静音优先，隔离旧 revision。 | [KUM-66](https://github.com/g95809080-cmyk/moto-intercom/pull/41)；实机效果暂缓 |
| 3 | 软件降噪与原生错误归属 | 固定软件 NS 路径，保留 AEC/AGC/Opus；增加实际 recorder/PCM 诊断与 ADM 错误回执，按当前原生线程及媒体归属撤权。 | [KUM-67](https://github.com/g95809080-cmyk/moto-intercom/pull/42)；实机效果暂缓 |
| 4 | 蓝牙首次选路及实际录放音恢复 | 有界重试并核对当前设备；实际录音、新鲜 PCM、Android 输出写入和双向 RTP 才报告音频就绪；重新 Init 并隔离旧线程/缓冲与替代设备。 | [KUM-68](https://github.com/g95809080-cmyk/moto-intercom/pull/43)；实机效果暂缓 |
| 5 | 发现、连接候选和迟到请求撤销 | 保留候选及验证身份；绝对 HELLO 截止、可取消实际 Socket；从请求到最终采用/Connected 提交持续核对可撤销授权及 lineage，I/O 在短锁外。 | [KUM-69](https://github.com/g95809080-cmyk/moto-intercom/pull/44)；实机效果暂缓 |
| 6 | 自动重连授权与真实新发现 | 保留原 verified deviceId；精确 ResetCompleted 后等待 30 秒，只接受当前 adapter 的真实新发现启动新三次尝试，重新 Socket/HELLO；手动操作在 Room await 前同步撤权。 | [KUM-70](https://github.com/g95809080-cmyk/moto-intercom/pull/45)；实机效果暂缓 |

“音乐进度继续但耳机无声”涉及普通蓝牙的 SCO/A2DP 路由与系统行为，已确认的焦点和原生录放音缺陷已修复；没有实际耳机听音证据，不能把它归因于品牌，也不能报告混播已通过。VOX 的编码前清零和持续检测已有 JNI 证据，降噪的请求配置和 recorder 诊断已有源码证据；两者的骑行听感仍待实机。

## 七天本机日志

稳定 v1.2.1 的 [日志修复测试 #38](https://github.com/g95809080-cmyk/moto-intercom/pull/38) 已在此前安装到小米 13；[主线移植 #49](https://github.com/g95809080-cmyk/moto-intercom/pull/49) 保留同一日志行为。日志存于本机应用目录，滚动保留近 7 天，自动清理过期记录，支持重启后查看与导出。单段 1 MiB，总预算 256 MiB；预算耗尽时停止新增并记录缺口，不删除仍在保留期内的历史来假装完整七天。导出目录读取失败现在明确报错，保留未过期分享及原字节。

本轮审查修复没有覆盖安装到手机，也没有提高到 1.4。维护线保持既有 1.2.1 测试版本；main 的既有 1.3.0/versionCode 保持原值。后续修复分发按项目修订号规则单独进行。

## 后续审查确认的缺陷

| 任务 | 问题 | 已完成处理 | 审查入口 |
| --- | --- | --- | --- |
| KUM-71 | 日志导出目录失败绕过预算 | 两次目录读取均明确失败；TTL 清理及新文件创建前退出，现有 worker 返回失败并保留未过期分享原字节。 | [Draft PR](https://github.com/g95809080-cmyk/moto-intercom/pull/46) |
| KUM-72 | 群组准入时钟与额度并发 | 完整 acquire 时钟、窗口清理、额度检查和记账共用 monitor；Socket I/O 保留在锁外。 | [Draft PR](https://github.com/g95809080-cmyk/moto-intercom/pull/47) |
| KUM-75 | 网络故障脚本错误退出码 | 每条 native 命令检查退出码，失败停止后续命令；Mode 必填，保留模拟器/root/接口约束。 | [Draft PR](https://github.com/g95809080-cmyk/moto-intercom/pull/48) |
| KUM-76 | 主线移植、群组媒体与关闭并发 | 共享 ADM/factory/track 保留唯一归属，成员各自 PC/renderer/readiness；快照关闭 pending leases；精确撤权，按实际资源完成回执等待自有 fixture 清理。 | [Draft PR](https://github.com/g95809080-cmyk/moto-intercom/pull/49) |
| KUM-77 | 群组控制连接关闭与迟到认证 | 安装前后及 Main 终点检查 attempt、channel、adapter、queue；exact remove 只清理自身；局部连接在 finally 独立释放，当前异常断链报告真实 onFailed，数组快照关闭。 | [Draft PR](https://github.com/g95809080-cmyk/moto-intercom/pull/50) |
| KUM-78 | 协议及双设备验收误成功 | 验证合法终态；解析真实 DataStore 身份，核对不同物理设备、前后 APK/身份、所有 native 退出码和完整 SQLite integrity；先将当前结果标 NotRun、独立保留历史、最后原子发布。 | [Draft PR](https://github.com/g95809080-cmyk/moto-intercom/pull/51) |

旧源码的误成功、原生迟到回调/JNI 崩溃和关闭并发回归均保留失败记录，再以修复源码运行；不会用一次 CI 绿色覆盖已知失败。KUM-78 的 Pass 是操作者输入的场景结果；mock 测试只证明采集工具不会将失败或旧结果误当当前成功。

## 覆盖范围与方法

使用官方 [alibaba/open-code-review](https://github.com/alibaba/open-code-review) CLI 1.12.13，工具提交 fabbdb296b0d97e2140ada8d54ca7d6d6d1d7ad4。实际执行固定范围 scan --preview、delegate preview/rule；未配置 OCR LLM endpoint，不声称运行了其独立模型服务。主 Agent 唯一写入，独立只读审查者按文件、完整差异、producer/consumer、实际失败和回归核验架构。

稳定基线 `e958fff38c98177a9995343601eb82d75e3cbdd6`，main 基线 `0f5e7b778ee0b945725c9506055d6137a7faf546`。两次扫描分别 523/630 项，其中固定 Git 树 520/628 项，其余是扫描时的两个用户未提交文档与父任务初始化元数据。coverage.json 逐路径保存基线 SHA、blob、实际阅读深度及排除理由。

默认选中范围去重后 254 项：216 reviewed、16 明确 skipped、22 用户排除的 iOS 项，unknown=0；范围归账率 100%，包含排除项的阅读率约 85.04%。216 项中 203 项在其所有存在的原基线已全文阅读，12 项稳定线全文/main 完整差异与上下文，1 项只存在父任务初始化提交的两行元数据。另补查默认排除扩展名 65 项：50 项至少一份原基线全文、15 项仅上下文；不把上下文或后续 SHA 阅读算作原基线全文。

16 个明确跳过项为 6 个随仓附带 Agent/浏览器工具、9 个历史生成 XML/JSON 证据及 stock gradlew；理由逐项记录。所有 ios/** 与 iOS CI 按用户指令排除，原 KUM-73/74 保留 Backlog，既有分支不继续执行。这份覆盖不表示所有潜在缺陷已被证明不存在。

## 自动化结果

最新应用源码 `1f636f787b7ee1b62c834bf642a14823fff560fb`：114 suites / 886 JVM tests，0 failures/errors/skipped；Lint 0 errors、79 warnings；Debug 与 AndroidTest APK 构建成功。API 36 实际 XML 为 32 个用例：30 通过、2 个因缺双机角色参数按既有 assumption 跳过，0 failures/errors。跳过的是 SharedNetworkNsdTest.exchange 与 SyntheticAudioNetworkTest.exchange，不计作双设备验收成功。

KUM-78 `018b6827bce93c928d3ce7883d3230456a3dfcb8` 的 app Git tree 与上述源码完全相同（`4ee9ced9fdc65439a9a91f02d886bff517668757`），本地应用证据按相同 blob 复用，远端重新完成全部 Android/Windows CI。Windows PowerShell 5.1 实际子进程 45 个采集场景、14 项 Python TCP/CLI 测试、34 个原故障脚本场景全通过；另 8 项关键场景重复通过。无真实手机操作。

| 任务 | Draft PR | 审查/测试源码 | 实际 CI head | CI |
| --- | --- | --- | --- | --- |
| KUM-65 | [#40](https://github.com/g95809080-cmyk/moto-intercom/pull/40) | `6c8e56c` | `6c8e56c` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37882188306) |
| KUM-66 | [#41](https://github.com/g95809080-cmyk/moto-intercom/pull/41) | `34229a3` | `34229a3` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37885467933) |
| KUM-67 | [#42](https://github.com/g95809080-cmyk/moto-intercom/pull/42) | `985aeb8` | `6578f67` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37888252920) |
| KUM-68 | [#43](https://github.com/g95809080-cmyk/moto-intercom/pull/43) | `25f6a9d` | `25f6a9d` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37898285291) |
| KUM-69 | [#44](https://github.com/g95809080-cmyk/moto-intercom/pull/44) | `58fa051` | `579b641` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37915204711) |
| KUM-70 | [#45](https://github.com/g95809080-cmyk/moto-intercom/pull/45) | `99fc2bc` | `0616b8f` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37924933236) |
| KUM-71 | [#46](https://github.com/g95809080-cmyk/moto-intercom/pull/46) | `d095e03` | `d7eaa6c` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37925795827) |
| KUM-72 | [#47](https://github.com/g95809080-cmyk/moto-intercom/pull/47) | `ba4550d` | `221ad3b` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37966744534) |
| KUM-75 | [#48](https://github.com/g95809080-cmyk/moto-intercom/pull/48) | `65c2320` | `d83a6f8` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37966772679) |
| KUM-76 | [#49](https://github.com/g95809080-cmyk/moto-intercom/pull/49) | `5b9b6a7` | `5b9b6a7` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37955528528) |
| KUM-77 | [#50](https://github.com/g95809080-cmyk/moto-intercom/pull/50) | `1f636f7` | `1f636f7` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37961390317) |
| KUM-78 | [#51](https://github.com/g95809080-cmyk/moto-intercom/pull/51) | `018b682` | `018b682` | [success](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37965905864) |

审查源码与 CI head 不同的行已经用 Git 逐路径核对，仅 rasen/changes/** 的审查元数据不同，应用、工具、构建和工作流相同。当前 PR head 与源码的差异也分别核对，仅 Rasen 元数据。完整 SHA、job 结论、真实响应文件 hash 在 ci.json 中。

KUM-76 最新元数据 head `170e7ff` 的 [CI](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37957257683) 也已成功。首次原生任务因 Google Maven 下载 androidx.test:core-ktx:1.7.0 返回 HTTP 502，尚未运行测试；只重跑失败的原生 job，已有 JVM/Windows 成功结果保留。失败日志及第 2 次实际响应保留在证据中。

## Git 与证据入口

维护线依赖为 #38 日志 → #39 审查记录 → #40–#46 六项故障及导出补修。main 独立 #47/#48 的预算及脚本修复已按相对 main 的增量纳入 #49；#49 → #50 → #51 保留递进审查关系，不能把这些 PR 当作互不相关的整包重复合并。#39 是本报告与覆盖的唯一归属。所有 PR 仍为 Draft，Linear 保留 In Review；没有自动 merge、force push、删远端分支、签名或设备部署。

机器可读入口为 findings.json、coverage.json、ci.json；每个子任务 Rasen 的 review.md、coverage/ci/evidence 文件与对应 Draft PR 可追到固定源码。原始成功、失败日志及 XML 保存在本机 logs/kum64-open-review；ci.json 保存引用文件 SHA256。两个原有用户未提交产品文档未修改。收尾返回常驻 main，报告副本留在忽略日志目录，便于本机继续查看。
