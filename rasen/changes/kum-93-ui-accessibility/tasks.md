## 1. 实现

- [x] 1.1 移除发现页和设置页图标按钮内部的重复语义。
- [x] 1.2 移除昵称输入框上的 `contentDescription` 覆盖，保留可见标签和真实编辑语义，并切换到高对比度空态颜色。

## 2. 验证与交付

- [x] 2.1 增加图标单一标签、昵称 `EditableText`、选区、无 `contentDescription` 的 Compose 回归。
- [x] 2.2 运行相关 Compose 定向测试。
- [x] 2.3 运行完整 JVM、Lint 和 Debug APK 构建。
- [x] 2.4 固定 Base/Head 执行 `motointercom-product-architect` 只读审查；tree `b005e76850e48a3023cd585157d2485a1d228b16` 结果为 `APPROVED`，P0=0、P1=0。
- [ ] 2.5 记录真机或模拟器 TalkBack 验收限制并更新 Linear 状态。
