# 跨设备交接：Android 骑行问题审查与 LAN 实测失败

交接日期：2026-10-11（Asia/Shanghai）。当前代码修复、独立审查与 Android CI 已完成，KUM-81 保持 **In Review**。用户目前不方便连接手机，真机互通、蓝牙听音和风噪验证暂缓；现场 `EHOSTUNREACH` 的根因及连接恢复尚未确认。

本次交接仅增加文档。继续工作使用 `fix/kum-81-lan-network-failure`；旧设备收尾返回常驻 `main`。先读取本文件、项目 `AGENTS.md`、KUM-81 的 proposal/design/spec/tasks、[verification.md](../verification.md) 和 [coverage.json](../coverage.json)，再核对 Linear 与 GitHub 最新状态。

## 在另一台设备接续

仓库：<https://github.com/g95809080-cmyk/moto-intercom>。已有 GitHub 访问权限的新环境可以执行：

```sh
git clone https://github.com/g95809080-cmyk/moto-intercom.git MotoIntercomApp
cd MotoIntercomApp
git switch --track origin/fix/kum-81-lan-network-failure
git status --short --branch
```

已有 checkout 先检查本地修改，再正常 fetch/switch。主代码目录是 `MotoIntercomApp`；同名前缀历史目录不作为正式发布来源。交接后的最新文档提交见 [Draft PR #54](https://github.com/g95809080-cmyk/moto-intercom/pull/54)。本次不创建新任务、不移动 Linear 路线图、不合并 PR。

## 用户目标与约束

用户要求用 [Alibaba Open Code Review](https://github.com/alibaba/open-code-review) 审查六项骑行问题和潜在缺陷，修复并做好 Git 管理；日志在本机保留近7天。用户明确：**跳过 iOS**；这些是修复测试，版本沿用 `1.2.x` 修订号，不因为本轮修复升到 `1.4`。

设备为小米6 / Android9（API28）、小米13 / Android16（API36）。头盔耳机维脉通 V9X、魔多狼 BH1；普通降噪蓝牙耳机也能复现，用户已说明与品牌无关。高德/网易云问题表现为播放器进度继续、耳机无声。不要再次把排查限定到某个耳机品牌。

用户已要求蓝牙现场验证暂缓，后来又说明当前不方便连接小米13。接续阶段保持这一安排，待用户具备条件时再取得当前无线 ADB 地址/必要配对信息；历史端口不能作为当前在线证据。

## 已安装版本与 Git 来源

| 项目 | 固定值 |
| --- | --- |
| 常驻 main，交接前未修改 | `0f5e7b778ee0b945725c9506055d6137a7faf546` |
| KUM-81 基线 / PR #53 head | `1ac1e3eb42a36d5ae5557bff767f2f2374aa1072`，`test/kum-79-v1.2.2-xiaomi13` |
| KUM-81 行为提交 | `907f2c388e96c11d461a9c869310ddd36e6d41a4` |
| 独立架构审查与 CI Head | `d2827ec1b11d923a3559a8b5bd52b4dd65116fd1` |
| 交接前证据提交 | `36a7f7450e7dad89a0e6034fd124d438307af44a`，仅 Rasen 文档 |
| 小米13最后实际安装 | `1.2.2+bb0fa92` / `versionCode=6` |
| 已安装包批准应用源码 | `bb0fa92ee7326844891967981355ccf93c9c8639` |
| 已安装包实际构建提交 | `7338a7a33a4f3dc694c3af83e14fd9b4fbb9b30a` |
| 已安装包 SHA256 | `12e67949ed42f1574ae1054ab72c47da06bf77dd4ffa290f8c4e0419158d07bd` |
| 沿用分发证书 SHA256 | `7f20f38dc1d7372cde34cac6e0e17d80ec995ac298c298fd0d24605e1a8070f3` |

2026-10-10 经用户授权无线 ADB 覆盖安装小米13成功，冷启动正常，原有授权和设置保留。日志页能读取旧版记录，近7天导出进入系统分享选择器，随后取消分享。详情在 `rasen/changes/kum-79-v1.2.2-xiaomi13/verification.md`。

**KUM-81 修复尚未部署。** 当前修复分支的 `version.properties` 仍为 `1.2.2/code6/approvedCommit=bb0fa92…`，不代表这个旧后缀批准了后续 LAN 行为提交。后续需要部署时，依照 `docs/releases/versioning.md` 和实际修复测试授权准备版本、批准源码、严格递增 versionCode 与既有分发证书；每次核对实际 APK 来源并覆盖保留数据。本次交接没有打包、使用私钥或操作手机。

## 六项原问题与其他审查结果

下列 Android 问题在 2026-10-11 查询时均为 **In Review**。对应稳定修复已进入上述已安装源码 bb0fa92 的历史，源码和自动化结论见各自 Rasen `review.md`；现场体验结论保持待验证。

| 序号 | Linear / Rasen change | 修复和仍待验证的内容 |
| --- | --- | --- |
| 1 | KUM-65 / `kum-65-audio-focus` | 焦点丢失、延迟获准、恢复及媒体结束的通信模式释放；普通蓝牙下高德/网易云实际并播待验证 |
| 2 | KUM-66 / `kum-66-vox-transmit` | 实际上行 PCM 门控、灵敏度与设置 revision；有双 PeerConnection 原生回归，骑行噪声下远端听感待验证 |
| 3 | KUM-67 / `kum-67-noise-processing` | 软件 NS 请求与实际输入诊断、迟到原生回调归属；配置/模拟器不能证明风噪听感 |
| 4 | KUM-68 / `kum-68-bluetooth-routing` | 初始蓝牙路由/SCO 有界重试、录放音重建、旧 native producer 撤权；普通蓝牙首次路由和并播待验证 |
| 5 | KUM-69 / `kum-69-connection-races` | 地址/请求新鲜度、P2P 清理、目标和实际 Socket lineage；后续真实 LAN 失败单列 KUM-81 |
| 6 | KUM-70 / `kum-70-automatic-recovery` | 重连开关、原 verified deviceId 恢复意图、真实发现/Reset/probe；长期失联与双机射频待验证 |

本机近7天日志最初由 KUM-63 完成。后续 KUM-71 修复导出目录枚举失败绕过容量预算，KUM-80 回补稳定线 pending Socket 并发关闭快照；已安装 1.2.2 包含这两项。

稳定分支末端依赖为：KUM-70 → KUM-71 [PR #46](https://github.com/g95809080-cmyk/moto-intercom/pull/46) → KUM-80 [PR #52](https://github.com/g95809080-cmyk/moto-intercom/pull/52) → KUM-79 [PR #53](https://github.com/g95809080-cmyk/moto-intercom/pull/53) → KUM-81 [PR #54](https://github.com/g95809080-cmyk/moto-intercom/pull/54)。它们仍为开放的 Draft PR；父分支改动需要保持，不能假定已合并 main。

其他 Android 潜在问题也已独立修复并保留 **In Review / Open Draft**：

| Linear | PR / 分支 | 内容 |
| --- | --- | --- |
| KUM-72 | [#47](https://github.com/g95809080-cmyk/moto-intercom/pull/47) / `fix/kum-72-group-budget-concurrency` | 群组共享 BLE 准入预算并发保护 |
| KUM-75 | [#48](https://github.com/g95809080-cmyk/moto-intercom/pull/48) / `fix/kum-75-network-fault-exit` | 模拟器故障命令检查实际退出码 |
| KUM-76 | [#49](https://github.com/g95809080-cmyk/moto-intercom/pull/49) / `fix/kum-76-main-field-fixes` | 稳定修复回补 main，共用音频与群组 owner 保留 |
| KUM-77 | [#50](https://github.com/g95809080-cmyk/moto-intercom/pull/50) / `fix/kum-77-group-control-ownership` | 关闭后迟到群组认证/连接清理 |
| KUM-78 | [#51](https://github.com/g95809080-cmyk/moto-intercom/pull/51) / `fix/kum-78-acceptance-evidence` | 协议验收工具终态检查、真实双设备标签 |

主线 #49 → #50 → #51 是另一条依赖链；#47/#48 独立基于 main。这些主线 PR 不在当前稳定 LAN 修复分支中，后续查看其代码应切到对应分支。iOS KUM-73/KUM-74 保持 Backlog，按用户要求跳过。完整路线图与状态以父 [KUM-64](https://linear.app/kuma999/issue/KUM-64) 及其子问题为准。

## 当前 KUM-81：现场事实与修复

[KUM-81](https://linear.app/kuma999/issue/KUM-81/实测lan不可达修复wi-fi选路与异步连接失败处理) 来自用户最新实测日志。两台同版、同路由器 Wi-Fi、均启动；小米6的 UDP/NSD 持续发现 `192.168.1.160`，本机 `192.168.1.108` 连接对端 TCP8890 两次 `EHOSTUNREACH / No route to host`。错误发生于 2026-10-10 UTC 16:59:50.206 与 16:59:54.067（北京时间 2026-10-11 00:59:50.206 与 00:59:54.067），尚未进入 HELLO/身份校验/SDP/语音。

原日志本机源地址已是 Wi-Fi IP，所以默认网络错误选路仅是待查假设；对端监听、系统策略、路由器互通尚未区分。P2P BUSY 在刷新/关闭阶段出现，也未证实为本次 TCP 失败原因。发现成功不等于 TCP 可达。

已改动的生产文件只有 `LanDiscoveryCoordinator.kt` 与 `IntercomService.kt`：

1. LAN socket 在 connect 前绑定匹配本机 IPv4 的非 VPN Wi-Fi Network，不要求 INTERNET/VALIDATED，只绑定当前 socket。
2. 提交、绑定、TCP、HELLO 失败携带原 runtime/attempt/transport，交给既有 actor `TargetedTransportOpenFailed`；Service 在 Main 最终检查精确归属，失败先释放实际 pending lease/socket。
3. 最终 claim 与失败标记共用短锁，阻止发现回调在 worker 失败后重复提交；停止/替换后的旧回调不能影响新尝试或界面。
4. 日志增加实际 listener 就绪、绑定网络与地址，以及 `SUBMIT / WIFI_BIND / TCP / HELLO` 阶段。

adapter 的失败标记只属于执行层，逻辑状态仍由 actor 单独写入。单 LAN 及时 FAILED，既有双通道计划保留可用分支；目标、总截止时间、HELLO 身份校验和协议未改变。不要把本修复描述为已恢复手机连接。

## 审查与验证证据

- 固定 `d2827ec…` 独立只读 `motointercom-product-architect`：**APPROVED / P0=0 / P1=0 / findings=[]**，8/8 reviewed、0 skipped。7项全文，Service 为完整差异及完整受影响函数/归属上下文，未冒充 Service 全文。精确 blob 见 `coverage.json`。
- 官方 Open Code Review CLI **1.12.13** 的 `delegate preview/rule` 生成范围和规则：默认3项，另5项补审；结合独立模型审查。没有调用远端 OCR LLM 服务，不能宣称有该服务的审核结果。
- 原生产源码基线10项红回归；补充真实 claim 竞态2项红（期望1个 socket、实际2个）。最终新增实际 Service/worker 测试11方法 × SDK28/35，22项全通过。
- 完整本地 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`：93 suites、736 tests，0 failures/errors/skipped；Lint0 errors/70 warnings；双 APK 构建成功。Rasen strict 通过。
- 固定 SHA [Android CI 38073584716](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38073584716) 两个 job 均 success，交接时已重新核对 head=`d2827ec…`。
- API36 XML：16 suites、27 tests，25项实际执行通过，2项跳过，0 failures/errors。`SharedNetworkNsdTest.exchange`、`SyntheticAudioNetworkTest.exchange` 需要双设备 `role=server|client` 参数，单模拟器 CI 未传入，按前置条件跳过。
- Robolectric 使用真实 Socket、worker、pending lease 和 Service，`Network.bindSocket` 为 shadow；不证明手机内核选路或两机物理互通。

交接前证据提交和本次交接仅修改文档，产品源码/测试/构建配置与已审查 CI Head 相同。仅切换设备与读取文档无需重跑所有测试；有新的行为改动、失败或未解决疑点时，按对应改动重新验证与审核。

## 接下来怎么做

1. 先核对仓库/分支、Linear KUM-81 和 PR #54 最新状态，保留已有父分支、固定 SHA 与证据。当前无正在运行的构建、CI等待或待答复提问。
2. 手机访问仍未恢复时，以本交接和 Git 中的证据接续；不要重复要求现在连接或重新询问已确认的同版/同路由器/均启动。
3. 用户恢复验证条件后，读取两端当前地址、系统路由、应用 listener 与 TCP8890 监听情况，收集同一次连接的双方近7天诊断日志；用新增阶段字段区分绑定、TCP、HELLO。再判断应用缺陷、端点监听与路由器互通原因。
4. 新修复继续用真实 worker/Service 的必要红绿回归与固定 SHA 独立架构审查；不改变已批准身份校验和连接总预算。需要新独立问题时，按一个 Linear/Rasen/分支/PR 管理。
5. 蓝牙并播、首次选路、实际 VOX/降噪听感和长期自动重连仍待用户安排实机复测。新的部署依实际指令和版本规则进行；不自动合并 main、正式发布或推进 iOS。

## 工程规则与环境

- 根 `AGENTS.md`：Linear 管路线图/状态，Rasen 管当前问题合同和 checkpoint，Git 管源码，GitHub 管 PR/CI；一次只有一个写者。源代码行为变化必须经过只读 `motointercom-product-architect` 审查，P0/P1 清零后才能前进。
- 所有 Rasen 命令设置 `DO_NOT_TRACK=1`、`RASEN_TELEMETRY=0`。handoff 归属于既有 KUM-81，不新建路线图任务。
- 构建需要 JDK17、项目 Gradle wrapper 与 Android SDK。旧 Windows 机 JDK 为 `F:/Android/jbr`，`M:` subst 指向主目录用于绕过 Unicode 路径；新设备按实际路径配置，不假定这些盘符存在。
- 官方 OCR 安装/用法参考其仓库；旧 Windows CLI 在 `C:/Users/kuma/.codex/tools/ocr-runtime/node_modules/@alibaba-group/ocr-win32-x64/bin/opencodereview.exe`。新设备重新确认工具可用，复用固定审查结论时核对源码 blob。

## 仅在旧设备的数据

旧 workspace 为 `C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp`。以下内容未提交 Git，**不会随 clone 自动出现**：

- `logs/2026-10-11-lan-connect/`：现场原始日志、排查记录、红绿 XML、完整本地构建输出、官方 OCR 输出、CI状态及已取得的 API36 日志/XML。完整 CI 构建产物额外下载已停止；CI成功状态和API36报告已确认。
- `logs/2026-10-10-xiaomi13-deploy/`：部署前后包、签名/版本证据、截图和基础检查。
- 用户原始附件 `C:/Users/kuma/.codex/attachments/99101e1b-638b-47a0-b29a-86f1d017f208/已粘贴的文本.txt`，已复制到上述 LAN 日志目录。
- 用户两份原有未跟踪文档：`docs/product/2026-07-17-motocom-ui-redesign-prd.md`、`docs/product/2026-09-30-ui-interaction-inventory.md`，保持原状，未纳入本次提交。
- 本机 ADB 配对、SDK/JDK、分发私钥等机器配置；交接没有同步这些内容。

本文件已整理继续排查所需的已确认事实；若需重新核对原始手机日志或旧截图，另行从旧设备取得对应文件。手机原始日志、APK、私钥及无关用户文档不自动追加到 Git。

## 新设备可粘贴的接续指令

> 请读取 `g95809080-cmyk/moto-intercom` 仓库 `fix/kum-81-lan-network-failure` 分支的 `rasen/changes/kum-81-lan-network-failure/handoff/2026-10-11-device-handoff.md`，核对项目规则、Linear KUM-81 与 Draft PR #54 后接续 Android 排查。当前代码修复、固定SHA独立审查与CI已通过，真机验证暂缓；保持独立PR和1.2.x修复测试版本管理，跳过iOS，不把模拟器证据当作两机互通结果。
