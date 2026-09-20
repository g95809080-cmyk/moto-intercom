# KUM-54：群组离线网络适配

在 KUM-53 认证基础上增加 BLE 发现、GATT 有界请求响应和 Android Wi-Fi 网络适配。此阶段还未从 UI 启动，不代表四人端到端对讲完成。

## 实现
- 固定独立服务 UUID 广播，不公开口令或网络凭据；发现窗口12秒、最多16个候选。
- GATT 每连接20秒总期限、至多4连接，房间级12次/分钟和每地址3次/分钟尝试预算。每片最多20字节、每消息8192字节；有界入口64任务，溢出合并关闭。
- 异步响应绑定连接和请求 counter，旧响应/取消/错误 ATT 请求不能交付到新请求；关闭回调抛错不妨碍清理。
- 房主创建 GO；清理 UNKNOWN 保留进程所有权，不会因超时放行新 owner。成员 API29+使用无互联网 NetworkSpecifier 请求；API23–28复用或新增自己的 Wi-Fi 配置，退出保留用户主动切网选择。返回 Network 供逐 socket 绑定。

## 审查
设计：Base `5d1c427`，Head `2edcc22`，APPROVED。
源码：Base `5d1c4270a61438e0d7b3229c73bcbc1f6a018ca2`，Head `fa799a0a965b069eb1b9f65fac3bc9914b11cad9`，APPROVED，P0/P1均无。
前轮P1（外部关闭回调中断清理、Handler入口无界）已修复并增加实际适配器回归。

## 自动化
`testDebugUnitTest lintDebug assembleDebug --offline --console=plain` 通过，耗时2分16秒；82个测试套件、664项测试、0失败。Lint 0错误、75警告（较前阶段新增一项：BLE_SCAN 的 usesPermissionFlags 仅API31+生效，旧版使用传统蓝牙权限）。新增24项测试覆盖分片/限速、GO迟到回调/UNKNOWN、GATT异步回复/ATT拒绝/洪泛/异常清理，以及Wi-Fi客户端取消和Legacy配置保护。
曾有一次并发编辑期间的Lint内部 `Unexpected owner function: null` 异常；源码固定后重跑完整命令通过，未禁用规则或降低检查。
BLE测试替身显式模拟sendResponse结果：默认Robolectric代理未注册binder会返回false；成功发送与原生失败分支分别测试，不放松生产校验。

## 尚未验证
用户当前没有实体设备。BLE真实吞吐、蓝牙耳机共存、OEM切网、Wi-Fi Direct三客户端容量、三人/四人双向听音及无路由器D01–D12均未执行。后续认证桥接、群组单写者、Service和UI仍需接入与自动化。
