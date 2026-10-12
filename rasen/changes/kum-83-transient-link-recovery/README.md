# KUM-83 短暂掉线与自动恢复

[Linear KUM-83](https://linear.app/kuma999/issue/KUM-83)，分支 `fix/kum-83-transient-link-recovery`，Base `fd0bcd0ff194e165216e7d2b515c0f4f52a82552`（KUM-82 源码获批且 be443c4c 两项 Android CI success）。Android-only，v1.2.x 修复测试线。

用户批准 2026-10-12 五项骑行改进方案的第2/3项。独立只读准备确认观察接线与 P2P 清理排序缺口；本 change 实施后需固定源码审查，不把准备设计当源码批准。

原始用户日志、设备 dump、红绿回归和构建输出留在忽略 `logs/2026-10-12-field-review/`。初次 RF 掉线根因未证实；实机双手机及骑行验收尚待条件。PR/CI 尚未建立。
