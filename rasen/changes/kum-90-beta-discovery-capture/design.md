## Context

KUM-90，Base d7800c1701e06b9708decba1ad0c514c4c52f518。MI 6 三轮真实启动均 query 1→2→3 且服务请求未创建；两机真实 SDK 静音帧均让 RNNoise remaining 校验报错。原始证据保留在工作区外 RNNoise-Beta/validation-2026-10-17。

## Decisions

- 只有 DISCOVERING、无 selected/target attempt、无连接地址、无隧道/验证/Socket 时，把无组断线通知当作空闲状态提示，在增加查询代次之前忽略。已选连接、目标重试及待验证物理组继续原有确认和清理路径。
- 在固定 WebRTC 1.3.9 的 ASM 捕获桥内创建完整帧的 duplicate 视图。该调用点已有 SDK 实际读取字节数等于容量的条件；clear 仅作用于独立视图，不修改 SDK 原缓冲区的 position/limit。RiderAudioEngine 的格式、帧长、静音、VOX 与 producer/revision 校验保留。
- 不改变状态机、目标选择、身份验证、恢复策略或正式产品线。回归覆盖两种初始化交错、关闭后迟到回调及真实原生静音帧。
- 固定 SDK 的 `microphoneMute` 原始读取值保存到生产者线程独有的 `motoFrameMuted`。完整帧回调完成后，如果本帧已静音或回调期间开启静音，按绝对索引重新清零原缓冲区；回调期间取消静音不能释放当前已静音帧的模型历史。原游标及原生借用所有权终检保持不变，字段类型、volatile 属性和读取点数量不符时构建失败。
- 确定性初始化回归覆盖 API 28/35；SDK 桥回归覆盖非零回写、回调期间静音/取消静音和非静音原位写入；真实 SDK 录音在 callback 后的 samples tail 检查静音帧全零，使用真实保存的 mute 决定，避免再次从游标推断静音。

## Gates

定向回归、Debug/Beta 单测/Lint/构建；固定 Base/Head 只读架构审查无 P0/P1；双机真实初始化、原生录音与断开重扫重连验证。人工听音与道路效果不代验。
