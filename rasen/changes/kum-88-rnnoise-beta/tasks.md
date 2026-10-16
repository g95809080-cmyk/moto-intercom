## 1. 实现
- [x] 1.1 固定官方代码与模型，提供JNI和严格格式检查。
- [x] 1.2 接入当前有效PCM，保持mute/VOX/epoch/producer所有权。
- [x] 1.3 完成Beta离线开关、偏好、启动Intent与互斥NS模式。
- [x] 1.4 新增独立安装Beta身份和测试签名构建。

## 2. 验证与交付
- [x] 2.1 运行Debug/Beta JVM、Lint、APK构建与模型真实性检查。
- [x] 2.2 在owned API36 AVD验证JNI、负载、reset、并发与真实音频回调。
- [x] 2.3 检查Beta界面、持久化、独立安装与版本签名。
- [x] 2.4 固定功能Head做独立架构审查，完成PR/CI。
- [x] 2.5 输出APK、校验和及中文体验说明，记录未执行的物理听音。

固定功能Source `dc7a45d01fc837f795cdcbffa5bf7616a25e5e85` 已获 APPROVED；[CI run 38868719425](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38868719425) 的 verify 和 API36 instrumentation 两个 job 均成功。打包元数据与完整证据见 `docs/verification/2026-10-16-rnnoise-beta.md`。后续元数据提交的CI状态在PR/KUM-88交付记录单独追踪；不据此宣称真实设备验收或合并main。
