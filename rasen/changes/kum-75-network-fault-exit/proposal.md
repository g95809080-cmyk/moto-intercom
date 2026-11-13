## Why

KUM-75 / OCR-APP-04：模拟器network speed/delay失败未检查退出码，仍可继续并输出APPLIED，留下虚假验证结果。缺少Mode也会无动作返回APPLIED。

## What Changes

共用helper逐条检查speed/delay原生命令；任何失败立即throw，不继续后续命令或发布APPLIED。Mode成为必填，保留emulator-only及接口/root校验。实际Windows PowerShell子进程与native mock ADB验证34种成功/失败/参数边界，并加入Windows CI job。

## Impact

独立main脚本修复，不改Android/iOS产品源代码、版本或设备状态；scenario既有finally恢复合同不变。
