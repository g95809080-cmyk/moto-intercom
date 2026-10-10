## Source and authorization

Base b7f717bd9f4c635ddd442185fb8335e0a35928cd；实际应用源码 d095e03e954f33c7674f1cb6553029078f17bf94，APPROVED/P0=0/P1=0及CI37925795827已核验。用户2026-10-10的明确部署指令授权本次本地证书使用及无线安装；遵守该授权，不将Rasen自动化禁止部署解释为需要重复询问。

## Package

version.properties作为唯一版本来源：1.2.2/code6/approvedCommit=d095e03。app、buildSrc、依赖及工作流与批准源码相同，复用712项JVM及原生/CI证据；实际Release构建与Lint独立执行。使用既有证书签名非调试变体，签名SHA256必须等于7f20f38dc1d7372cde34cac6e0e17d80ec995ac298c298fd0d24605e1a8070f3。

## Device and evidence

adb install -r，不卸载或清数据。保存安装前APK、package/appops和日志，核对升级后的实际APK hash、versionCode、证书、uid/firstInstallTime/权限与启动。可访问UI时检查原配对/设置及七天日志页面/导出入口；不可访问的部分如实记录，不执行会重建数据库的测试runner。结束切回main，保留独立Draft PR与本机安装包。
