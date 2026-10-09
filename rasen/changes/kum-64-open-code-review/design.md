## Scope

稳定基线 e958fff；main 0f5e7b7。官方 OCR 1.12.13/fabbdb29 的 scan --preview 与 delegate rule 不调用未配置 LLM。主 Agent 唯一写入；独立只读审查分别覆盖稳定传输与 main 群组/iOS。

## Evidence

每个扫描条目记录 reviewed 或具体 skipped 理由，按 path/status 记账。每项发现附精确行号、触发顺序、修复和回归。历史报告作为线索，重新核对固定源码；实机效果与自动化分开。

## Git

每项一分支/一 PR，依赖顺序明确，所有行为修改绑定独立只读架构 review 的固定 SHA。不 force push、不自动 merge。原有文档与已部署包保留。
