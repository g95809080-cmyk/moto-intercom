## Why

用户的 1.2.2+bb0fa92 小米 6 实测日志显示：两台同版本、同路由器 Wi-Fi、均已启动，UDP/NSD 能发现 192.168.1.160，但从 192.168.1.108 连接 TCP 8890 两次返回 EHOSTUNREACH。尚未进入 HELLO、身份确认或语音阶段。

已确认生产缺口是 LAN 异步 connect 失败只通知通用错误监听，没有把原 runtime/attempt 的传输失败交给 actor；同一尝试会被发现广播再次触发并等待总截止时间。LAN socket 依赖系统默认网络，也是可以明确消除的选路不确定性。

## What Changes

- LAN 客户端在 connect 前绑定与本地 Wi-Fi IP 相符的非 VPN Wi-Fi Network；只绑定该 socket。
- 异步提交、选路、TCP 和 HELLO 失败使用既有 TargetedTransportOpenFailed，保留准确尝试身份，并在适配器和 Service 最终投递时检查当前归属。
- 记录实际 TCP 监听就绪、Wi-Fi 网络、目标及失败阶段，便于下次两端日志判断。
- 通过实际后台 socket worker 和 Service 回归验证失败、迟到、关闭、移动默认网络与 Wi-Fi 并存、无匹配网络。

## Impact

基线 test/kum-79-v1.2.2-xiaomi13@1ac1e3e，已部署源码 bb0fa92。只改稳定线 LAN 选路及失败生产者，不改协议、目标锁、截止时间、身份校验或已有单/双通道计划，不引入群组或 iOS，不变更版本或部署。

日志中的源地址已经是 Wi-Fi IP，因此不能把默认网络选错当作已证实根因；路由器互通、系统限制和对端监听仍待两端证据。用户当前不方便连接手机，实机互通验证留待后续，不要求现在提供 ADB。
