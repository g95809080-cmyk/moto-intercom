# KUM-85 安装验证

2026-10-13已完成用户授权的小米13覆盖安装。Base为5b369c9ebf68e2c5fd988f206782f5ee54d79906，功能Source为f587472e251b59f3c25e8cad5e483c8e0afc887a，固定打包Head为8296e24958bd143edb405c0ccf638e652f39b092。小米13型号2211133C，Android16/API36，原版本1.2.2+bb0fa92/code6，安装后为1.2.3+f587472/code7。

本次不修改app/src/build/workflow，沿用获批Source门禁与固定CI（38239675696，两项均成功）；新增版本元数据及Release variant已重新构建、签名与核对。未重复运行远程CI。构建、包信息、安装、启动输出及截图放忽略目录logs/2026-10-13-xiaomi13-deploy，不提交用户原始诊断数据或私钥。

本地testDebugUnitTest/lintRelease/assembleRelease成功：96 suites、804 tests、0 failure/error/skip，Release Lint 0 error/71 warnings。与Base/Source相比app/构建/workflow/script无差异，只有version.properties和本单合同/证据。

只读打包审查：APPROVED，P0=0/P1=0，Base5b369c9、Head8296e24、Source f587472；允许在用户明确授权范围内覆盖安装与单机检查。8个变更路径全文审查，证书、APK hash、版本与非debuggable属性核对通过。16KiB对齐由主Agent的zipalign输出证明；审查未声称独立重跑该检查。本次没有新的产品行为修改。

签名安装包：1.2.3+f587472/code7，package com.kuma.motointercom，min23/target36，manifest无debuggable；v1/v2/v3签名与16KiB ZIP对齐均通过。证书SHA256为7F20F38DC1D7372CDE34CAC6E0E17D80EC995AC298C298FD0D24605E1A8070F3，与手机原包一致。APK SHA256为73B910336E1357F1180CB0D5F781EE8C9A0D1A06E7831CF7C6E34C1520240B15；安装文件output/MotoCom-v1.2.3-f587472-test.apk及其.sha256位于主工作区父目录。

adb install -r返回Success。安装前后user0的ceDataInode=8094856、deDataInode=11256679及firstInstallTime=2026-08-07 13:55:47相同，未清除应用数据。16项已授予权限前后完全一致，permissions-compare.json的changed为空；麦克风、附近WiFi、蓝牙、通知、电话状态权限均保留。versionCode7已实际安装分发，后续包须递增到至少8。

am start -W返回Status ok、COLD、TotalTime927ms、WaitTime932ms。启动后进程持续存活，目标进程crash buffer为空，启动日志未发现应用fatal。launch-after.png实际显示首页正常、原VOX灵敏度70；设备没有连接蓝牙或对端，用户尚未启动对讲，不能据此确认通话音频、VOX或自动重连修复效果。

诊断页面与重启后的历史日志检查尚未完成。安装前后uiautomator无法获得idle，临时UI dump helper被系统终止且未产出XML；这些失败不计为UI验收成功。仅凭数据目录保留不证明历史日志内容完整。KUM-85保留In Review，第5项未全部勾选；双机、普通蓝牙及道路验收继续由KUM82-84跟踪。本次未合并main、创建公开Release/tag或操作iOS。
