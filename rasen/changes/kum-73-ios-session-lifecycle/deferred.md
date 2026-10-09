# 用户暂缓 iOS

2026-10-09 用户明确指示「跳过 IOS 端」。KUM-73 和 KUM-74 暂缓，不作为本轮 Android 骑行修复的验收或整合依赖。

KUM-73 已提交且已推送的源代码检查点为 `b3fc5e9150f7c2a276e6f87a4cf105809958fb0d`，分支 `fix/kum-73-ios-session-lifecycle`，基线 `0f5e7b778ee0b945725c9506055d6137a7faf546`。保留可恢复历史；任务清单不勾选，独立架构复审尚未闭合，没有 Draft PR、合并、版本变更或部署。

旧 CI `37937197603` 默认及真实 WebRTC SDK 两组通过，之后新增回归在 `37937731958` 暴露 BLE 不可用通知覆盖手动网络状态的缺陷。最新源已修复该状态覆盖，并补实际 native failure 终结回归；`37939186771` 进行时因用户调整范围取消，不能以较早绿色 CI 代替最新源验证。

恢复 iOS 工作时须先核实本分支与当前 main 的差异，再完成固定 SHA 的只读架构/OCR 门禁和默认、真实 SDK 的两组 macOS Simulator CI。实际 BLE source producer 属于尚未开始的 KUM-74；真实 decoded PCM sink 与设备效果未验证。当前分支不能作为已验收的 iOS 发布来源。
