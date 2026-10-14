## ADDED Requirements

### Requirement: 枚举失败不能绕过导出预算
每次读取导出目录 SHALL 成功取得文件列表后才能继续；失败 MUST 不创建新导出且 MUST 保留未过期分享文件。

#### Scenario: 首次目录枚举失败
- **WHEN** 初次读取返回 null
- **THEN** SHALL 完成失败回调，MUST 不执行后续TTL清理或写入

#### Scenario: TTL清理后的第二次枚举失败
- **WHEN** 过期文件已按既定规则清理但计费枚举返回 null
- **THEN** SHALL 完成失败回调，MUST 不把占用计为0或创建新导出

#### Scenario: 目录故障恢复
- **WHEN** 同一 worker 后续成功枚举
- **THEN** SHALL 继续把保留的已有导出纳入预算，原TTL到期后 SHALL 正常恢复导出
