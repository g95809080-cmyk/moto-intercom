## Why

导航/其他应用的临时焦点中断后，abandon 删除焦点栈条目，无法自动恢复；永久丢失被忽略，结束媒体时未释放通信模式。

## What Changes

保留 transient 焦点请求等待系统 GAIN，处理永久 LOSS 并拒绝迟到 GAIN；DELAYED 不周期重请求；媒体结束和焦点中断释放通信模式，电话中断保留系统电话模式。

## Impact

仅修改 CommunicationAudioCoordinator 和回归，基于稳定日志版，不宣称 SCO/A2DP 可听共存已真机通过。
