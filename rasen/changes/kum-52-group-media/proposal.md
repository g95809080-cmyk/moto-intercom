## Why
KUM-52 实现四人PRD的共享媒体基础。当前音频引擎和协调器均只允许一个连接，不能建立三条独立本机PeerConnection。

## What Changes
- 将RiderAudioEngine逐连接资源放入MediaSession，公共ADM/factory/source/track/VOX仍由engine持有。
- 新增独立群组媒体控制器和fake回归，旧双人默认单会话。

## Capabilities
### New Capabilities
- `group-media`: 群组共享音频与逐成员生命周期。
### Modified Capabilities
无产品规则变化。

## Impact
RiderAudioEngine、独立group控制器及测试。无网络/UI集成。用户明确先完成代码与自动化；真机混音、音轨实际采集和路由待实测。
