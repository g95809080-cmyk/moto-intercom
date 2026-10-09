# kum-78-acceptance-evidence

## Why

修复 Android 开发验收脚本误报成功：仅接受协议终态；按真实 DataStore device_id 和系统属性确认两台不同设备；采集失败时当前结果失效，历史证据独立保留。只使用 loopback/mock 回归，不操作实机、iOS、版本或部署。

## What Changes

协议CLI限制实际终态；双设备工具补native退出码、protobuf身份和物理属性检查、数据库完整性及独立采集历史。Windows CI运行真实CLI/mock回归。APK、音频产品逻辑、版本和设备安装不变。

## Impact

受影响文件仅 tools/kum26_peer.py、tools/kum26_peer_test.py、tools/kum26_two_device_evidence.ps1、新回归及 Android CI。Stop日志位于独立 captures/<GUID>/，根scenario-result.txt为当前判定，根summary.md为中性索引；旧格式目录需使用本版工具重新Start。本项依赖KUM-77固定源码与CI绿色，独立分支/增量Draft PR。
