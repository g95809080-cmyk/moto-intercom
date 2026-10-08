# KUM-63 设计

复用 KUM-62 的 PersistentLogStore、DiagnosticLogSession、DiagnosticLog、DiagnosticExport、MotoComApplication 及回归测试，保持滚动 168 小时、256 MiB 历史预算、512 条后台队列和独立只读导出文件。周期 VOX DEBUG 持久化每五秒采样，原 Logcat 频率保留。磁盘/队列失败不进入通话决策。

只在 v1.2.0 已有 Android Log 和 Service 事件出口接入诊断；Activity 日志展示不重复记录 Service 回放。日志 UI 的弱引用、页面 generation、onStart/onStop/onDestroy 刷新生命周期按旧 Activity 适配。构造参数只添加诊断依赖，不添加 1.3 的群组或后台设置参数；音频中断只加记录，不带入 1.3 的 publishAudioReady 改动。

版本配置遵循 version.properties，记录用户选择的固定功能源码 SHA；测试包 code 5 高于已分发的 1.2.0/code3 与 1.3.0/code4。构建只修改打包元数据，历史证书私钥不入库。先完成源码、完整 JVM/Lint/APK 与固定 SHA 只读审查，再核验签名并覆盖安装，保留数据/权限。小米 13 检查启动、进程重启后的历史与系统导出入口；不声称七天实时时长或双真机骑行已经验收。
