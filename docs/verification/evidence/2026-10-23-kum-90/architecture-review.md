# KUM-90 固定范围只读架构审查

日期：2026-10-23。独立 reviewer：`kum90_architecture`，使用项目指定 `motointercom-product-architect` 技能及完整 architecture-and-gates 参考。reviewer 未改文件、未运行构建、未委派其他 Agent。

```text
APPROVED (code and automated verification gate)
P0: none
P1: none
Non-blocking: report JDK 17 should be JDK 21.0.10, as recorded in environment.txt; corrected in the evidence-only follow-up.
Base SHA: d7800c1701e06b9708decba1ad0c514c4c52f518
Head SHA: 9666ef123fa6be8c6a76f0cf3d79485d3d5e3ddd
Functional Source: 5e438402c8858571d897327e24a3060d0dfd6e5a
Next gate allowed: independent Draft PR and evidence delivery; retain physical acceptance pending. No Done, main merge, or release.
```

审查确认 SDK 本帧 mute 决定、callback 后清零、发现初始化、PCM 格式、VOX、producer/revision 和原生 borrow 所有权边界均保留。API 28/35 故障顺序及非零回写 red/green 覆盖先前缺口。

独立复核 zip 内每份 XML：Debug/Beta 各 97 suites / 816 tests，failures/errors/skipped 全为 0；两份 Lint 各 0 errors / 71 warnings。原生 XML 为 7 类 / 27 tests，全为 0 failures/errors/skipped；构建与固定源码复核均 SUCCESSFUL。

本地产品 APK 和测试 APK 重新计算的 SHA256 均与 `apk-hashes.json` 一致。功能 Source 到审查 Head 的 `app/src`、`buildSrc` 无差异，临时红测导致的 BOM 差异已恢复并重新核验。

MI 6、小米 13 的本 Head 原生采集、发现初始化、双机断开/重扫/重连未执行。用户明确本轮无设备；模拟器和历史记录不替代实机验收。人工听音、蓝牙与道路效果未标通过。

先前固定范围 `d7800c1..e73b9e8` 为 REQUEST CHANGES，P0=0/P1=1：RNNoise 可把历史音频写回 SDK 静音帧。该问题由 `5e43840` 修复，并通过原生非零回写回归复核关闭。
