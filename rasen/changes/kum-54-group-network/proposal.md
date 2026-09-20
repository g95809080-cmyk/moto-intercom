## Why
认证和媒体已具备独立实现，需要把无 IP 发现与房主自建 Wi-Fi 网络接到实际 Android API，才能继续实现四人离线入队。

## What Changes
- BLE 服务发现及有界请求/响应传输，不公开六位码或 verifier。
- 房主 Wi-Fi Direct GO、成员 Wi-Fi 客户端及网络 socket 绑定。
- 可测试的分片、限速、超时和资源代际策略。

## Capabilities
### New Capabilities
- `group-network`: 群组离线网络适配。
### Modified Capabilities
无。

## Impact
新增 group/network 文件及必要权限；不改双人适配器、不启动新产品模式。完整认证桥接、单写者与 UI 在后续 issue 接入。
