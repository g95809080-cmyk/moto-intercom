## Ownership

SessionOrchestrator 继续独占连接状态；Service 只执行和投递效果，LAN 适配器只报告它执行的 ConnectionAttempt。使用已有 TargetedTransportOpenFailed，不能通过诊断推断或合成已验证车友身份。

## Network selection

从 ConnectivityManager 可见网络中，选择 TRANSPORT_WIFI、无 TRANSPORT_VPN 且 LinkProperties IPv4 与 WifiManager 的当前局域网地址相符的 Network。在未连接 socket 上调用 Network.bindSocket，不绑定整个进程，也不要求 Wi-Fi 获得互联网验证。不存在匹配网络时明确失败，不悄悄走移动网络。

## Failure and resource ordering

失败保留原 attempt/runtime/transport；释放当前 worker 标记前先阻止该尝试被发现广播再次提交，清理真实 pending lease/socket 后投递失败。关闭或替换后的后台完成不得修改新尝试；Service 主线程最终检查 session、runtime 和 attempt，actor 再执行既有身份检查。单 LAN 尝试失败后回到发现；双通道计划继续按既有 actor 规则处理可用分支。

## Diagnostics and limits

记录 socket 绑定的网络、接口、本地地址、目标、阶段和实际 listener 绑定完成。EHOSTUNREACH 发生在 TCP 阶段，不据此断言 AP 隔离或 P2P 清理失败。真实网络绑定及两机互通需设备复测；JVM shadow 只验证应用调用和事件顺序。

## Verification

先在未经行为修复的基线运行可编译的实际 Service/worker 回归并记录红结果，再完成修复和对应绿结果。覆盖 Android 9 对应 SDK 28 与现代 SDK 35 的平台接口。完成稳定线全 JVM、Lint、Debug/AndroidTest APK，固定源码 SHA 的只读架构审查与官方 Open Code Review，独立 Draft PR 和固定 SHA Android CI。任何审查 P0/P1 均需修复后才能推进。
