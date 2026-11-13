## ADDED Requirements

### Requirement: 故障脚本只能报告已成功完成的命令
每条network speed/delay SHALL 核对native退出码；失败 MUST 立即终止后续命令且 MUST 不输出APPLIED。Mode SHALL 明确指定。

#### Scenario: normal/slow/online中的任意命令失败
- **WHEN** 模拟ADB在该位置返回非零退出码
- **THEN** SHALL 返回失败，仅执行截至失败的命令前缀，MUST 不输出APPLIED

#### Scenario: 无模式或非模拟器设备
- **WHEN** Mode缺失或serial不符合emulator规则
- **THEN** MUST 在ADB调用前拒绝

#### Scenario: scenario恢复清理
- **WHEN** 原scenario执行finally恢复
- **THEN** SHALL 保留清理动作，恢复失败 SHALL 阻止后续ping验收及PASS
