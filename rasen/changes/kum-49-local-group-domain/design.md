## Context

KUM-49 使用已批准草案 docs/design/local-group/2026-09-16-implementation-design-draft.md 的纯域模型范围。原接口授权已恢复；前轮阻塞记录作为历史保留。

## Goals / Non-Goals

Goals：确定性房主 reducer、受验证准入、成员/连接代次、60秒边界、权限与逐对就绪；本机参与意图和静音状态。
Non-Goals：不改变 SessionOrchestrator/Service/音频引擎，不增加 wire codec、认证算法、网络、UI 或持久化。

## Decisions

- GroupRoom 为不可变快照，输入事件和单调时刻，输出新快照及结构化结果；调用者将来必须从唯一 orchestrator 串行提交，不能同时把同一旧快照的多个结果提交。当前只在单测调用。
- RoomKey 包含随机实例及房主 runtime。PeerLease 含 deviceId/runtime/incarnation/controlGeneration；名单 revision 不参与健康链路失效。
- 加入证据来自抽象的后续认证适配器；模型只检验版本/身份/凭据判定，不宣称实现了安全认证。六位码只负责产生/显示，不广播也不日志输出。
- 未完成认证不占位；已验证 JOIN 成功即名单准入，重复 request 缓存30秒/128项；移除/离开/结束撤销旧凭据资格。保留期60秒从首次掉线开始；到期保留可恢复的等待记录但不占席位。
- 每条媒体 link 保存两端成员 incarnation、自身递增代次及双方确认；换成员资格/掉线清除相关证据，无关成员变化不影响其余关系。所有音频就绪证据只作为模型输入，实际采集/统计探针为后续任务。
- GroupParticipation 默认未参与，显式 begin 生成新generation；stop 后回调不能恢复意图；自静音和本机屏蔽在本次参与内保留，stop清除。

## Risks / Trade-offs

未接生产路径 → 本次APK仍无多人入口。验证证据只是受信抽象 → 禁止以模型测试声称真实网络认证或听音已通过。等待记录/限制仅运行时保存 → 不恢复跨进程房间。
