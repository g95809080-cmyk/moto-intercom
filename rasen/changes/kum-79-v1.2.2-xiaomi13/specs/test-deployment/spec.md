## ADDED Requirements

### Requirement: 可追溯的稳定线测试部署
测试包 SHALL 使用已批准稳定线源码及严格递增versionCode，沿用现有签名覆盖安装并保留应用数据。

#### Scenario: 已安装1.2.1/code5
- **WHEN** 用户明确授权部署已审查修复
- **THEN** 部署 SHALL 为1.2.2+bb0fa92/code6非调试测试包，实际APK hash与构建包一致，不带入1.3群组或iOS

### Requirement: 证据不能扩大
部署证据 SHALL 区分包安装/启动/日志基础检查与实际骑行听音。

#### Scenario: 只有一台手机
- **WHEN** 没有第二台手机或蓝牙听音条件
- **THEN** 这些场景 MUST 保持not run，不因安装成功标记已通过
