## ADDED Requirements

### Requirement: 协议终态不可由任意第二帧冒充
第二帧 SHALL 先通过既有 envelope 与身份校验，且只能是 CONNECT_ACCEPT、CONNECT_REJECT 或 BUSY；未设置 --expect 也不得把 HELLO、DISCONNECT 或未知类型记为 received 成功。

#### Scenario: 重复 HELLO
- **WHEN** 实际 TCP 响应 HELLO 后又响应 HELLO
- **THEN** 工具非零退出且第二帧不产生成功 received

### Requirement: 物理证据要求两台不同安装及完整属性
采集 SHALL 检查所有 native exit，精确解码 device_id，并拒绝相同serial/identity、模拟器、未知qemu、缺失属性或身份；在采集前后都验证 A/B 原身份与APK。

#### Scenario: 昵称含 UUID
- **WHEN** nickname 的 UUID 位于实际 device_id 前
- **THEN** 仅实际 device_id 用于身份绑定

#### Scenario: 同一安装经两个 serial
- **WHEN** A/B 实际 device_id 相同
- **THEN** 不生成双物理设备 manifest 或当前Pass

### Requirement: 当前采集失败不得留下旧判定
Start SHALL 禁止覆盖已有证据。每次 Stop SHALL 在外部命令前使当前结果失效，独立保留各次快照，全部完成后才原子发布当前结果；失败非零并发布NotRun/capture_failed。

#### Scenario: 成功后再次Stop中途失败
- **WHEN** 原快照成功后新Stop任一检查失败
- **THEN** 当前结果为NotRun，原成功快照所有文件hash不变，恢复后新快照使用不同目录
