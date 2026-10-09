# 群组控制通道所有权修复

Linear KUM-77。基线为 KUM-76 文档提交 `170e7ff`，其源码 `5b9b6a7` 已通过 Android CI。独立分支 `fix/kum-77-group-control-ownership`，Draft PR 以 KUM-76 分支为 base，仅审查本 issue 的增量。

关闭后的认证回调目前仍可安装 channel/ingress 并占据 writer 的 clientChannel；Client 在构造 connection 与字段发布之间被 close 时会遗留 channel/key。当前 pending control 连接在 Main 认证前意外关闭还可能永久 JOINING。closeControl 的 CHM values.toList 与真实关闭 callback 并发时可抛异常。

修复实际 channel、adapter、network/control attempt 和 ingress 的归属检查，保持唯一 GroupSessionOrchestrator writer。用实际 JPAKE/AEAD loopback、PAUSED Main、真实 worker 和 latch 验证旧源红与修复绿；完整 Android 验证、固定 SHA 只读架构/OCR 复审及 CI 后提交独立 Draft PR。iOS、协议和版本不变；用户暂缓实机听音验证。
