## Why

KUM-49 将已确认四人离线 PRD 的队伍准入/恢复规则固化为确定性模型，避免网络和音频接入时混淆名单、成员资格与连接代次。

## What Changes

- 新增纯 Kotlin 房主域模型与本机参与意图，包含4人容量、60秒占位、移除限制、逐成员身份和逐对就绪。
- 新增确定性回归测试，不接入运行中的 Service、网络、UI 或媒体。
- 保留基线、设计审查和实现验证证据。

## Capabilities

### New Capabilities
- `local-group-domain`: 临时队伍的受验证准入、逐成员恢复、权限、意图撤销与逐对就绪。

### Modified Capabilities
无。旧双人路径不变。

## Impact

仅新增 app/src/main/java/com/kuma/motointercom/group 下纯模型及对应单测。无新依赖、协议、权限、数据库或发布。

Linear: https://linear.app/kuma999/issue/KUM-49
Branch: feat/kum-49-local-group-domain
Depends on: KUM-18、KUM-19、KUM-37（均已完成）；本次有限范围设计审查 APPROVED。
PRD SHA: 0f808ad44f70976624f69d242bfb0a72aa3f77f2
