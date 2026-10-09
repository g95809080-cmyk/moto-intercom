## ADDED Requirements

### Requirement: 有界自动蓝牙选路
蓝牙请求 SHALL 在同一revision的固定预算内核验并重试，预算用尽才回退；停止、新选择和中断 SHALL 撤销旧事件和计时器。

#### Scenario: 首次受理但尚未激活
- **WHEN** 系统受理蓝牙请求但当前通信设备仍是手机
- **THEN** 应用 MUST 在固定预算内重新请求并核验当前实际设备，不能发布虚假的蓝牙就绪

#### Scenario: 初始旧SCO断开广播
- **WHEN** SCO正在建立且收到初始sticky DISCONNECTED
- **THEN** 当前请求 MUST 保持到CONNECTED、ERROR或固定截止，旧revision广播 MUST 无效

### Requirement: 音频就绪使用当前真实证据
就绪 SHALL 同时要求当前路由、I/O许可、native录放音、fresh PCM和双向audio RTP证据；PC.CONNECTED不能单独满足。

#### Scenario: 迟到stats或中断
- **WHEN** 采样期间路由/许可/native revision改变，媒体关闭或手机来电
- **THEN** 旧结果 MUST 失效，UI就绪 MUST 清除，旧grant或route事件 MUST 不恢复音频
