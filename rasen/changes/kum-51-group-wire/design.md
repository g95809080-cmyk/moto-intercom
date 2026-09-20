## Context

KUM-51；Base 48f52ef6937d9564b5aef75ba41db00288e22ed2，已包含 main 880e3ed。本分支堆叠在 KUM-49 之上，PR base 为 feat/kum-49-local-group-domain。保留用户工具配置改动。

## Goals / Non-Goals

只实现准入后的控制帧与主机直连通道接收授权。没有 JOIN/口令/认证标志的 wire 字段；解码不产生 GroupJoinProof。认证、真实通道和 Service 调用仍需下个设计门禁。

## Decisions

帧使用大端二进制固定字段：4字节 magic MCGP、2字节版本1、1字节消息类型；room UUID、host runtime UUID；sender 与 recipient 各为 device UUID、runtime UUID、正整数 incarnation、controlGeneration；正整数 sequence；类型专属 payload。UUID固定16字节，不使用 JSON，重复字段/未知字段表现为尾随数据并拒绝。最大64KiB，decode先限长，stream读取先验证4字节长度再分配。字符串使用长度前缀与严格UTF-8，上限按字段检查；未知版本/类型、负数、截断、尾随均拒绝。encode也执行同样校验。不得日志打印 payload。

首批类型：Leave(1)、End(2)、Remove(3，目标device)、Unblock(4，目标device)、AudioAvailable(5，0/1)、Offer(6，link lease+SDP)、Answer(7，同前)、Candidate(8，link lease+mid+line index+candidate)。SDP上限48KiB，candidate 4KiB，mid256bytes。不提前冻结名单同步与认证握手格式。

授权上下文由将来的唯一 SessionOrchestrator 写入者提供：当前 GroupRoom 快照、本机 GroupIntentToken、当前连接的 GroupMemberLease 与接收序列。wire 是未受信值，不能创建上下文。gate 调用同时接受当前 participation，必须 stillCurrent；room未结束且token匹配当前room；sender严格等于通道peer并且是当前ADMITTED成员，recipient也必须是当前ADMITTED成员且与预期目标一致。控制消息只能发送给房主；Leave不能来自房主；End/Remove/Unblock只能来自房主，因本单元是远端接收 gate，其无效远端命令全部拒绝，主机本地命令走域模型。AudioAvailable仅本人向房主发送。Offer/Answer/Candidate由房主接收并验证两端和当前link lease，可得到可转发未执行结果；不发送socket，不实现接收端转发信任。link pair须匹配sender/recipient，当前两端incarnation一致；Offer由字典序小端产生，Answer大端。不把SDP/ICE视为语音就绪。

序列只对同一个(room, intent, peer lease)接收上下文严格递增，拒绝值不消费序列；通道替换必须新建gate状态，旧state与新room/lease不匹配。状态为immutable值，调用者必须串行提交授权成功的returned state，不能多线程各读旧状态。无集合无限增长。默认toString隐藏媒体payload。

## Risks / Trade-offs

设计审查 2026-09-19 APPROVED，P0/P1=0，Base/Head 48f52ef。补充实现约束：所有 generation/incarnation/sequence 使用64位正整数；字符串和流长度使用32位有符号整数、以UTF-8字节计量；最短帧143字节。gate实际校验localHost等于当前房主。相同控制generation必须沿用序列状态，只有真实通道替换及新generation才可从零开始；禁止通过重新创建对象重放同连接消息。媒体授权结果仅表示待转发，不表示已转发或就绪。

本单元不能独立提供认证或可用通话。被移除身份的强证明、预IP凭码匹配、房主转发封套与名单同步尚需设计。本gate只验证来自真实认证适配器的上下文，不把安装UUID声明说成密码学证明。设备缺失不影响本单元纯协议测试，D01–D12仍未执行。

## Validation

类型往返与字段边界，所有截断位置、畸形UTF-8、版本/magic/类型、流超限拒绝；旧V2不误解码；伪造sender、错误room/目标/资格、旧control generation、重复sequence、离队后回调、无关成员变化保留健康link；全量测试/Lint/build和固定SHA只读审查。
