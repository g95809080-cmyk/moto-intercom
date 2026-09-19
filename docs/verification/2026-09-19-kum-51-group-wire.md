# KUM-51 群组协议验证

新 main `880e3ed8b4423100114758232c3f75d93e25b270` 的 KUM-50 修复已无冲突并入 KUM-49，集成提交 `48f52ef6937d9564b5aef75ba41db00288e22ed2`。该基线73 suites/601 tests通过，Lint无错误，Debug构建通过。PR24已更新，没有合并main。

本单元素码 `242fae012469dacde2f63323cf0202929cc0628c`：独立MCGP v1帧与房主接收授权，14项新增测试。group定向36 tests通过（42秒）；全量75 suites/615 tests通过，lintDebug无错误，assembleDebug通过（1分23秒）。使用JDK F:/Android/jbr、现有SDK与离线Gradle，Unicode路径通过subst M:运行。旧V2与Android调用路径未改。

只读架构审查：APPROVED；P0无；P1无；Base `48f52ef6937d9564b5aef75ba41db00288e22ed2`，Head `242fae012469dacde2f63323cf0202929cc0628c`。Next gate allowed：PR审查回填及下一独立共享媒体单元的设计审查。

重要边界：相同controlGeneration不能重建空序列gate；真实通道集成必须保持此所有权。解码数据不产生认证证明，授权结果不表示已经转发、加入或语音就绪。未实现认证/名单同步/预IP发现/socket/媒体/UI。

当前ADB无设备。用户明确先完成代码与自动化；D01–D12的三机/四机听音、无路由器、来电/蓝牙、2小时及4小时观察均NOT RUN，不能由这些测试替代。
