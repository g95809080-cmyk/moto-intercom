# 5｜发现与连接偶发失败排查

日期：2026-10-08。来源：项目独立只读排查会话 01a11922-3894-7182-af37-5d9be4ae1b8e。

优先排查 **v1.2 的连接时间预算和 P2P 清理时序**。代码还存在两条能造成“发现得到、连接不上”的路径：陈旧 LAN 地址被优先选择，以及延迟发现回调把当前会话误判为旧会话。**这些代码行为已确认，但缺少本次骑行的双端 v1.2 日志，尚不能锁定实际触发的根因。**

版本已核对：`v1.2.0` tag 指向 `d479ec1`，发布源码为 `408bfbe`，两者 `app` 内容完全一致；版本来自旧版 `app/build.gradle.kts`，为 **1.2.0 / code 3**。指定 main 为 `0f5e7b7`，实际版本名为 **1.3.0+aee3b3f / code 4**。[v1.2 发布来源与验证记录](/C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/docs/verification/2026-09-12-release.md:3)

v1.2 的调用链是：**P2P DNS-SD / LAN NSD、UDP 发现 → Presence → 锁定 deviceId、sessionId → TCP → HELLO 身份检查 → 连接请求与确认 → 通道选择 → WebRTC SDP/ICE → Connected**。端口分别是 LAN UDP 8889、LAN TCP 8890、P2P TCP 8888。旧版没有 BLE 房间发现或六位房间码认证。

以下源码链接固定到 v1.2 tag 提交，行号依据旧版。

