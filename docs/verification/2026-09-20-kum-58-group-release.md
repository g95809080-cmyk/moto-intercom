# KUM-58：群组网络释放与重建

## 范围与现场

用户报告小米13房主后台掉线，回前台恢复失败；结束后再创建一直提示等待网络释放。现场进程与前台服务仍存活，`dumpsys wifip2p` 已是 `InactiveState`，应用仍重复发送 `REMOVE_GROUP`。旧代码只等待删除成功，没有确认“网络已经不存在”的路径；恢复和停止还可能各自启动一条重试循环。

随后使用原安装版本进行三台手机重试：房主退桌面及熄屏后网络仍为 `GroupCreatedState`，用户分别确认其他两台仍能互听。用户明确要求暂缓后台掉线原因追查，本轮只修复释放卡死与重建阻塞；不宣称后台掉线触发原因已解决。

## 修复

- 删除失败后，当前通道确认群组为空且连接明确未形成，才释放旧GO所有权。
- 未完成创建、未完成删除、读取失败/超时/矛盾结果均保留占用；读取超时后继续读，不盲目再次删除。
- 同一旧host的恢复/停止等待者共用一条清理重试链。完成时清空旧host和重试，再通知各等待者；迟到回调不能释放新owner或重开旧房间。

## 自动化与审核

最终源码 `0364c9b9b704641b463740b8997afcfcb55cb091`：定向8项通过；完整 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline` 通过（1m49s），728项测试，0失败/错误/跳过；Lint 0 errors / 78 warnings。

确定性回归覆盖系统已删GO、矛盾查询、探测超时只重读、旧查询/创建回调隔离，以及重叠恢复与停止的释放通知。固定 base `a2a9f736d2096c2ea0b9de0b003e72f921d66619` → head `0364c9b9b704641b463740b8997afcfcb55cb091` 只读架构审核 **APPROVED，无P0/P1**。初审发现查询超时后又尝试删除的边界，已修正并增加“删除调用次数不增加”的断言。

[Draft PR #33](https://github.com/g95809080-cmyk/moto-intercom/pull/33) 基于 KUM57 修复分支；[CI 35485357724](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/35485357724) 全部通过：JVM 测试、Lint、APK 构建及 Android API 36 instrumentation tests 均成功。未合并或发布。

## 修复包实机验证

小米13覆盖安装最终debug包，保留数据。进程PID始终为31034，连续创建三个房间、结束三个房间，中间两次同进程重新创建均成功。每次重建前系统均确认 `InactiveState / groupFormed=false`；新房间均显示“房间已创建，等待成员加入”。最终结束返回创建/加入页。

上述实机验证的是正常结束后的连续重建；“系统先删除GO、应用删除失败”的精确故障排序由确定性回归覆盖，本轮没有将该排序写成修复包实机注入测试通过。

安装包 `app/build/outputs/apk/debug/app-debug.apk`，SHA256 `63ff858ff184a7e845597848237c868dd8e99fe8c4e23c22cfafb72aba6eaa61`。另外两台成员未连接ADB，此次清理修复只安装到房主。

原始系统日志保留在忽略目录，仅用上述与应用故障相关的摘要作为交付证据，不提交系统日志里的网络凭据。
