## Why

KUM-69 / 问题5：单一候选channel失败会在已计划的备用传输启动前终止整个attempt；Socket交接锁包含阻塞HELLO，逐次read超时允许碎片延长交换；旧Wi-Fi关闭和迟到确认/请求回执可干扰新runtime，UI缺席计时反复延期。

## What Changes

- 候选失败保留仍有工作量的原attempt、目标与截止；选择cohort携带事件归属。
- HELLO采用绝对frame/whole/原attempt预算，pending socket覆盖连接、HELLO和主线程admission，短锁原子移交，关闭不等待IO。
- 进程级稳定runtime释放所有权防止旧Wi-Fi清理影响新实例；迟到确认与请求拒绝核验完整归属。
- 连接请求授权直到实际 Connected 成功提交前可撤销；清理先赢则不采用旧请求，采用先赢则精确取消其当前 child，排队的 glare/CONNECTED 不能越过撤销。
- 发现UI固定首次缺席的2秒截止，不被后续无关Presence刷新延长。

## Impact

基于KUM-68稳定维护线的独立修复，不改变wire协议、身份验证契约、发行版本或部署。自动重连开关及第三次重试后的恢复目标保留另由KUM-70处理。
