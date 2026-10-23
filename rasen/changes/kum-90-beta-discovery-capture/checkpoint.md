# KUM-90 恢复检查点

2026-10-23 在 `MotoIntercomApp` 主工作区继续；唯一写者为本轮主 Agent，只读证据核对和架构 reviewer 不修改仓库。用户本轮明确不合并 main、不发布，且没有手机，实机验证保留待办。

## 恢复时核对

- Linear 完整描述与评论已读取：状态 In Progress，无评论、无关联 PR；Base 为 `d7800c1701e06b9708decba1ad0c514c4c52f518`。
- 旧 `MotoIntercomApp-rnnoise-beta` 已有 `e73b9e8f0f0ec9557b5a52947039a1986eead91a`，未推送；tasks 尚未勾选。远端没有 KUM-90 分支或 PR，也没有更新的完整修复。
- 主工作区原分支 `test/pair-verification-package` 保留在 `151c0c72f4d53ada899431a42998ee8cf2f68000`。两个原有未跟踪 product 文档保留，不纳入本 issue。
- 旧工作树先 detach 在 `e73b9e8`，再在主工作区接管同一 `fix/kum-90-beta-discovery-capture` 分支；旧树原有三份未提交的版本/文档修改原样保留，并复制到主树继续整理。没有建立第二条实现分支。
- 发现更晚的稳定线部分修复 `cdf9dfb9519be9adb714fb733d5c41168c314df1`：发现被无组通知撤销后重建注册。本 issue 在无 owner 时阻止撤销，且还有 RNNoise 修复；该稳定线提交不是 KUM-90 的后继或完整替代，不整体移植其他测试分支。
- `main` / `origin/main` / 远端 main 都为 `a72cea7c1ff1879a2b6175b17881d0686c22d9c4`。

## 初次固定架构审查

`motointercom-product-architect` 独立只读审查：

```text
REQUEST CHANGES
P0: none
P1: SDK mute 先清零，RNNoise callback 可把历史音频写回；必须保留本帧 mute 决定并在 callback 后重新静音。
Non-blocking: startup 回归补 API 28；原生测试补最终静音缓冲区断言。
Base SHA: d7800c1701e06b9708decba1ad0c514c4c52f518
Head SHA: e73b9e8f0f0ec9557b5a52947039a1986eead91a
Next gate allowed: 修复并补充回归后，以新固定 Head 重审。
```

## 历史验证边界

`../RNNoise-Beta/validation-2026-10-17` 是修复前诊断：MI 6 初始化 0/3 就绪、两机完整 SDK 静音帧游标在末尾。诊断测试以复现故障为成功，不能当作 red/green。

`../RNNoise-Beta/fix-2026-10-17` 的原 XML 经独立复核：Debug/Beta 各 810 tests 无失败，Lint 各 0 errors / 71 warnings；两机各 24 项 instrumentation 通过，初始化各 ready=3 / unready=0。双机断开、重扫和重连记录为 LAN，不能标纯 Wi-Fi Direct 重连通过。历史测试 APK 缺少当时保存的完整 SHA/commit manifest，不能补写成已固定来源的新证据。

历史 beta.2/code10 APK 已装两机，SHA256 `a5ababde847991c4bd13faf9c7a2da2eb95ac6ca0d82ab19dfa0d37b848f17f2`。本轮新包必须递增身份，正式版字段及 code9/code10 既有身份不复用。

本轮最终验证与剩余门禁见 `docs/verification/2026-10-23-kum-90.md`。
