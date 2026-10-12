# 设计

`AudioSessionController` 继续拥有一个 runtime 内的可替换 media lease。探针属于该 lease 和实际 RiderMediaSession；所有结果在最终应用前重新检查 upstream runtime/attempt、lease、session、查询版本、系统 route revision 和通信协调器状态。已撤销的查询、计时器或设备不能认证或恢复替代会话。

实际输入来自固定 WebRTC 1.3.9 模块自身的 AudioRecord，沿已有诊断字段与 R8 keep 规则读取；输出来自 `DecodedAudioPlayout` 当前播放 lease 所持有的 Android AudioTrack。类型及新鲜 PCM/rendered evidence 均不足时不生成就绪证明；SDK 接受 setter 或蓝牙名称不是录放证据。新增 query 在 RTC 队列执行并经现有当前会话回调返回。

探针每秒采样，断开的 PeerConnection 和电话/focus 中断不触发恢复。正常连通且系统 Bluetooth route 已验证、实际 native 录放持续两秒不匹配，调用已有 `CommunicationAudioCoordinator.reapplyPreferredRoute(force=true)`。自动恢复上限为每 media 两次；只有显式偏好更新重置该预算。媒体关闭取消计时器，旧媒体回调重新校验归属后丢弃；当前媒体仍可通过后续真实证据自然恢复 readiness。

选路来源明确区分 `USER_SELECTION`、`ACTIVITY_REBIND`、`PERMISSION_GRANTED` 和 `RUNTIME_START`；所有选路重试都撤销旧查询和证明，但仅用户选路清零当前 media 的自动恢复计数。Activity 的 stop/start 不得补发自动次数。Service 将来源写入诊断并传给原控制器，不参与产品状态裁决。

native snapshot 携带 RTC 开始读取设备时的 monotonic `sampledAtElapsedRealtimeMs`。生产端同时核对采样时和交付时 I/O generation/freshness，并限制原设备采样年龄；持续的新 PCM 不给旧设备类型延长有效期。消费者拒绝超过两秒或逆时样本，清除证明及连续异常观察，不凭它消耗重试次数。有效证明的计时使用原采样时间。真实 RTC→Main 阻塞 2.2 秒的仪器红回归确认旧实现会把过期元数据重新认证；修复后须验证该采样失败、新采样仍可恢复。

`AudioRouteEvidence` 增加实际已验证蓝牙标志；fallback phone 不被强制当作蓝牙。Activity 在已有权限展示刷新路径检测 missing→granted，仅重试当前保存的蓝牙偏好，不重复要求焦点，不强迫用户改回蓝牙。

测试覆盖系统已蓝牙/native 输入仍内置、输出仍手机、新鲜度不足、未连通长等待、有限重试、显式手机偏好、来电/focus、媒体替换迟到、上游撤销和 Activity 实际补授权限。真实 JNI/AudioRecord/AudioTrack 仪器回归核对元数据生产者与暂停/恢复，不把 emulator/单手机结果当作双设备骑行或人工听感验证。
