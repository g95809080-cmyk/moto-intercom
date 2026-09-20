## Why
现有域、媒体、认证与网络适配各自通过自动化，但尚无真实入队和房间控制链路。需要将它们接为可测试的认证控制运行时，供最后的Service/UI接入。
## What Changes
- BLE请求响应中的元数据、完整PAKE与加密网络描述桥接，匹配候选集合。
- 独立TCP PAKE与有界加密控制通道，认证后才生成短期准入证明。
- 主机权威名单、信令路由、群组单写者及网络/媒体effects。
## Capabilities
### New Capabilities
- `group-session`: 认证后的群组会话编排。
### Modified Capabilities
无产品需求变更。
## Impact
新增group控制/桥接文件与群组状态视图，复用现有域、媒体、认证和网络；Service/UI入口留到下一独立issue。不变更旧双人V2线上协议。
