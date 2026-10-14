## Why

KUM-66 / 骑行问题2：VOX 原来以本地音轨音量代替上行静音，高噪声底使滑块门限合并，滑动又反复重建门控校准。

## What Changes

- 补齐 pinned SDK 未接入的编码前 PCM 回调，先测量原始能量，再在 VOX/手动闭麦时清零实际 JNI 输入；保持 native track 与 encoding active，以持续采集。
- 灵敏度同时影响绝对门限和噪声余量，滑动保留已学噪声与校准截止，保证高灵敏度在背景音下也会闭麦。
- 以原生双 PeerConnection instrumentation 验证发送静音不停止采集，以及开麦/手动静音/旧 revision 隔离。

## Impact

基于 KUM-65 稳定修复分支，不带入群组；当前请求不改变批准发布版本或安装设备。真实说话、风噪及远端听感单独验收。
