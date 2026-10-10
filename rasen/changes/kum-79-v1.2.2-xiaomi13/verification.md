# 小米13修复测试版部署记录

2026-10-10 已按用户明确授权，经无线 ADB `192.168.1.62:37447` 覆盖安装到 Xiaomi 13（2211133C / Android16）。结果 **Success**，应用冷启动 **Status: ok**。仅部署 Android 稳定维护线，蓝牙听音、风噪及双手机验证按用户安排暂缓。

## 版本和来源

| 项目 | 结果 |
| --- | --- |
| 安装前 | 1.2.1+052f419 / versionCode5 |
| 安装后 | **1.2.2+bb0fa92 / versionCode6** |
| 批准应用源码 | bb0fa92ee7326844891967981355ccf93c9c8639 |
| 实际构建提交 | 7338a7a33a4f3dc694c3af83e14fd9b4fbb9b30a |
| APK | MotoCom-1.2.2+bb0fa92.apk，55,053,290 bytes |
| 构建包和手机拉回包 SHA256 | 12e67949ed42f1574ae1054ab72c47da06bf77dd4ffa290f8c4e0419158d07bd |
| 沿用证书 SHA256 | 7f20f38dc1d7372cde34cac6e0e17d80ec995ac298c298fd0d24605e1a8070f3 |
| 非调试属性 | Release APK，aapt 未报告 application-debuggable，签名验证成功 |

该源码包含 KUM65–71 稳定修复和 KUM80 两处关闭快照回补，独立只读架构审查 APPROVED/P0=0/P1=0。本项的 app、buildSrc、Gradle、依赖和工作流与批准源码相同；只修改版本与部署证据。未引入1.3群组或iOS，未合并main或创建正式Release。后续证据提交仅修改文档；实际构建提交保留在部署分支祖先历史中，不改写其身份。

## 构建与基础验证

固定源码完整 714 JVM 通过，Debug Lint 0 Error/Fatal、70 Warning，Debug/AndroidTest APK 通过。固定源码 [Android CI38012858535](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38012858535) 两个 job 均 success；API36原生报告27项、0 failure/error，2项条件跳过（双机NSD/合成音频网络交换），25项实际执行通过。CI运行于模拟器，没有在用户手机运行会清除测试数据库的 instrumentation runner。

最终构建 `assembleRelease lintRelease` **BUILD SUCCESSFUL in 1m 58s**；Release Lint 0 Error/Fatal、71 Warning。签名、版本、非调试和校验值门禁通过后，执行 `adb install -r`，没有卸载或清数据。

安装前后 appId 都为10577，firstInstallTime 都为2026-08-07 13:55:47。通知、附近Wi-Fi、蓝牙连接、电话状态及麦克风五项已有运行时授权均保留。界面确认 VOX灵敏度30和自动重连开启，与安装前观察一致。

升级后日志页读到 `2026-10-08T03:21:37.183Z`、标注旧版 `1.2.1+052f419` 的历史记录，显示本机近7天保留和最近300条预览。点击“导出近7天日志”成功创建诊断文件并进入系统 ChooserActivity，诊断 FileProvider URI及读授权在系统活动记录中可见；随后取消分享，没有发送给任何人。界面可用，检查期间应用进程存活，应用PID日志未见 FATAL EXCEPTION、Fatal signal 或 ANR。

UI自动化的 idle dump 两次失败，没有得到安装前设置XML；设置与日志验证使用实机截图、package/appops、活动记录及拉回APK。不能把失败的XML检查算作通过。蓝牙音频共存/自动路由、实际风噪及双方发现/重连没有新增实测结果；安装和日志检查不能替代这些场景。

## 证据与Git

本机证据目录：`logs/2026-10-10-xiaomi13-deploy/`。保存安装前后APK、包/授权状态、构建/Lint/签名报告、CI原始结果、有效界面截图、导出入口活动记录和应用PID日志。APK及手机原始诊断内容保存在本机，不提交到Git。

KUM80独立 [Draft PR #52](https://github.com/g95809080-cmyk/moto-intercom/pull/52) 基于 KUM71；本项独立分支 `test/kum-79-v1.2.2-xiaomi13` 基于KUM80，单独提交版本与部署记录。收尾返回常驻main，保留用户两份未跟踪产品文档。
