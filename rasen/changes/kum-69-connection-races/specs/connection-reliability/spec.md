## ADDED Requirements

### Requirement: 候选失败保留有效备用工作
候选失败 SHALL 不在仍有候选或计划传输时提前终止attempt；原目标、截止和fallback时间 MUST 保持。

#### Scenario: 首选channel在备用启动前关闭
- **WHEN** 首选channel失败但备用传输仍在计划中
- **THEN** attempt MUST 等待原fallback和截止，旧cohort选择结果 MUST 无效

### Requirement: Socket及HELLO有界归属
Socket SHALL 在连接、HELLO及主线程admission期间可被原owner关闭；frame和整体HELLO MUST 有绝对截止。

#### Scenario: 碎片持续到达或主线程登记被延迟
- **WHEN** 远端碎片延长read，或stop/截止先于admission登记
- **THEN** 精确pending socket MUST 被及时关闭，旧结果 MUST 不进入新session或阻塞关闭

### Requirement: 旧清理和UI结果不接管新runtime
异步Wi-Fi清理、确认提示和连接回执 SHALL 校验进程runtime和完整请求归属。

#### Scenario: 旧runtime清理未完成或目标缺席不断刷新
- **WHEN** 旧清理仍在执行，或同一缺席目标反复收到其他Presence更新
- **THEN** 新runtime MUST 等待释放，缺席选择 MUST 按首次2秒截止清除

### Requirement: 请求撤销约束实际采用与成功提交
Service 请求授权 SHALL 在实际 Connected 成功提交前可撤销；采用、同目标 child 转移、SUCCESS 提交和撤销 MUST 使用同一原子归属切点。

#### Scenario: 旧请求工厂或排队的 glare/CONNECTED 晚于撤销
- **WHEN** 清理先撤销授权，旧请求随后返回 fresh ID 或处理已排队回调
- **THEN** 旧请求 MUST 不采用 child、不提交 SUCCESS、不保存配对；精确取消 MUST 不伤及新请求

#### Scenario: 已采用的请求先于撤销且物理关闭先完成
- **WHEN** actor 已采用 attempt，物理关闭早于精确取消完成
- **THEN** 取消 MUST 指向其实际当前 child，恢复发现 MUST 复用原清理参数，迟到 effects MUST 不重新开通道

#### Scenario: Connected 成功先提交
- **WHEN** 同一授权下的成功提交先于撤销
- **THEN** 请求授权 SHALL 完成，已建立通话 MUST 保持既有 active-session 生命周期

#### Scenario: glare 后原请求的真实备用 Socket 迟到
- **WHEN** 原请求的备用 Socket 已完成 HELLO，当前请求转移到保持原目标和截止的 child
- **THEN** 只有同一授权血缘且匹配 child wire、RESPONDER、peer 和 channel plan 的 Socket SHALL 可以移交；无关 Socket MUST 只关闭自身，不改变当前 owner

#### Scenario: 撤权先于排队的真实 EOF 或发送失败
- **WHEN** 真实 reader EOF 或 writer 失败已排队，而 Service 先撤销原授权并发出精确取消
- **THEN** 普通失败事件 MUST 不修改当前 graph，精确取消 SHALL 记录 CANCELED，已经开始的资源清理 MUST 不重复提交 Abort

#### Scenario: 普通终态先赢但 Abort 迟到
- **WHEN** 普通失败终态先提交，而其实际 Abort effect 晚于另一项资源清理到达
- **THEN** 已有 cleanup request 的对象身份、延迟、reset 和 refresh generation MUST 保留，只重新核验其完成条件
