# KUM58 设计
Base a2a9f73。现场系统已 Inactive，应用仍持有 GO lease 并每秒 removeGroup；失败回复不会证明网络仍存在，不能只重复删除。

关闭期间仍由旧 adapter/lease 独占清理。createPending 未结束绝不释放。removeGroup 失败后发起当前 channel 的 requestGroupInfo + requestConnectionInfo，只有 group=null 且 connectionInfo 明确 groupFormed=false 才确认网络已消失。查询缺回调/异常/互相矛盾保留 UNKNOWN；每次查询携带代次，关闭或失去lease后的回调无权修改新owner。查询超时只允许重试读取，不把超时当作释放证据。removeGroup 成功继续作为确认释放依据。

GroupNetworkRuntime 合并同一旧host的并发释放等待者，只维护一条清理重试链；防止恢复与用户停止叠加两条循环。完成时每个等待者自检其attempt，迟到恢复不能再建房。

用户在三人退桌面与熄屏仍可互听后明确要求暂缓后台掉线追查。本轮只修复已消失网络的释放与重建，不改权限、OEM设置或后台策略，不宣称后台触发原因已解决。

验证：外部先删GO→remove失败→读取证实不存在→释放→同进程可重建；group仍在/信息不一致/查询缺回调/迟到create/removal/query不得越权释放；重叠恢复/停止仅一条清理链。完整JVM/lint/APK/API36；小米13后台与重建实测，成员听音由用户确认。
