# KUM-72 固定提交审查与验证

Base SHA `0f5e7b778ee0b945725c9506055d6137a7faf546`；源码/测试 Head SHA `ba4550d7410b2a4b070cad80213d1724381ffd29`。

独立只读 motointercom-product-architect / OCR：APPROVED，P0=0、P1=0、findings=[]。Next gate allowed：独立main Draft PR、CI和下一独立KUM-75检查点。Non-blocking：远端CI待执行；实机BLE和双手机按用户要求deferred。In Review不是Done/合并/发布。

完整acquire monitor包含真实时钟读取、倒退检查、窗口清理、全局/单地址额度及记账。Socket I/O在锁外；Legacy双accept、GroupSocketHost与BLE ingress均追读，未发现反向锁调用。末尾注入时钟参数默认仍为原groupNowMs。

两个最后额度测试核对实际budget monitor/owner；真实server测试调用start、双TCP HELLO/CONNECT_REQUEST/BUSY/EOF，验证同地址跨端口3次、下一60秒窗口、两线程存活及close后退出。暂移除同步运行相同14项测试，恰好新增3项失败（两个旧时间IllegalArgumentException与真实server响应超时）；finally按原字节恢复后提交固定源码。修复定向14项通过1m4s；完整门禁94 suites/731 tests，failure/error/skipped=0；Lint0 errors/80原有warnings；Debug与AndroidTestAPKs2m57s成功。

同源码API36 runner `OK(21 tests)` /2.546s，精确状态19通过、2双机assumption、0失败。主分支native清单与维护线不同；根的早期数量/宽关键词结果校验误报已按runner精确状态纠正，没有重复运行或把误报当产品失败。单模拟器和loopback不构成实机射频/群组听音验收。没有修改版本或操作用户手机。

官方OCR9项选中3项全审，默认排除的2个unit tests及4份Markdown补审；9 reviewed、0 skipped，全文及实际producer/consumer深度见coverage.json。


## 远端 CI 最终记录

[CI](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37927499728) 已完成success，全部2个job成功。审查源码 `ba4550d7410b2a4b070cad80213d1724381ffd29`；实际CI head `0d02622557797c8f27a9f1292e1fe5d725efbda3`。实际CI head与审查源码仅Rasen审查元数据不同，已用Git逐路径核对，无产品、工具、构建或工作流差异。精确响应、job结果及差异路径见ci.json。

Draft PR #47和Linear保持InReview；实机蓝牙、多应用听音、双手机按用户要求deferred，iOS排除。未合并、改版本、发布或部署。
