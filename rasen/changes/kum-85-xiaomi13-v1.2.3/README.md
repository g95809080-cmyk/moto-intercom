# KUM-85 小米13修复测试安装

[KUM-85](https://linear.app/kuma999/issue/KUM-85)，[Draft PR #58](https://github.com/g95809080-cmyk/moto-intercom/pull/58)，branch `deploy/kum-85-xiaomi13-v1.2.3`，Base `5b369c9ebf68e2c5fd988f206782f5ee54d79906`。用户2026-10-13明确要求将已审查的Android修复安装到已开启无线调试的小米13；沿用1.2.x修复测试编号。

功能Source `f587472e251b59f3c25e8cad5e483c8e0afc887a` 已经只读APPROVED/P0P1=0、完整本地与固定Source CI通过，包含KUM82-84。仅调整版本权威为1.2.3+f587472/code7并补打包/安装证据；原app/src、构建、workflow不改。版本6已经实际分发，不复用。

这是用户授权的个人设备修复测试安装，非main正式发行：不合并main、不创建公开Release/tag、不改iOS。APK采用非debuggable Release variant及旧包一致的历史个人证书。单机安装不构成双机、蓝牙听音或道路验收。

2026-10-13已覆盖安装并冷启动成功，数据目录与16项已授权权限保留。打包Head8296e24只读APPROVED/P0P1=0。诊断页面和重启后历史日志检查未完成，问题保留In Review；安装证据及边界见verification.md。
