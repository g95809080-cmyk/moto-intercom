## ADDED Requirements

### Requirement: 可核对的代码审查覆盖
审查 SHALL 使用固定版本官方 OCR 的全文件范围和规则匹配结果，对每个可审查条目登记 reviewed 或明确 skipped 理由，并报告覆盖计数。

#### Scenario: 稳定线与 main 范围不同
- **WHEN** 稳定线不包含 v1.3 的群组功能
- **THEN** 审查 MUST 保留两份固定 SHA 清单，不把稳定线当成已覆盖 main 全部代码

### Requirement: 独立修复与真实验收
确认的缺陷 SHALL 以独立 Linear/Rasen/branch/PR 修复，行为更改通过固定 SHA 只读架构审核和相称的自动化验证。

#### Scenario: 自动化无法证明实际头盔听感
- **WHEN** JVM 或模拟器仅验证状态和回调
- **THEN** 报告 MUST 分开记录未执行的真实听音、降噪和多应用媒体共存验证
