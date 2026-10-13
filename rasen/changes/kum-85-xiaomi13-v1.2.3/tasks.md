- [x] 1. 用户安装授权、Linear/独立branch/change及目标手机/旧证书识别
- [x] 2. 固定已审查Source、version.properties更新为patch1.2.3/code7
- [x] 3. JVM/Release Lint/Release APK与固定打包Head只读批准
- [x] 4. APK同签名/非debuggable/版本/对齐/hash检查及覆盖安装
- [ ] 5. 小米13启动、设置/日志留存/权限/崩溃检查与Git/问题证据同步
- [ ] 6. 双手机/普通蓝牙/道路验收（不属于单机安装通过结论）

2026-10-13：覆盖安装、冷启动、数据目录与权限保留、首页显示及启动崩溃检查已通过。第5项中的诊断页面与进程重启后的历史日志检查尚未完成，UI dump失败不能算验收通过；详见verification.md。第6项继续由KUM82-84跟踪。
