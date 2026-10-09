## Why

KUM-67 / 骑行问题3：硬件NS声明支持并不能证明当前输入上的effect创建/启用成功；原native会在硬件NS受理时关闭软件NS。原生录音/播放错误也未传给应用，用户只能感到无声或无降噪。

## What Changes

- 固定软件NS路径与正确约束，保留AEC、AGC、Opus；移除无效NS键。
- 接入实际ADM启停/PCM与错误producer；错误清零、只报告一次，关闭后迟到错误不能触发产品状态。
- 记录本engine实际AudioRecord的输入source/deviceType/格式/session与状态。不会枚举其他应用或保存原始音频。

## Impact

独立稳定修复，基于KUM-66。不更改发行版本或部署。普通蓝牙与降噪耳机都走相同路径；风噪、远端听感需实机验证。
