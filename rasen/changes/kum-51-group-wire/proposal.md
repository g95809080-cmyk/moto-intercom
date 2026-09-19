## Why

KUM-51 承接 KUM-49 和 main 的 KUM-50 修复。多人控制消息需要独立于双人 V2，并拒绝跨房间、旧资格和伪造成员的控制。

## What Changes

- 新增准入后群组帧的严格有界编解码。
- 新增纯函数式接收授权与通道序列状态；不启动网络、认证或媒体。

## Capabilities

### New Capabilities
- `group-wire`: 群组控制帧和当前通道授权。

### Modified Capabilities
无。

## Impact

只新增 group 包及单测。旧 V2 不变，无新依赖。认证握手、发现、媒体及 UI 后续独立实施；本单元不证明完整四人通话。
