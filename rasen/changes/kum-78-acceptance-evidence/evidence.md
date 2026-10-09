# KUM-78 Android 开发工具验证

Base `b596feac30f9079d89d01e6f723de7f015011006`，固定源码 `018b6827bce93c928d3ce7883d3230456a3dfcb8`。

实际 Windows PowerShell 5.1 CLI 经外部 mock ADB：45 case 全通过，154.7秒；包含不同 USB 安装、昵称UUID在前、缺/重复/截断/错误类型device_id、模拟器/TCP qemu、缺属性、有效stdout但exit17、完整Room schema损坏无关表的non-ok integrity、无线端口变化、重复Stop早/中/末失败、真实摘要写入失败及旧快照文件hash不变。8项重复Stop定向也通过。所有临时目录删除前验证为自有绝对路径，环境变量恢复；没有调用实机ADB。

Python loopback/真实 CLI subprocess：14 tests 全通过，包含3种默认和精确终态、5种错误第二帧及终态不匹配；原 Windows 故障注入回归34 case全通过。实际日志：`logs/kum64-open-review/kum78-evidence-final.log`、`kum78-peer-final.log`、`kum78-network-fault-regression.log`、`kum78-repeated-targeted.log`。

原始 main `0f5e7b778ee0b945725c9506055d6137a7faf546` 两脚本经同一真实驱动执行：5种非终态第二帧实际exit0，原same-serial Start实际exit0，先完成真实Pass后中途ADB非零导致旧当前Pass仍保留。实际红日志 `kum78-old-peer-red.log`、`kum78-old-same-serial-red.log`、`kum78-old-prior-pass-red.log`，并非模拟预期断言。昵称UUID确实被旧脚本误取。新版本非零且当前NotRun/capture_failed。

首轮完整Windows执行前34case通过，但恢复Stop时File.Replace报“无法移除被替换文件”；日志保留`kum78-evidence-first-failure.log`。原子替换使用真实WinPS NullString，并针对该失败补对32/33/1175的有限重试，不删除当前文件作fallback。随后8项重复定向与45项全套通过。该异常的占用者没有被唯一定位，不声称已确认由某个进程造成。

Android产品代码没有变化：固定Head与KUM77源码 `1f636f787b7ee1b62c834bf642a14823fff560fb` 的整个app Git tree相同，均`4ee9ced9fdc65439a9a91f02d886bff517668757`。本地复用其886 JVM/114 suites全通过、Lint0errors/79warnings、双APK、API36实际32项=30pass+2双设备skip证据；新的固定Head远端Android CI另行执行。

官方OCR1.12.13：10 changed paths，5默认选中、5扩展手审；三位只读架构/OCR均APPROVED、P0/P1=0，逐路径阅读见coverage.json及review.md。

独立 [Draft PR #51](https://github.com/g95809080-cmyk/moto-intercom/pull/51) 以KUM77分支为base。[CI 37965905864](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37965905864) 已由现有workflow_dispatch触发，实际headSha精确等于本次源码；当前等待完整结果。Linear保留InReview，未将工具mock结果记为真实双机/蓝牙/无线验收。iOS排除，未改版本、合并、发布或部署。
