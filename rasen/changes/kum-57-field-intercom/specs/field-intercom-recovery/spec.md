## ADDED Requirements
### Requirement: 实机等待与取消可结束
系统SHALL在认证失败或用户取消时展示最终状态并释放当前资源；迟到回调不得恢复旧操作。
#### Scenario: 同步释放
- WHEN 尚未建立媒体的搜索失败且资源同步释放
- THEN 观察者在释放前收到IDLE与失败原因，允许重新尝试。
### Requirement: 双人就绪来自实际证据
系统SHALL将当前媒体与路由的有效证据送达UI，并在重新绑定时回放。
#### Scenario: 音频已建立
- WHEN 同一媒体的RTP双向增长且I/O与实际路由已验证
- THEN UI显示就绪；PC connected单独不足以通过。
### Requirement: 有界BLE认证
系统SHALL按协商ATT载荷传送有界认证包，保持身份认证与期限。
#### Scenario: 大MTU
- WHEN 连接支持247或更大MTU
- THEN 单分片最多244字节、重组不超过8192字节，认证消息保持原样。
