# KUM-76 Android 整合验证

固定输入main：`0f5e7b778ee0b945725c9506055d6137a7faf546`；最终源码：`5b9b6a7d853446280403f20a1b0fed39f370308a`。用户要求跳过iOS；本次不修改 `ios/` 和 `version.properties`。

## 最终本地自动化

Windows JDK `F:/Android/jbr`，既有 `M:/` 工作区映射，API36目标 `emulator-5554`。固定源码运行：

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest --console=plain
```

BUILD SUCCESSFUL，2m15s。实际XML：JVM874tests/113suites，failure/error/skip=0；Lint0errors/79warnings；Debug和AndroidTest APK通过。

原生XML根 `testsuites` 为 **32项：30pass、2assumption skip、0failure/error**，与32个实际testcase相符。stdout的Finished34不用于放大通过数。原生XML SHA256：`866709bce2cdb90559f7860af70b364b2e2f06a7a36d4abf7d2bc046a6ea9bc4`。

证据：`logs/kum64-open-review/kum76-fixed-release-final-gate.log`、`kum76-final-local-summary.json`、`kum76-native-final.xml`。两项skip是 `SharedNetworkNsdTest.exchange` 和 `SyntheticAudioNetworkTest.exchange`，缺双设备角色/目标，不能作为双机无线或听音验收。

## 确定性反证与原生释放

LAN和Wi-Fi Direct的新回归都使用实际accepted Socket、HELLO/ready worker和lease.onReleased，在size/mappingCount观察到最后一项时释放真实worker。旧源码两份XML都在真实生产 `toList()` 调用栈抛出 `NoSuchElementException`；修复后两个完整测试类13/13通过。保留实际Socket/session/listener/executor关闭、注册状态及重复关闭断言。

旧红证据：`kum76-snapshot-red-wifi.log/.xml`、`kum76-snapshot-red-lan.log/.xml`；修复类绿证据 `kum76-snapshot-green.log`，最终全量再次执行通过。

六类原生测试的13项全部通过。群组adapter使用已准入room夹具，验证实际GroupAudio→writer→room、六个PC和每成员独立实际输出/RTP，不证明无线认证。共享采集错误仍经当前operation的Failed结束群组，原全局平台owner空断言保留。每项fixture等自己的onDisposed、RTC终止、exact token、已观察实际生产线程退出及四平台字段清空；异常也先关闭其余自有engine。阻塞旧A的真实RTC清理时，A token不提前消失，健康B/C仍有实际PCM/RTP和token；放行后仅A token消失。

## 远端 CI 与失败记录

PR：[Draft #49](https://github.com/g95809080-cmyk/moto-intercom/pull/49)。最终源码CI：[37955528528](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37955528528) 已完成，headSha 精确等于 `5b9b6a7d853446280403f20a1b0fed39f370308a`；Windows 故障脚本、JVM/Lint/APK、API36 原生测试三个作业均 success。实际状态快照保存在 `kum76-ci-final-status.json`。

首次 `bb79b887` CI [37945318583](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37945318583)：Windows和API36作业绿；JVM一项SDK34 sandbox Maven下载TLS Connection reset，尚未进入测试；另一项LAN已完成即时资源关闭但actor仍Stopping。`44313de` 仅在原即时断言后等待实际Offline，未改deadline/状态。

随后 `44313de` CI [37949225378](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37949225378)：上述两项已通过，但Wi-Fi测试XML明确命中生产CHM.toList竞态。Native group fatal的两个真实释放回执已完成，global owner仍存在；日志确认上一fixture结束后旧SDK/RTC teardown仍运行。该次为JVM872项/1失败、native31项/28pass+1失败+2skip，不计入最终绿色证据。

失败artifact XML保存在 `kum76-ci-xml/`、`kum76-ci-artifacts174-native/`，失败日志 `kum76-ci-final-failed.log`。P1已重开、用旧源红回归确认后修复，未以重跑掩盖已确认生产问题。

## 审查与边界

三位固定SHA架构/OCR只读审查均APPROVED，P0/P1=0；来源、路径和真实阅读深度见 `review.md`、`coverage.json`。Rasen strict验证另行记录。

KUM-77/78仍待独立修复。普通蓝牙、Android9/16双手机、多应用耳机发声、降噪听感和骑行风噪按用户要求暂缓。上述证据不属于发布或部署验收。
