## MODIFIED Requirements

### Requirement: 音频焦点等待和释放
音频控制 SHALL 在 transient loss 保留系统焦点等待，在 delayed grant 等待真实 GAIN，在 permanent loss 释放路由且不处理迟到 GAIN；媒体结束 SHALL 释放本应用通信模式，电话优先不覆盖系统电话模式。

#### Scenario: 导航抢占后归还临时焦点
- **WHEN** transient loss 后系统发送 GAIN
- **THEN** 原请求 MUST 保持在焦点栈，只有路由验证后才能恢复音频

#### Scenario: 其他应用永久持有焦点
- **WHEN** permanent LOSS 到达
- **THEN** 对讲 MUST 挂起且释放通信模式，不无限回抢或被迟到 GAIN 恢复
