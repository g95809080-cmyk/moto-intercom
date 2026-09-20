## Context
KUM-53，承接KUM52；用户明确暂无实体设备，先代码和自动化。PRD要求六位码不能公开发现且完整确认后才开放媒体。

## Goals / Non-Goals
实现可由BLE或IP有界消息通道承载的口令认证与加密基础，不将此单元当作完整入队/设备身份签名/网络连接。不改变人数、无普通审批等产品语义。

## Decisions
采用org.bouncycastle:bcprov-jdk18on:1.86轻量API；2026-09-19 Maven Central metadata核实release=1.86，官网下载链接一致（页面简介仍写1.85，不以简介推测版本）。使用JPAKEParticipant默认NIST_3072、SHA256、SecureRandom，完整round1/round2/round3，不自行实现PAKE数学、不注册JCE Provider。

认证上下文固定字段：协议MCGA1、room instance、host runtime、host device/runtime、client device/runtime、每次握手随机UUID；UUID均canonical。hostRuntime须匹配host endpoint，host/client device不同。角色HOST/CLIENT分别生成唯一participantId，包含整个context；验证对端ID等于expected，防止跨房间/角色/端点拼接。握手UUID由发起者随机生成，响应者新建会话，一次性使用，重连重新握手。握手帧只有magic/version/round和有界BigInteger，不含口令、凭据有效布尔或可静态检验口令的派生值；round参与者身份由本次绑定context重建。

每个对象严格顺序：start生成round1；收到round1验证并返回round2；收到round2验证、计算keying material并返回round3；收到round3验证后才能takeChannel一次。API状态机串行，任何乱序/畸形/错码/超时/取消进入终态；未完成不能导出密钥或GroupJoinProof。握手总期限20秒、单调时钟，调用前后均检查；长度上限4KiB，BigInteger最多385字节、固定每轮项数、拒绝截断和尾随。未认证并发上限4和每候选/全局尝试速率须由后续transport管理器实施，本单元不创建无界任务。

从keying material用HKDF-SHA256和context SHA256作salt、固定domain info派生64字节，两方向各32byte AES-GCM密钥。secure channel最多明文65536byte；nonce为固定4byte方向标识+8byte递增sequence；AAD包含context digest和sequence。每方向从1开始，只接受恰好下一个sequence；AES/GCM/NoPadding、128bit tag；认证错误/重放/超限/耗尽计数立刻关闭channel。一次takeChannel防止同key多次初始化counter；channel不跨进程或重连复用。计数在加密前消耗，失败终止，避免nonce重用。

close清空能控制的byte[]并断开participant/key material引用；不承诺JVM BigInteger和库内部副本及时擦除。代码和异常不打印秘密/原始payload。密码数组创建后尽快清零，但GroupRoom为分享功能保留运行时六位码，按原规则离队/结束不持久化。

安装deviceId仍是稳定声明，口令证明表示对端知道码并绑定这次声明；不声称持码恶意客户端不能冒用deviceId。正常App进程重启继续使用DataStore同一ID，后续房主准入仍校验removed集合/容量/runtime/generation。认证完成本身不占席位、不打开媒体。

## Risks / Trade-offs
J-PAKE3072在旧Android速度需真机测量，BLE分片和耳机共存待下个适配器；本单元真实算法往返可先自动化。六位码在线猜测必须后续房间级限速，不广播任何离线verifier；不夸大身份安全。

## Validation
双端真实算法同码成功、错码失败、不同room/runtime/握手上下文失败；全部轮次重复/错序/畸形拒绝，未确认不能导出、重复导出拒绝、截止/取消；双向加密、篡改/重放/跨会话/超限拒绝、关闭后拒绝。依赖编译Android API23+和全量JVM/Lint/build，固定SHA只读审查。

## Sources
- https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.java
- https://www.bouncycastle.org/download/bouncy-castle-java/
- https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/maven-metadata.xml
