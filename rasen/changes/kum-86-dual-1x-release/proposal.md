## Why

用户要求发布现有双人测试包，并将双人1.x与四人2.x分开维护。原main是四人版，不能只改Release标题而留下错误主线。

## What Changes

先保存旧main至four-person。双人发布分支以190055b为父，使用明确的ours合并保留旧main的0f5e7b7父历史，产品树取已审查双人源码。仅修改中文README、版本约定、Android CI分支范围和本单证据，APK原样复用KUM-85包。

## Validation

app/Gradle与f587472树一致；只读固定Head审查、PR CI、正常main合并；GitHub预发布原包/hash核验。用户当前请求已授权为完成该目标所需main合并与发布，不由Rasen自动授权。物理验收继续保留未完成。
