# iOS 会话归属

## ADDED Requirements

### Requirement: 终结与旧回调
EOF、收发失败、拒绝、busy、DISCONNECT、期限及用户stop MUST仅终结实际owner一次；迟到事件不得创建或接管新媒体。

#### Scenario: A停止后B连接
- WHEN A的TCP、SDP、权限、bootstrap或pairing完成晚于B启动
- THEN B不被修改，A资源被清理，不能把A的目标绑定到B。

### Requirement: 有限验证与Connected事实
TCP/HELLO及确认、媒体MUST有期限；fallback和glare不得延长原attempt。实际HELLO、合法接受、当前媒体远端首帧及路由全部成立后才能Connected。重复音频/路由通知不能降级Connected。

#### Scenario: 旧SDK producer事件
- WHEN A的native PC事件在B重用engine后到达
- THEN 原producer持有A的callback快照，不能调用B回调或修改B候选队列。

### Requirement: 验证边界
自动回归MUST运行真实controller/driver ingress；native adapter MUST在真实WebRTC模块下编译，excluded source不能计为native通过。实机BLE和解码sink验证独立记录。
