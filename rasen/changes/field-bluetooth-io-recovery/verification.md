# 本地候选验证（2026-10-12）

Stable Base: `717fbecc23d60b1b35280f3e895898491bf533dc`。

## 已完成

- 权限语义红回归：实际 AudioRouteController 缺失权限被误报为没有耳机；修复后通过。
- 初审两项 P1 的独立红回归：实际 Activity→Service 重绑给同媒体补预算；真实 WebRTC RTC→Main 延迟 2.2 秒把旧设备类型重新认证为新鲜。两项在旧候选均失败，修改后通过。
- 所有音频行为均保留当前 lease、session、query revision、upstream owner 和 route revision 校验；测试覆盖输入内置麦克风/输出手机、新鲜度不足、等待接通、有限重试、显式手机、来电/focus、A→B 替换、上游撤销、过期/逆时同媒体样本及重绑/显式点击。
- 完整 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`：95 suites、759 tests，0 failure/error/skip；Lint 0 errors、70 原有 warnings；两个 APK 构建成功。
- 自有无界面 Android API 36 AVD：全套仪器回归 27 项中实际通过 25、assumption skip 2。包括新实际 AudioRecord/AudioTrack routedDevice 生产者、暂停证据撤销、恢复、新 PCM 不能刷新旧 RTC 样本及后续新采样恢复。
- 跳过项为 SharedNetworkNsdTest/SyntheticAudioNetworkTest 的双设备角色用例，需要实际 server/client 第二端；未把 runner 的 `OK (27 tests)` 误作 27 个实际通过。
- Rasen strict validation 与 Git diff whitespace check 通过。
- 主机 JDK 重新验证为缓存 Adoptium 21；此前 `F:/Android/jbr` 已不可用，使用既有 `M:` 主仓库映射构建，未修改 Gradle/JDK 版本配置。

## 仍待完成

- 固定源码 SHA 的只读架构 gate 与官方 Open Code Review 范围/规则及独立审查收口；初审 P0=0/P1=2，已修复并重跑，不能沿用初审为最终批准。
- Linear 已过期授权，真实 issue 绑定、正式分支/change 命名、Draft PR 和固定 SHA Android CI 尚未执行；不伪造编号或已通过的 CI。
- 小米 13 仅做当前设备只读核查，安装版本仍为 1.2.2+bb0fa92；本机无活动耳机/通话复现证据。候选 APK 仅安装到自有 AVD，未部署到用户手机，未改变版本配置、main 或发布状态。
- 两部普通蓝牙耳机首次连接、双方自动重连后的收听/说话、导航音乐共存和人工骑行听音需要双真实设备。单 AVD/JNI 元数据结果不替代这些验收。

构建、设备、红/绿回归的原始输出在忽略目录 `logs/2026-10-12-field-review/`；不提交用户原始日志或系统 dump。
