## ADDED Requirements

### Requirement: 全部当前owner断线路径遵守自动重连开关
当前owner异常断线 SHALL 统一判定自动重连开关；旧owner MUST 不创建恢复attempt。

#### Scenario: 开关关闭后当前Socket发生EOF或发送失败
- **WHEN** 自动重连关闭且真实当前控制Socket断开
- **THEN** 产品 SHALL 返回Discovering并清理当前资源，不创建恢复ID或deadline

### Requirement: 有界轮次后保留原车友追连目标
三次失败 SHALL 使用原Reset；实际完成后 SHALL 仅依据30秒固定冷却之后的新发现观测重开原deviceId的一轮。

#### Scenario: 原车友网络恢复但进程未重启
- **WHEN** 当前adapter在冷却后收到原deviceId的真实发现报文，remote runtime与上轮相同或不同
- **THEN** SHALL 消费一次观测、采用新ID/TargetLock/10秒截止/0失败计数，并等待当前Socket身份验证

#### Scenario: cached快照、其他车友或迟到原观测
- **WHEN** 只发生缓存列表/Room/expiry刷新、其他设备更新，或门槛前收到的报文迟到交付
- **THEN** MUST 不开启新轮次、不延长固定冷却、不切换追连目标

#### Scenario: 冷却后的实际探测
- **WHEN** 精确 reset 完成后的固定门槛已到，原意图和授权仍有效
- **THEN** SHALL 每30秒调用实际 NSD/Wi-Fi Direct 发现；没有新观测时 MUST 不创建连接 ID，停止/关闭/新 runtime 后旧探测 MUST 失效

#### Scenario: 原车友明确 USER 入站
- **WHEN** 原 verified deviceId 的已配对车友发起有效 USER 请求
- **THEN** SHALL 允许正常入站采用并清除等待目标；该请求超时或未实际采用时 MUST 保留追连目标

### Requirement: 未来恢复授权与运行轮次分离
Pending轮次采用 SHALL 与命令撤权原子互斥；采用后 SHALL 脱离一次性grant。

#### Scenario: factory阻塞期间关闭、忘记、停止或明确新选择
- **WHEN** 命令先撤权、旧factory随后返回新ID
- **THEN** 旧授权 MUST 不采用轮次，旧A取消 MUST 不清除新B

#### Scenario: 已在Recovering时关闭开关
- **WHEN** 已运行的轮次收到关闭设置
- **THEN** SHALL 保持原有当前轮次，不再创建下一轮未来意图，重试factory MUST 不在admission锁内

#### Scenario: 断线回调已排队后关闭再开启
- **WHEN** 真实断线 producer 已捕获授权，随后 off→on，旧断线才交付 actor
- **THEN** 旧授权 MUST 不被重新授予；on 后发生的新断线 SHALL 可取得新授权

### Requirement: Wi-Fi新attempt以真实Socket验证就绪
新attempt SHALL 使用其实际origin、Socket和HELLO；旧SIGNALING_READY MUST 不充当新通道就绪。

#### Scenario: 旧连接结束后复用发现adapter
- **WHEN** 新attempt接管已处于SIGNALING_READY的adapter
- **THEN** SHALL 撤销旧Socket归属并执行当前attempt的实际Socket/HELLO，旧ready/回调 MUST 被拒绝
