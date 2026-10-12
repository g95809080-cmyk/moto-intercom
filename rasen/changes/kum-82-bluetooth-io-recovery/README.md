# KUM-82 骑行蓝牙录放恢复

用户于 2026-10-12 批准执行五项实测问题的定位方案。本 change 仅覆盖第 1 项：蓝牙首次连接、重连后的实际录放与补授权限恢复。

- Stable Base: `717fbecc23d60b1b35280f3e895898491bf533dc`，1.2.2 稳定线；保留 KUM-81 LAN 修复。
- Branch: `fix/kum-82-bluetooth-io-recovery`。
- Linear: [KUM-82](https://linear.app/kuma999/issue/KUM-82)，KUM-64 下的独立 follow-up，关联 KUM-68/KUM-81。2026-10-12 用户重新授权后核查全部子问题，确认无重复再建立；当前 In Progress。
- 授权阻塞期间使用本地名称 `field-bluetooth-io-recovery` 准备源码和审查；现在正式更名绑定，保留 Git 历史。源码批准 Head 为 `49bf4e50dbc1d4b5f7c75686cad032e10a25b9c6`，其后只有证据和绑定文档变化。
- 工作期间仅在 loopback 将工具旧代理端口 7890 临时转发至已运行的用户代理 7897；不记录传输内容、不改变系统代理设置，收尾停止该临时进程。
- 下一 gate 为独立 Draft PR 和固定 Head Android CI；不自动合并、发布或部署。
- 未变更版本、主分支、发布记录、协议、重连预算或 iOS；未部署候选 APK。
- 小米 13 `192.168.1.62:37751` 只读核查：已装 1.2.2+bb0fa92，User 0 的蓝牙/录音/电话状态权限已授予，应用 stopped、MODE_NORMAL，未复现活动蓝牙会话。
- 原始用户日志、实时设备 dump、构建日志均保留在忽略目录 `logs/2026-10-12-field-review/`，不进入 Git。
