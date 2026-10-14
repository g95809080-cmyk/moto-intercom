# KUM-82 验证（2026-10-12）

Stable Base: `717fbecc23d60b1b35280f3e895898491bf533dc`。

Approved Local Source Head: `49bf4e50dbc1d4b5f7c75686cad032e10a25b9c6`。只读架构复审 `APPROVED`，P0=0、P1=0、findings=[]，19/19 paths reviewed、0 skipped。技术批准允许补齐本地记录；不代替真实 Linear 绑定、固定提交 CI 或部署批准。

## 已完成

- 权限语义红回归：实际 AudioRouteController 缺失权限被误报为没有耳机；修复后通过。
- 初审两项 P1 的独立红回归：实际 Activity→Service 重绑给同媒体补预算；真实 WebRTC RTC→Main 延迟 2.2 秒把旧设备类型重新认证为新鲜。两项在旧候选均失败，修改后通过。
- 所有音频行为均保留当前 lease、session、query revision、upstream owner 和 route revision 校验；测试覆盖输入内置麦克风/输出手机、新鲜度不足、等待接通、有限重试、显式手机、来电/focus、A→B 替换、上游撤销、过期/逆时同媒体样本及重绑/显式点击。
- 完整 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`：95 suites、759 tests，0 failure/error/skip；Lint 0 errors、70 原有 warnings；两个 APK 构建成功。
- 自有无界面 Android API 36 AVD：全套仪器回归 27 项中实际通过 25、assumption skip 2。包括新实际 AudioRecord/AudioTrack routedDevice 生产者、暂停证据撤销、恢复、新 PCM 不能刷新旧 RTC 样本及后续新采样恢复。
- 跳过项为 SharedNetworkNsdTest/SyntheticAudioNetworkTest 的双设备角色用例，需要实际 server/client 第二端；未把 runner 的 `OK (27 tests)` 误作 27 个实际通过。
- Rasen strict validation 与 Git diff whitespace check 通过。
- 主机 JDK 重新验证为缓存 Adoptium 21；此前 `F:/Android/jbr` 已不可用，使用既有 `M:` 主仓库映射构建，未修改 Gradle/JDK 版本配置。

## 固定提交审查

- 官方 Alibaba Open Code Review CLI 1.12.13 对精确 Base→Head 执行 `delegate preview/rule`；默认选中 10 条、排除 9 条。全部 19 条已补充人工模型审查，Kotlin、YAML 和默认规则均覆盖；未声称调用远程 OCR LLM 服务。
- 两项初审 P1 已经红→绿并经固定提交独立复审确认修复。scope/rules 原始 JSON 在忽略 logs；`coverage.json` 保存精确 blob 和实际阅读深度。
- 未变更的 ModernAudioRoute、通信协调器、AudioIoGate/evidence、R8 字段规则及 IntercomManager/readiness 消费链也已追读。
- 本地候选 Debug APK SHA256：`72109e525eacd28fc452410064674c928d7f268bdebae4217fc92b3d90f3c5c7`；AndroidTest APK：`6108f5a42281548809610aa7eec91529de6c80c992d4801e588d41d9953f4e67`。仅用于自有 AVD 验证，未经分发，现有版本文件未变更。

## 正式绑定与固定提交 CI

- 用户重新授权 Linear 后核查重复并建立 KUM-82，正式分支/change 名称与 issue 一致；[Draft PR #55](https://github.com/g95809080-cmyk/moto-intercom/pull/55) 已关联。
- 只读文档绑定复审 Base `717fbecc23d60b1b35280f3e895898491bf533dc` / Head `be443c4caa0df1ca43dd86b9b4945a9ab101aa39`：APPROVED，P0=0/P1=0，新目录8文件全文阅读；app/.github/version blob 与批准 source Head 完全相同。
- [Android CI 38209170750](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38209170750)，固定 Head `be443c4caa0df1ca43dd86b9b4945a9ab101aa39`：JVM/Lint/双 APK job `114679273795` 和 API36 instrumentation job `114679273660` 均 completed/success。源码与本地759 JVM、25 API36 pass/2 skip证据一致；远程artifact尚未另行计数，不把job结论当双实机验收。

## 仍待完成

- 小米 13 仅做当前设备只读核查，安装版本仍为 1.2.2+bb0fa92；本机无活动耳机/通话复现证据。候选 APK 仅安装到自有 AVD，未部署到用户手机，未改变版本配置、main 或发布状态。
- 两部普通蓝牙耳机首次连接、双方自动重连后的收听/说话、导航音乐共存和人工骑行听音需要双真实设备。单 AVD/JNI 元数据结果不替代这些验收。

构建、设备、红/绿回归的原始输出在忽略目录 `logs/2026-10-12-field-review/`；不提交用户原始日志或系统 dump。
