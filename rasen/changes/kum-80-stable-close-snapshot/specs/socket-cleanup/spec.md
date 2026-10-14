## ADDED Requirements

### Requirement: 并发关闭必须完成自身清理
LAN/Wi-Fi关闭 SHALL 在实际producer删除最后pending lease时完成自有server、executor和Socket清理，不因集合瞬时size抛异常中断。

#### Scenario: 读取最后一项size后worker删除
- **WHEN** 实际HELLO或ready worker关闭自己的lease并从registry删除最后一项
- **THEN** adapter close MUST 完成快照及剩余资源关闭，重复关闭保持幂等，不清理替代runtime资源
