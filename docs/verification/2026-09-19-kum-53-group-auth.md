# KUM-53 六位码认证验证

使用BouncyCastle1.86轻量J-PAKE三轮协议，绑定room、两端device/runtime及握手UUID；确认后一次性HKDF派生双向AES-GCM通道。未注册系统provider；不生成GroupJoinProof、不占成员席位、不接真实网络或媒体。

10项真实算法测试通过：正确/错误码、跨room/runtime/握手上下文、方向反射、错序/截断/超限、截止/取消、并发导出一次、双向加密、篡改与重放、最大65536字节明文、并发序列唯一。完整78 suites/640 tests通过，lintDebug 0错误/74警告，assembleDebug通过（1分44秒）。依赖已从Maven Central核实并编译，Android打包通过。

固定源码审查APPROVED，P0/P1=0；Base `82daa0d679912cdd8742b51acddb759789d61ede`，Head `d5236018e263795a5e8c630421cb22c0a9056c82`。后续仅合入KUM52 instrumentation修正4dc31bb，集成Head `a776b1bd89bc71eaa1bb10ba5965cae52ae1f8d4`，认证源码未变。

KUM52最初云端instrumentation失败为旧测试反射已迁移字段，已修正并增加三PC原生生命周期测试；修正测试固定SHA复审通过，云端run35448384664验证中。该证据不代表三机互听。

后续真实通道必须实施全局/候选在线限速、最多4个未认证握手、硬截止与资源回收，并在意图撤销时主动close已导出的SecureGroupChannel。口令证明不等于设备私钥身份证明；持码客户端主动冒用稳定UUID不在既有身份保障范围内。正常App进程重启仍按原ID执行移除限制。

D01–D12仍NOT RUN，用户明确暂时没有设备、先完成代码与自动化；预IP发现、建网、准入桥接和UI继续实施。
