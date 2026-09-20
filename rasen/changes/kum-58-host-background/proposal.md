## Why
房主切后台后成员掉线，已消失的 Wi-Fi Direct 网络又被不断 removeGroup，阻止恢复和结束后重建。现场 Service 仍为前台服务，不能将故障归因于进程被杀。
## What Changes
- 对已消失的 GO 使用当前通道的群组和连接状态确认释放，保留 UNKNOWN 所有权约束。
- 复现并修复后台触发链，网络恢复仍绑定原 room/host 身份。
- 增加故障排序回归与小米13实机记录。
## Capabilities
### New Capabilities
- host-background-recovery: 后台网络检查、确认释放与恢复回归。
### Modified Capabilities
无 PRD 或认证契约变更。
## Impact
GroupWifiHost、GroupNetworkRuntime、对应自动化测试和验证记录。
