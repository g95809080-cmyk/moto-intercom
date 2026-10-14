# KUM-83 验证记录

Base `fd0bcd0ff194e165216e7d2b515c0f4f52a82552`；固定源码 Head `ff19c4f152f3800b19be769f7149fbfc748e0db5`。正式只读架构审查 `APPROVED`，P0=0/P1=0，25/25路径覆盖、0 skipped。其后仅补证据文档，生产/测试/构建 blob 与该 Head 相同。固定源码 [Android CI 38230913237](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38230913237) completed/success：verify job `114749876580` 与 API36 job `114749876378` 均成功；不代表已完成双机或骑行验收。

## 本地自动化

该 Head 的 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 成功，96 suites / 793 JVM tests，failures=0 / errors=0 / skipped=0。Lint errors=0；70 warnings 与基线一致。严格 `rasen validate kum-83-transient-link-recovery --strict --no-interactive --json` 有效。

Owned API36 AVD 全量 instrumentation：26 pass / 2 双机条件不满足的 assumption skip / 0 failure；Runner `OK (28 tests)` 包含 skip，不能写成28通过。只给本次 owned emulator 安装候选 debug/test APK，未部署小米13、未改版本或 approvedCommit。

真实 JNI 两端 PeerConnection 经测试 UDP relay 断流后，同一 source 在52ms内登记 CONNECTED；RTC executor 与 Main 同时暂停时，实际 Handler timer 仍读到入口证明，实际 PeerConnection 没有更换。该测试覆盖 native entry / delayed delivery，不等于实际 Service 授权或双手机无线稳定性；后者分别由 Service 集成回归与待实机验收承担。

Debug APK SHA256（仅 owned AVD 验证）：`C729C9F2B648463F97CBD55BAEADA3F94C8F65BCA946AF3B02EE155A97C4255E`。

## 缺陷复现与实际接线

- native timer→ID factory 阻塞时，真实入口收据原被同锁阻塞；红测失败，改为锁外准备、短纯元数据 claim，回归通过。
- 另一 producer 早500ms、其事件交付晚于 timer 已采用新 attempt：原 deadline 晚500ms，off/on可取新票；两条红测均失败。exact owner producer ledger、同毫秒入口序号及最终 frozen claim 后通过。factory期间更早 hard receipt、旧票失效、无关 snapshot/成功发送/sibling/缺channel/retired producer 均覆盖。
- D1/C1都已捕获才交付D1，以及C1未交付又有D2：软自愈清除旧 ledger、后续独立窗口/票/time通过，保持原connectedAt/SUCCESS/配对。
- 实际 TCP HELLO→Service 原 effect collector→IM→SDP Answer 集成：当前2秒观察保留实际 Socket/IM；actual EOF producer 的 Main交付延迟2.5秒仍使用原首轮10秒预算；实际IM失败在Main前冻结授权，off/on不能补票；stop撤销窗口及晚回调。
- 双 origin 实际 HELLO/RECOVERY/glare→Service选定媒体owner，actual ACCEPT后、actor确认发送前收到实际 Offer：只缓存，正式 StartWebRtc采用后才创建IM；stop期间缓存清空，不能创建媒体。旧R1 P2P回调不能清理获准G。
- 实际 SDK info/group callbacks 与 TCP/HELLO worker：空广播必须查询真实 group；旧negative/startup/重复ACK/BUSY不得清理继任 Socket/group。真实 EOF三连，同attempt reentry不补两次重试次数；prepared B暂停期间旧removeGroup ACK必须同步native已删除事实，再resume才发SDK connect。
- native清理缺ACK时，全程同一个绝对五秒期限；replacement不能补等待时间，超时只失败P2P分支，未获ACK仍不越过屏障。实际错误HELLO不走普通EOF重试捷径。

详细输出留在忽略 `logs/2026-10-12-field-review/`：`kum-83-after-adopt-red.log`、`kum-83-final-entry-and-budgets-2.log`（paused cleanup缺口）、`kum-83-final-entry-and-budgets-3.log`（115定向绿）、`kum-83-full-gate.log`、`kum-83-local-counts.json`、`kum-83-full-native.txt`、`kum-83-full-native-recovery-logcat.txt`。原始用户日志/dump不入Git。

## 官方 OCR 与架构审查

官方 Alibaba Open Code Review 1.12.13 `delegate preview/rule` 固定 Base/Head：25 changed paths，14 selected / 11 excluded。只读 reviewer 使用官方规则，并手动覆盖 excluded 测试/docs：18 full_source（含7合同/证据文件）+7 diff_context；大型未改段未记作全文。固定范围 `APPROVED`，P0=0/P1=0。未调用远程OCR LLM，不把规则输出当代码批准。完整 path/blob/depth 见 `coverage.json`。

Next gate allowed：补交真实证据、确认生产/测试 blob 不变；独立Draft PR及固定源码Head Android CI。CI闭合后推进KUM-84。唯一非阻断补强为 Connected 观察中 same-peer RECOVERY 入站分支的直接回归；当前真实双origin HELLO用例主要从Connecting入场，该分支未发现确认缺陷。

[Draft PR #56](https://github.com/g95809080-cmyk/moto-intercom/pull/56) 已绑定KUM-83；Source之后文档Head `f259ea31cd419303fb80b02d09490bc2815b8945` 只有4项Rasen证据变化。`git diff --quiet ff19c4f HEAD -- app .github scripts version.properties` 成功，未改生产、测试、构建、workflow或版本blob。CI固定经过架构审查的Source Head；后续CI链接与任务状态文档不重复运行行为检查。

证据绑定复审 Base `fd0bcd0f` / Head `f259ea31` 为 APPROVED，P0/P1=0；25 path/blob全部匹配获批Source，生产/测试/构建diff为零。已通过 GitHub API核实 run/job 两项成功并写入Linear；KUM-83进入 In Review，真实验收仍未通过。Next gate允许CI/Linear证据闭合后进入独立KUM-84，纯文档后续不重复行为CI。

## 实机限制

首次RF/ICE失联原因缺少当时系统及候选对数据，不能据此认定已解决。仅新增首次native loss的candidate-pair状态/RTT/字节/候选类型诊断，不记录候选IP或凭据。真实小米6/API28＋小米13/API36蓝牙、双机骑行与用户听音均未运行；必须在条件允许后验收。iOS跳过。
