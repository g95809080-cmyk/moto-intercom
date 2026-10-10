# KUM-80 固定源码审查与验证

Base：`b7f717bd9f4c635ddd442185fb8335e0a35928cd`。
Head：`bb0fa92ee7326844891967981355ccf93c9c8639`。

## 源码和独立审查

只回补两处 pending Socket 集合的数组快照，避免实际 HELLO/ready worker 删除最后一个 lease 时，Collection.toList 的单元素迭代抛出 NoSuchElementException。两生产和两测试文件与 KUM-76 批准源码 `5b9b6a7d853446280403f20a1b0fed39f370308a` 的 blob 完全相同。锁边界、锁外 I/O、lease 归属及关闭顺序不变。

独立只读 `motointercom-product-architect` 审查结果：**APPROVED，P0=0，P1=0，findings=[]**。覆盖固定差异 9/9，skipped=0；官方 Open Code Review 默认选择 3 项，其余 6 项手动补审全文及差异。额外全文读 PendingSocketLease，Service/Tunnel 为上下文追读。官方 delegate preview/rule 原始结果保存在本机日志目录，未声称远端 OCR LLM 审查。

Next gate allowed：完整本地自动化成功后进入 KUM-79 版本元数据与打包复核；安装继续使用用户已提供的部署授权。源码批准不代表实机听音效果通过。

## 实际回归与本地门禁

旧稳定源码两个独立红回归均在真实生产 close 路径抛出 NoSuchElementException；使用真实 TCP HELLO、ready worker、lease.onReleased 和真实集合，不伪造 iterator。修复后两个完整类 LAN 5 项、Wi-Fi 8 项通过，共 13 项。原断言保留，覆盖 socket、listener、registry、executor、Service 登记及重复关闭。

固定 Head 执行 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`：BUILD SUCCESSFUL，3m 2s；92 suites / **714 tests，0 failures，0 errors，0 skipped**。Lint **0 Error/Fatal，70 Warning**。Debug 与 AndroidTest APK 构建成功。

证据位于 `logs/2026-10-10-xiaomi13-deploy/`：`kum80-old-lan-red.xml`、`kum80-old-wifi-red.xml`、`kum80-fixed-lan-green.xml`、`kum80-fixed-wifi-green.xml`、`kum80-full-gate.log`、`kum80-gate-summary.json`、`kum80-lint-debug.xml`、`kum80-ocr-preview.json`、`kum80-ocr-rules.json`。首次旧源命令仅执行了 LAN 一项，Wi-Fi 由另一条正确选中测试的命令独立复现，不混淆计数。

## CI 与实机边界

固定 Head 的 Android CI：[38012858535](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38012858535)，结果待收尾核验，不能提前计入通过。

音频生产路径及 AndroidTest tree 与 KUM-70/71 相同；本项仅沿用其明确未变的音频证据，不引用主线 886/32 计数。蓝牙混播、风噪及双手机验收仍按用户要求暂缓。没有 iOS、群组、版本、数据库或依赖修改。
