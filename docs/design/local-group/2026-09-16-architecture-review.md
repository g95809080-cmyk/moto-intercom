# motointercom-product-architect 只读设计审查

日期：2026-09-16。独立 reviewer：本任务 `architecture_review`；使用本机 `motointercom-product-architect` skill 与 architecture-and-gates reference，全程只读，不运行重复构建、不修改文件。

## 结论

APPROVED：仅批准实施草案中的纯域模型设计范围。尚未批准网络、认证、wire codec、共享音频或 UI 集成；源码尚无变更，本记录不是实现验收。

## P0

无。

## P1

有限范围内无未关闭项。首轮 REQUEST CHANGES 提出的两项设计 P1 已修复并复审关闭：

1. 将名单 RoomRosterRevision 与每个成员 MembershipIncarnation 分离，PeerLink 绑定两端 incarnation；无关成员加入/退出/恢复不得失效健康 A–B 链路。
2. 明确同 deviceId 新 runtime 的显式 JOIN、串行席位替换、幂等范围和有界缓存；补充 ControlConnectionGeneration，旧 socket close/error/heartbeat 不能改变新连接或续期占位。

## Non-blocking

- 纯域模型只在单测运行，不产生 Android/网络/媒体 effects，不新增 wire codec；抽象的身份/凭据验证输入不能来自 UI 或未认证发现广告的自我授权。
- 预 IP 凭码方案、设备认证绑定、媒体录制开关和就绪探针仍未收敛，属于后续实施门禁。
- Linear oauth_token_invalid_grant 阻塞真实 issue、依赖和 Exit Criteria 绑定，是工具/治理阻塞，不是源码 P1。
- reviewer 未独立运行测试；基线自动化由主任务单独记录在 `../../verification/2026-09-16-local-group-preflight.md`。
- 当前无真实设备证据，不建议关闭任何 issue。

## Base SHA

`0f808ad44f70976624f69d242bfb0a72aa3f77f2`

## Head SHA

`0f808ad44f70976624f69d242bfb0a72aa3f77f2`

草案未提交，以内容 hash 额外固定审查对象。首轮 hash：`ECE901EBB7216B912FED42FCF2786426B0E04DE9566533218BCB97199416714A`。P1 修复后的独立复审 hash：`17DD3EC07255659E8AF114D63EDEEA09A3C3AEFE8499F1B43C157A9D0D1AAB25`。随后仅移除文件末尾多余空行，最终文件 hash：`78ED895A456879046E63CE779B65676AAFE050A0C805825B111CEAEA703ED5AE`，正文未变。

## Next gate allowed

允许保存设计与验证证据。待 Linear 可用并核实真实活动 issue、建立相应 Rasen change 和实现分支后，进入草案第 7 节首项的纯域模型实现及单测。对实际源码差异再次执行架构审查。当前不允许跳到 wire codec、认证发现、Wi-Fi Direct、WebRTC、共享音频或 UI 集成。