| 路径与影响 | 代码证据确认 | 有待真机验证 |
|---|---|---|
| **连接预算不足、确认窗口不一致** | 总预算从点击起只有 **10 秒**；双通道模式 **T+5 才启动 P2P**，剩余时间须完成成组、HELLO 和媒体协商。未配对接收方却有 **15 秒**确认窗口，远端接受不会延长发起方期限。[总预算:25](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SessionOrchestrator.kt#L25)、[确认期限:968](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingControlCoordinator.kt#L968)、[接受后仍检查过期:1383](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingControlCoordinator.kt#L1383) | 是否在成组或人工确认阶段耗尽预算；已配对连接也需检查 P2P 成组耗时。 |
| **停止后立即重开，旧清理与新发现重叠** | Stop 先设置 `running=false`，P2P 清理随后异步执行；Start 没有等待旧资源释放。旧清理还可能执行 cancel/removeGroup。removeGroup 重试失败后也会继续完成关闭，没有验证 group 已消失。[Stop:1658](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomService.kt#L1658)、[失败仍完成:604](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt#L604) | 是否集中发生在结束、取消后马上重开；旧 removeGroup 是否与新 connect 重叠。 |
| **陈旧 NSD 地址压过新鲜 UDP 地址** | NSD 端点没有本地 TTL；UDP 端点约 3 秒过期。连接按端点名称字典序取最小项，正常 `MotoCom-…` 会排在 `udp:…` 前。连接失败不降级该端点，后续广播可能再次触发同一坏地址。[NSD 写入:233](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/LanDiscoveryCoordinator.kt#L233)、[选址:58](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/LanRiderDevice.kt#L58) | 是否存在 NSD 旧 IP 与 UDP 新 IP 并存，实际连接反复命中旧 IP。 |
| **发现回调顺序误判会话新旧** | 一个此前未见过的 sessionId 到达时，会直接替换当前 session，并把当前 session 永久记为 superseded。若顺序是“当前会话先到、缓存旧会话后到”，之后真正当前会话的广播会被忽略；候选 TTL 到期不会清除这个判定。[会话切换:111](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/PresenceAggregator.kt#L111)、[忽略 superseded:191](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/PresenceAggregator.kt#L191) | 对端重启后是否出现上述顺序，并继而出现 `HELLO source does not match TargetLock`、设备消失或旧选择被忽略。 |
| **P2P 成组信息尚未完整就被清理** | GO 的 clientList 为空时，匹配直接返回 REJECTED；只有 PENDING 才等待重试，REJECTED 会马上清理 group。[成员匹配:53](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/WifiDirectPeerRegistry.kt#L53)、[重试与清理:1217](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt#L1217) | OEM 是否先回调 `groupFormed=true`，再补齐 clientList，导致刚建立的组被立即拆掉。 |

另有一个 LAN 生命周期缺口：启动时没有 Wi-Fi IP 就直接退出，此对象没有监听后来入网并自动启动；TCP 服务端也是异步启动，NSD 发布没有等待监听成功。因此还需检查“启动后才连 Wi-Fi”和 TCP bind 失败两种情况。[LAN 启动:59](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/LanDiscoveryCoordinator.kt#L59)

取消和并发连接方面，v1.2 已有单一状态写入者、attempt/generation/TargetLock 检查、确定性的 glare 仲裁，以及第三方 BUSY 拒绝。现有测试覆盖取消后迟到回调、同目标同时请求和替换连接保护；本次没有证据证明这些保护普遍失效。未配对对端在后台且通知不可用时，则会按策略返回 `CONFIRMATION_UNAVAILABLE`，需要和无线故障区分。[接收策略:865](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/SignalingControlCoordinator.kt#L865)

保存的日志提供了相似路径，但**不能作为 v1.2 骑行复现**：

- 7 月 8 日记录了 P2P connect 后 12 秒超时，随后 removeGroup 返回“系统 Wi-Fi P2P 忙”。[日志:82](/C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/logs/efcb9031_problem_b_start_filtered.log:82)
- 6 月 27 日记录了组长 TCP 8888 连接失败；同批 package 记录为 **1.0 / code 1**。[连接失败:17](/C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/logs/20260627_114015/9688fa60_connection_filtered.txt:17)、[版本:21](/C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/logs/20260627_114547/9688fa60_package.txt:21)

与指定 main 比较，Presence、LAN、v2 信令、总预算和旧版关闭序列的文件内容均未改变。main 增加了发现前的权限/定位检查，以及跨 Service 的网络释放占用保护；这能缓解快速重开竞争，但旧关闭序列仍没有确认 group 消失。[发现前检查:198](/C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt:198)、[启动保护:417](/C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/app/src/main/java/com/kuma/motointercom/IntercomService.kt:417)。main 新增的 BLE 房间搜索和认证属于独立群组链路，不能解释旧版故障。[群组搜索:94](/C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp/app/src/main/java/com/kuma/motointercom/group/GroupBootstrapRuntime.kt:94)

最小修复建议是以 v1.2 为底，分别处理：

1. **释放门禁**：新发现等待旧清理结束，并验证 group 已不存在；未知释放状态继续受控重试。
2. **LAN 选址**：按新鲜度选择端点，失败后降级并尝试其他匹配端点；网络变化时重建发现。
3. **会话判新**：未知 session 声明先作为候选，经过当前 Socket 验证再淘汰旧会话；发现回调绑定发现轮次。
4. **时间预算**：统一两端确认期限。若要真正提供 15 秒人工确认，需调整现有 T+10 契约；单独增大局部 watchdog 没有效果。
5. **成组验证**：把暂缺成员信息作为有界等待状态，同时保留明确非目标成员的拒绝。

后续真机验证建议覆盖以下场景，每次同时保存两端结果：

| 场景 | 操作与判定 |
|---|---|
| 配对与超时 | 已配对/未配对分别测试 LAN、纯 P2P、LAN 不通但 P2P 可用；记录首次确认在第 8、12 秒是否仍可操作，以及超时阶段。 |
| 快速重开 | Stop 后间隔 0、1、3、10 秒再启动；检查旧清理完成前是否出现新发现或 connect。 |
| 地址与会话变化 | 一端保持运行，另一端换网或重启；对齐 NSD/UDP/P2P 的 IP、sessionId 与 HELLO 实际身份。 |
| 取消与同时连接 | 双端同时点击、连接中取消后重试；确认只有一个媒体通道，旧回调不能接管新 attempt。 |
| 后台与成组 | 前台/锁屏分别测试，记录确认面是否可用，以及 `groupFormed`、clientList、removeGroup 的先后顺序。 |

应采集故障前约 60 秒、后约 120 秒的双端日志：APK/versionCode、手机型号/API，runtimeSessionId、attemptId、channelId、TargetLock，候选来源/IP/端口/新鲜度，剩余预算，HELLO/确认/SDP/ICE 阶段，完整异常及 cause，P2P 操作返回码、generation、关闭步骤和最终 group 状态；并配套 `dumpsys package/wifip2p/connectivity`。目前错误转发只保留异常 message，缺少这些关联信息。[错误出口:2387](https://github.com/g95809080-cmyk/moto-intercom/blob/d479ec1dec5c4c197785d69b88d0872a366946cb/app/src/main/java/com/kuma/motointercom/IntercomService.kt#L2387)

尚缺本次失败的错误原文、双端版本身份、网络状态、是否已配对及是否紧接停止重开。现有发布验证也明确不代表双真机射频或骑行实测。本次仅作只读定位，没有修改文件、分支、应用数据或外部事项，也没有运行构建或安装应用。

