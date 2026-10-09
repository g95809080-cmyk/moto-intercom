## Why

KUM-71 / OCR-APP-03：导出目录枚举失败被当成空目录，第二次失败会把已有文件计费为0，绕过512MiB总预算。

## What Changes

两次枚举统一检查失败；无法完整读取目录时通过现有 Result.failure 返回，不创建新导出。TTL 清理和旧分享保留合同不变。同一诊断 worker 恢复后继续核算已有文件。

## Impact

基于KUM-70维护链的独立修复。只改 DiagnosticLogSession 与必要故障回归，不改变七天保留、版本、音频、网络或部署。
