# KUM-52 共享媒体验证

基于已包含KUM-50修复的分支，保留旧双人默认单会话；新增显式GROUP三会话模式。PC、sender、SDP/ICE队列和远端音轨归各session，公共ADM/factory/source/local track/VOX归engine。GroupMediaController仅执行已授权effects，首条/末条需求调用现有音频协调器，不接网络和UI。

审查修复了排队resume覆盖暂停、延迟dispose覆盖新授权两项P1。实际engine使用AudioIoGate代次校验；默认GROUP关闭I/O，队友播放屏蔽先于track启用。四项可控队列门控测试及11项群组媒体控制测试覆盖失败、撤销、替换和独立释放。

固定SHA复审：APPROVED，P0=0，P1=0。Base `ed13e35a6daee11d1f3b3a80020b636cc4dda6fb`；Head `3b334c9e296cfe40be6ac1bd1db3fff1ebc630d6`。Next gate allowed：完整检查后Draft PR和下个集成单元设计。

最终全量77 suites/630 tests无失败；lintDebug和assembleDebug通过（1分50秒）。使用既有JDK/SDK、subst M:和离线Gradle。编译验证真实WebRTC API接线，fake测试验证生命周期；未用fake声称原生混音或AudioRecord行为已验证。

用户已确认暂无设备，先完成代码与自动化。D01–D12实体三/四机听音、无路由器组网、电话/蓝牙和2小时/4小时观察均NOT RUN。后续认证、发现建网、信令集成和UI仍在实施，当前不是完整四人通话APK。
