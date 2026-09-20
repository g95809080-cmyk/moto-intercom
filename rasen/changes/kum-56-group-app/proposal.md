## Why
KUM-49至55已有域、认证、网络和控制编排，但用户还不能启动四人房间。KUM-56完成已确认PRD的Android生产入口与自动化。
## What Changes
- 现有Service拥有独立群组执行器，双人/群组资源互斥；页面重建不离队，进程重启不复活。
- 群组页面支持凭码、候选选择、成员/恢复状态、自静音、本机屏蔽、移除解除和确认结束。
- 接入真实共享音频平台与RTP、ADM录放音、路由证据，缺证据不显示全队就绪。
- 完成无路由器控制和媒体调用链、自动化及APK证据。
## Capabilities
### New Capabilities
- `group-app`: 群组前台服务、界面及生产音频闭环。
### Modified Capabilities
无；现有双人协议和配对规则保持。
## Impact
IntercomService、MainActivity/MainScreen/HomeScreen、独立GroupActivity/GroupRuntime、RiderAudioEngine与群组效果接口、Manifest及测试。无新云服务；实体设备验收NOT RUN。
