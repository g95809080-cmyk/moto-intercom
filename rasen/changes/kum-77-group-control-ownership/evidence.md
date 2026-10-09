# KUM-77 Android 验证

Base：`170e7ff4349c9390dac774a944bc9aebab3c6360`。最终源码：`1f636f787b7ee1b62c834bf642a14823fff560fb`。独立增量 [Draft PR #50](https://github.com/g95809080-cmyk/moto-intercom/pull/50) 以 KUM-76 分支为 base。

## 固定源码本地验证

`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest --console=plain`：BUILD SUCCESSFUL，2m。Windows JDK `F:/Android/jbr`，API36 `emulator-5554`。

实际 XML：886 JVM 项、114 suites，failure/error/skip=0。Lint 0 errors/79 warnings；Debug/AndroidTest APK 成功。原生实际 32 testcase：30 pass、2 assumption skip、0 failure/error；skip 为 SharedNetworkNsdTest.exchange、SyntheticAudioNetworkTest.exchange，缺双设备角色。

原生 XML SHA256：`e9c0f203f593fd1222bb6c2e07a6f2ac000dedae749fce7b0ce65ac59c1b1e45`。日志 `logs/kum64-open-review/kum77-final-full-gate.log`，摘要 `kum77-local-final-summary.json`，原生 `kum77-native-final.xml`。空 skipped 元素按 XPath 存在性计数，不用 PowerShell truthiness，也不采用 stdout Finished34 作为通过数。

## 反证及清理

原生产 Base 配合当时完整定向夹具，17项中10项失败；实际 worker/onClosed 交错使两种 stop 在真实生产 CHM.toList 堆栈抛 NoSuchElementException，构造/字段发布窗口没有收到 late channel 的真实 close；其他反例命中迟到安装、队列遗留和无恢复终点。实际日志/XML：kum77-old-source-red.log、kum77-old-runtime-red.xml、kum77-old-transport-red.xml。最终更严格的双向收据、handshake绑定及Service结束屏障均在完整绿色中验证。

中间工作区完整验证曾886项/1失败，Home捕获前一GroupService suite未完成的Cancelled Room IO；该suite在Home之前仅约16ms结束，旧fixture只destroy不等待Job。无法从pointer88唯一指认7个实例中的哪一个。仅fixture补自有Job完成和原Room实例关闭，保留Home断言和生产Service。

`b9f5ac7` 的19项定向有6项同栈失败，健康B双向检查在PAUSED Main等待Roster之前没有消费Host Signal。两位只读复审REQUEST CHANGES；以真实当前handshake解密Signal收据推进Main后，再等待新publication的Roster和双方exact lease状态。修正后27项定向（11 Runtime、6 Socket、8 Service、2 Home）及完整886项均通过。保留 kum77-refined-receipts-failed.xml 与 kum77-final-targeted-cleanup.log，未以重跑掩盖确定问题。

## 远端与边界

[CI 37961390317](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/37961390317) 通过已有 workflow_dispatch 触发，headSha精确为上述最终源码；完整运行 success，Windows脚本、JVM/Lint/双APK、API36原生三个job均success。保存实际最终查询为 `logs/kum64-open-review/kum77-ci-final-status.json`。现有PR自动触发器仅覆盖main，本次未为分支改变CI策略。

三位独立只读复审均 APPROVED，P0=0、P1=0；具体 fixed SHA、逐路径归账和下一门禁见 review.md、coverage.json。Draft PR 保持待审，Linear 保持 InReview。

实际控制Socket/JPAKE/AEAD、Runtime队列/Main、writer、关闭/密钥清零及健康成员状态属于本次证据；Android无线引导被fixture接管，不证明P2P射频。普通蓝牙、多应用听音、Android9/16双机和风噪按用户要求暂缓；iOS排除，未改版本、合并、发布或部署。
