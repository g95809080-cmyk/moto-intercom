## Why
实机反馈暴露双人UI就绪事件未接线，以及群组查找失败/取消后终态丢失。BLE固定20字节分片使3072位PAKE在真实ATT往返中容易耗尽20秒。
## What Changes
- 接通当前双人媒体的真实RTP/I/O/路由证据，驱动可重放UI状态。
- 终态在资源释放前送达观察者；查找整体限时并显示进度。
- 协商ATT MTU，按连接可用载荷分片，保留默认MTU路径、大小上限和认证期限。
## Capabilities
### New Capabilities
- field-intercom-recovery: 上述现有契约的实机回归覆盖。
### Modified Capabilities
无PRD或身份/认证契约变更。
## Impact
Service/Activity、AudioSessionController/IntercomManager、群组writer、BLE及候选搜索；不改配对数据或云端依赖。
