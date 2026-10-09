## ADDED Requirements

### Requirement: 固定可追踪的降噪路径
应用 SHALL 对所有输入使用明确的软件NS请求，不因仅声明支持的硬件NS取消软件路径。日志 SHALL 区分配置请求、实际录音状态与未知状态。

#### Scenario: 普通蓝牙通话输入
- **WHEN** 创建采集模块
- **THEN** 硬件NS替代 MUST 禁用，正确软件NS约束 MUST 启用；实际录音/PCM输入以native producer诊断

### Requirement: 原生录放音错误隔离
原生录音/播放错误 SHALL 通过当前engine/runtime报告一次并清零后续发送；关闭后的callback SHALL 失效。

明确请求挂起/关闭期间的停止错误 SHALL 不触发新的runtime失败。真实platform失败 SHALL 停止该runtime并显示错误，而不是复用已失效engine维持Connected。

#### Scenario: 当前输入读取失败
- **WHEN** native报告读取错误及重复错误
- **THEN** 应用 MUST 收到一次失败，后续帧 MUST 清零，旧关闭engine错误 MUST 不影响后继runtime
