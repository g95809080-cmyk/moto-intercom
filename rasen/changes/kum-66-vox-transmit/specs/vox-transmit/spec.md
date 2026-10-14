## ADDED Requirements

### Requirement: 发送门控与可重新检测的采集
VOX SHALL 使用实际发送静音，保持 PCM 检测可重新开麦，手动静音 SHALL 优先；旧设置 revision 不得开麦。

#### Scenario: 背景音后再次说话
- **WHEN** VOX 闭麦后输入语音越过门限
- **THEN** PCM MUST 继续检测且发送门控按 attack 打开

### Requirement: 灵敏度在噪声下可区分
灵敏度 SHALL 改变已学噪声之上的开门限，连续调节 SHALL 保留噪声底与校准截止。

#### Scenario: 骑行背景下拖动滑块
- **WHEN** 已完成噪声学习后调高灵敏度
- **THEN** 实际门限 MUST 降低，不重新开始500ms校准；只有背景残留时仍可闭麦
