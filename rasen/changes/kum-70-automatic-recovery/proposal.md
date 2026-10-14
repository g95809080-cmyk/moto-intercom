## Why

KUM-70 / 问题6：真实控制通道 EOF 和发送失败绕过自动重连开关；三次失败后的 Reset 丢失原车友目标。旧 Wi-Fi SIGNALING_READY 不能作为新 attempt 的真实 Socket/HELLO 就绪；缓存 Presence 更新时间也不能证明新发现。

## What Changes

- 统一当前 owner 的异常断线开关判定，拒绝旧 owner 回调。
- 保留原已验证 deviceId 的未来恢复意图；三次失败仍 Reset，实际完成后等待固定30秒冷却和当前 adapter 的真实发现报文，开启新三次轮次。
- 同一或新的 remote runtime 均可作为新目标声明，须由当前 Socket HELLO 验证；每轮采用新 ID、不可变 TargetLock、10秒截止和0失败计数。
- Pending episode admission 可在命令入口同步撤销；设置关闭只取消未来意图，已经运行的 Recovering 轮次按原合同完成。
- 主动断开、停止、忘记原车友和明确的新选择取消追连；普通 preferred snapshot 与其他车友不能抢走等待目标。

## Impact

基于KUM-69维护线的独立修复，保留三次失败、原重试退避、全局截止和身份验证合同。不改变版本身份，不带入群组，不自动合并、发布或安装；实机长失联验收仍独立记录。
