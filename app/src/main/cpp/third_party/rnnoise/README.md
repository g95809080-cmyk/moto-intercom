# RNNoise 原生降噪

这里固定保存官方 RNNoise 推理代码及默认模型，让 Beta 构建不需要联网下载模型。用途是处理 48 kHz 单声道 PCM16，每帧 480 个采样点（10 ms）。

## 来源与校验

- 官方仓库：<https://github.com/xiph/rnnoise>
- 固定代码提交：`70f1d256acd4b34a572f999a05c87bf00b67730d`
- 源码包：<https://codeload.github.com/xiph/rnnoise/tar.gz/70f1d256acd4b34a572f999a05c87bf00b67730d>
- 源码包 SHA-256：`f61ee0b3f4c4cd337303e003d333357c5eaf25ef5d75a742109ee59e9a0a3932`
- 该提交的 `model_version`：`0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37`
- 官方模型包：<https://media.xiph.org/rnnoise/models/rnnoise_data-0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37.tar.gz>
- 模型包 SHA-256：`0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37`
- `src/rnnoise_data.c` SHA-256：`d6021b7697677c4d2274c912975e143765552b0e6f25500aa660fdb4a9849be5`
- `src/rnnoise_data.h` SHA-256：`09ff880bddd0fc74a2ae0e5ec6c8d65714031b08d0c3f672493acd9e189c5855`

源码包只保留 CMake 构建需要的 C 源码、头文件、`COPYING` 和 `model_version`。模型包只保留默认 `rnnoise_data.c`（78,003,012 字节）及 `rnnoise_data.h`（926 字节），两者保持官方原始内容；未保留训练权重、训练脚本、little 模型或下载压缩包。

## 构建与处理约定

上级 `CMakeLists.txt` 输出 `libmoto_rnnoise.so`，使用官方默认 `DISABLE_DEBUG_FLOAT=1` 量化 DNN 路径和对应架构的 NEON / SSE2 向量实现；信号处理使用浮点。模型编译进动态库，无外部文件加载。

JNI 为每个 `RnNoiseProcessor` 分配独立状态与工作缓冲，在初始化阶段完成分配。音频回调原位处理 direct ByteBuffer，不写录音文件，不在成功路径分配对象或原生堆内存。`reset()` 通过官方 `rnnoise_init()` 清除完整模型和音频历史。处理保留官方模型的帧延迟；Beta 对比时应同时观察听感和端到端延迟。

许可证见本目录 `COPYING`（BSD-3-Clause）。APK 同时包含 `assets/rnnoise-license.txt`。
