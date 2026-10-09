## ADDED Requirements

### Requirement: 可核对的代码审查覆盖
审查 SHALL 使用固定版本官方 OCR 的全文件范围和规则匹配结果，对每个可审查条目登记 reviewed 或明确 skipped 理由，并报告覆盖计数。

#### Scenario: 稳定线与 main 范围不同
- **WHEN** 稳定线不包含 v1.3 的群组功能
- **THEN** 审查 MUST 保留两份固定 SHA 清单，不把稳定线当成已覆盖 main 全部代码

#### Scenario: 用户排除 iOS
- **WHEN** 用户明确要求跳过 iOS
- **THEN** ios/** 和 iOS CI MUST 标记 user_excluded，覆盖计数保留该原因，不继续 iOS 修复、CI、版本或发布

### Requirement: 独立修复与真实验收
确认的缺陷 SHALL 以独立 Linear/Rasen/branch/PR 修复，行为更改通过固定 SHA 只读架构审核和相称的自动化验证。

#### Scenario: 自动化无法证明实际头盔听感
- **WHEN** JVM 或模拟器仅验证状态和回调
- **THEN** 报告 MUST 分开记录未执行的真实听音、降噪和多应用媒体共存验证

#### Scenario: CI 在审查元数据提交运行
- **WHEN** CI 的 headSha 与批准源码不同
- **THEN** 报告 MUST 记录两个完整 SHA，并以 Git 逐路径核对差异；只有 Rasen 元数据差异才复用源码批准
