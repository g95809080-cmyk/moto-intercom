## Why

用户明确要求将当前四人1.3版移到独立分支，并以后使用2.x。必须修改实际APK版本，不能只更名Release标题。

## What Changes

从历史四人0f5e7b7建立的four-person发起独立PR，version.properties改为2.0.0/code8，获批功能后缀仍aee3b3f。同步两线版本规则、README、Android CI分支范围；不改app/Gradle/iOS。既有四人Release先备份元数据与资产，然后迁到新标签/正确安装包。

## Validation

源码与旧标签一致；完整JVM/Release Lint/Release构建、固定Head只读审查及CI；同证书非debuggable签名、版本、16KiB对齐、hash检查；正常four-person合并，迁移Release及远端字节核验。旧Git标签保留，用户本轮授权签名/发布及迁移，不由Rasen自动授权。
