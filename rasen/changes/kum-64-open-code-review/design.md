## Scope

稳定基线 e958fff；main 0f5e7b7。官方 OCR 1.12.13/fabbdb29 的 scan --preview 与 delegate rule 不调用未配置 LLM。主 Agent 唯一写入；独立只读审查覆盖 Android 音频、发现/连接/恢复、群组、诊断、平台及验收工具。用户明确要求跳过 iOS：ios/**、iOS CI、版本与发布均不纳入本轮；原有分支只保留，不继续执行。

## Evidence

每个扫描条目记录 reviewed、具体 skipped 或 user_excluded 理由，按 path/status 记账。每项发现附固定源码、精确行号、触发顺序、修复和回归。历史报告作为线索，重新核对固定源码；实机效果与自动化分开。coverage.json 保留全文、完整差异/上下文、精确 blob 复用和额外上下文的实际深度，100% 归账不等于全仓逐行阅读。

父任务的 findings.json、ci.json、report.md 汇总独立修复，不复制或改写子任务的架构批准。CI 的真实 headSha 与审查 SHA 分开记录；只有 Git 验证差异仅为 Rasen 元数据时才复用源码批准。设备听音和双手机验证按用户要求暂缓，状态保留 In Review。

## Git

每项一分支/一 PR，依赖顺序明确，所有行为修改绑定独立只读架构 review 的固定 SHA。不 force push、不自动 merge。原有文档与已部署包保留。
