# KUM-84 VOX 高噪声门控与 NS/AGC 实测

[Linear KUM-84](https://linear.app/kuma999/issue/KUM-84)，branch `fix/kum-84-vox-road-noise`，Base `9819833c81b13474609af104c8a3959f5480109d`。继承 KUM-82 蓝牙录放与 KUM-83 有界恢复；后者Source `ff19c4f`、文档绑定`f259ea31`架构APPROVED，固定Source Android CI38230913237两job成功，允许本问题独立编码。

用户2026-10-12批准第4/5项改进；Android-only / 1.2.x修复线。先用实际native发送与远端解码同输入测NS/AGC，再完成VOX底噪/拖尾/灵敏度门控。保持采集、mute和current producer所有权；不改产品状态/传输/协议/版本，不发布或部署，iOS跳过。

原始用户日志、合成复现、A/B采样与构建输出留在忽略`logs/2026-10-12-field-review/`。无真实道路参考音频，合成结果不能作为骑行或人工听音通过结论。
