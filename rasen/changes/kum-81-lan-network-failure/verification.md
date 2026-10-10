# KUM-81 验证记录

## 固定源与审查

- Base：`1ac1e3eb42a36d5ae5557bff767f2f2374aa1072`，已部署稳定线 KUM-79。
- 行为提交：`907f2c388e96c11d461a9c869310ddd36e6d41a4`。
- 审查 Head：`d2827ec1b11d923a3559a8b5bd52b4dd65116fd1`；与行为提交只差两个诊断 Scenario 和 tasks 第2项。
- 独立只读 `motointercom-product-architect`：**APPROVED / P0=0 / P1=0 / findings=[]**。Next gate allowed：独立 Draft PR 与固定 SHA Android CI。
- 8/8 reviewed、0 skipped；7项全文，Service为完整差异、完整受影响函数和归属/admission上下文，没有计为 Service 全文。精确 blob 和深度见 `coverage.json`。
- 官方 Open Code Review 1.12.13 delegate preview/rule：默认3项，另外5项补审；已应用规则，不声称远端 OCR LLM 服务审查。追读未变更的 actor targetedTransportOpenFailed、attempt/terminal/fallback 与 Service 最终归属检查。

## 红 / 绿证据

`logs/2026-10-11-lan-connect/` 保存本地原始数据，手机原始日志未提交 Git。

- `baseline-red.xml`：原生产源码、成功编译的5方法 × SDK28/35，10项红；缺 FAILED 终态、缺 Wi-Fi binding、旧错误污染替换后的 UI。测试中的 TCP refusal 不是用户手机 EHOSTUNREACH 的物理复现。
- `claim-race-red.xml`：发现回调已通过初检、阻塞于 registry；原 worker 此时失败，初版仍开第二个 socket，2项 expected1/actual2。Head的短锁最终 claim修复该顺序。
- `final-lan-green.xml`：11方法 × SDK28/35，22项通过，涵盖默认移动网络/local-only Wi-Fi、无匹配/VPN、提交/TCP/HELLO失败、发现重投、排队和执行中的迟到、stop、既有双计划。
- `full-gate.txt`：完整 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`，93 suites/736 tests，0 failure/error/skipped；Lint0 errors/70 warnings，双APK成功。
- `rasen-validation.json`：strict valid=true/issues=[]。

测试使用实际 Socket、后台 worker、pending lease 和 Service；Android Network.bindSocket 为 shadow。未改变协议/身份校验/目标锁/总截止时间、iOS、群组、版本或数据库。

本地 Debug APK SHA256：`9efde33fd533e3f4e12222de280cc0181c06028a84d3abc43daa374d8fbb5b90`；AndroidTest APK：`24ed5841fd4d5645e5a68ff8118420a90f8e149fc67ef1b034dbfa3c68b7a359`。这些是构建验证产物，未部署。

## GitHub

[Draft PR #54](https://github.com/g95809080-cmyk/moto-intercom/pull/54)，base `test/kum-79-v1.2.2-xiaomi13`，head `fix/kum-81-lan-network-failure`。Android CI [38073584716](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38073584716) 固定 `d2827ec1b11d923a3559a8b5bd52b4dd65116fd1`，两个 job 均 success：完整 JVM/Lint/Debug/AndroidTest APK，以及 API 36 instrumentation。

API 36 XML：16 suites、27 tests、0 failures/errors、2 skipped，实际执行通过25项。`SharedNetworkNsdTest.exchange` 和 `SyntheticAudioNetworkTest.exchange` 需要 `-e role=server|client` 双设备运行参数；本次单模拟器 CI 未传入，按前置条件跳过。没有把这两项记录为通过，也没有把 CI 当作真机 LAN 互通验收。

原始 CI 状态、日志和测试报告保存在 `logs/2026-10-11-lan-connect/`。后续证据提交仅增补 Rasen 文档，与审查/CI Head 的应用源码、测试和构建配置无差异；CI 绑定上述固定 SHA。

## 现场限制

用户确认同版、同路由器 Wi-Fi、两端均启动，当前不方便 ADB。发现成功不等于 TCP 可达；源 IP 已是 Wi-Fi IP，因此不把默认选路误判为已确认根因。对端 listener、两端系统路由和物理互通仍待设备复测，本轮没有宣称连接已恢复。
