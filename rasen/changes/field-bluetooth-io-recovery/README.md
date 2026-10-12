# 骑行蓝牙录放恢复：本地准备 checkpoint

用户于 2026-10-12 批准执行五项实测问题的定位方案。本 change 仅覆盖第 1 项：蓝牙首次连接、重连后的实际录放与补授权限恢复。

- Stable Base: `717fbecc23d60b1b35280f3e895898491bf533dc`，1.2.2 稳定线；保留 KUM-81 LAN 修复。
- Local Branch: `fix/field-bluetooth-io-recovery`。
- Linear: 待恢复接口后查询重复 issue 并绑定 KUM-64 下的独立 follow-up，关联 KUM-68/KUM-81。多次 list/get 均在 connector HTTP 传输层失败，未创建或更改 Linear 状态，未虚构编号。
- 传输恢复后 Linear 明确返回 `UNAUTHORIZED / oauth_token_invalid_grant / TRIGGER_REAUTHENTICATION`；已请用户重新授权，当前正式绑定阻塞原因是过期授权。工作期间仅在 loopback 将工具旧代理端口 7890 临时转发至已运行的用户代理 7897；不记录传输内容、不改变系统代理设置，收尾停止该临时进程。
- 当前为本地候选准备；正式 checkpoint、推送、PR/CI 以及下一 issue 的推进须先完成真实 Linear 绑定与 P0/P1 为零的固定提交审查。
- 未变更版本、主分支、发布记录、协议、重连预算或 iOS；未部署候选 APK。
- 小米 13 `192.168.1.62:37751` 只读核查：已装 1.2.2+bb0fa92，User 0 的蓝牙/录音/电话状态权限已授予，应用 stopped、MODE_NORMAL，未复现活动蓝牙会话。
- 原始用户日志、实时设备 dump、构建日志均保留在忽略目录 `logs/2026-10-12-field-review/`，不进入 Git。
