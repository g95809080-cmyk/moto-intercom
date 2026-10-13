## Why

用户要求把已批准并通过CI的三项修复安装到小米13。现有owned AVD debug包仍带旧1.2.2+bb0fa92/code6，不能用这个旧身份分发新功能源码。

## What Changes

- version.properties统一更新为1.2.3/code7/approvedCommit=f587472，修复测试编号保持patch。
- 不修改产品行为、app/src、协议、构建或workflow；功能来源固定到已审查Source。
- 用相同历史个人分发证书签名非debuggable Release APK，校验签名/版本/hash/对齐，覆盖安装并检查启动、权限、设置与日志留存。

## Validation

JVM、Release Lint与Release APK；固定打包Head只读核对Source范围及用户安装授权；实机覆盖安装、cold start、诊断页面与进程重启留存。真实双机/道路仍由原问题保留未验收。
