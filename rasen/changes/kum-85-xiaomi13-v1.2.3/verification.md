# KUM-85 安装验证

Base5b369c9；已审查功能Source f587472；当前为打包WIP，没有新APK批准或安装成功结论。现有小米13为1.2.2+bb0fa92/code6，非debuggable，型号2211133C/API36，麦克风/附近WiFi/蓝牙/通知/电话状态均已授权。旧包与历史个人分发证书一致。

本次不修改app/src/build/workflow，沿用获批Source门禁与固定CI；新增版本元数据和Release variant需重新构建、签名与打包核对。原始包/dump/构建输出放忽略logs/2026-10-13-xiaomi13-deploy，不提交用户原始诊断数据或私钥。

本地testDebugUnitTest/lintRelease/assembleRelease成功：96 suites、804 tests、0 failure/error/skip，Release Lint 0 error/71 warnings。与Base/Source相比app/构建/workflow/script无差异，只有version.properties和本单合同/证据。

已完成签名安装包核验，尚未安装：1.2.3+f587472/code7，package com.kuma.motointercom，min23/target36，manifest无debuggable；v1/v2/v3签名与16KiB ZIP对齐均通过。证书SHA256为7F20F38DC1D7372CDE34CAC6E0E17D80EC995AC298C298FD0D24605E1A8070F3，与手机现有包一致。APK SHA256为73B910336E1357F1180CB0D5F781EE8C9A0D1A06E7831CF7C6E34C1520240B15；文件output/MotoCom-v1.2.3-f587472-test.apk位于主工作区父目录。
