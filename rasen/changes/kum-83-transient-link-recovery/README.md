# KUM-83 短暂掉线与自动恢复

[Linear KUM-83](https://linear.app/kuma999/issue/KUM-83)，分支 `fix/kum-83-transient-link-recovery`，Base `fd0bcd0ff194e165216e7d2b515c0f4f52a82552`（KUM-82 源码获批且 be443c4c 两项 Android CI success）。Android-only，v1.2.x 修复测试线。

用户批准 2026-10-12 五项骑行改进方案的第2/3项。独立只读准备确认观察接线与 P2P 清理排序缺口；本 change 实施后需固定源码审查，不把准备设计当源码批准。

固定源码 `ff19c4f152f3800b19be769f7149fbfc748e0db5` 的只读架构审查 APPROVED，P0/P1=0，25/25路径覆盖；793 JVM tests和API36的26 pass＋2双机skip已核验。完整覆盖、红绿回归与限制见 coverage.json / verification.md。

独立 [Draft PR #56](https://github.com/g95809080-cmyk/moto-intercom/pull/56)，Base为KUM-82。固定Source Head [Android CI 38230913237](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38230913237) 运行中；其后仅补Rasen证据文档，须确认生产/测试/构建blob不变。CI闭合后推进KUM-84。

原始用户日志、设备 dump、红绿回归和构建输出留在忽略 `logs/2026-10-12-field-review/`。初次 RF 掉线根因未证实；普通蓝牙听音、实机双手机及骑行验收尚待条件。未修改version、approvedCommit、main或iOS，未发布或部署小米13。
