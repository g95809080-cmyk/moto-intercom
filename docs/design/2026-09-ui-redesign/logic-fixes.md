# 三处 UI 逻辑问题修复

本轮范围：此前 logic-review.md 的三处问题。保留固定底部三栏；未修改 Service、连接协议、音频引擎或配对规则。

1. 设置页的状态展示传入真实 `audioReady`。音频尚未就绪、就绪、再次失去就绪时，设置正文、设备摘要和通道信息均随真实状态变化。
2. 首页音频快捷入口在设置分组完成布局后，一次性滚动到“音频输出”，并记录位置；普通底部页签继续恢复各页滚动与昵称草稿。定位使用 Compose 内容坐标，Android ScrollView 执行滚动，不依赖固定像素猜测。
3. 手机和中等宽度下点击车友头像/名称，会打开车友信息快照。离线配对车友没有运行会话 ID 时也可查看。弹窗不会自动连接；连接仍由列表中的明确按钮发起。大屏保留原详情栏。返回、离开页面、收到来电确认时清理弹窗。

新增回归覆盖音频就绪 false/true/false/true、音频入口及后续切页滚动、手机详情不连接且返回关闭、离线无 sessionId 详情及清理。模拟器实际音频定位截图见 [音频输出定位](screenshots/logic-fixed-audio-section.png)。

## 最终验证与手机交付

- 全量 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain`：BUILD SUCCESSFUL，591 tests、0 failures、0 errors、0 skipped。MainScreen 类 80 项通过。Lint 0 errors、69 warnings。见 [构建日志](logic-fixes-build.log)。
- 独立产品架构审查 APPROVED，无 P0/P1。
- 真机型号 2211133C，通过用户提供的已配对无线 ADB 连接传输，并执行 `install -r`，结果 Success；未卸载或清除应用数据。
- 手机下载目录保留 `/sdcard/Download/MotoCom-UI-fixes-20260905.apk`。传输文件 SHA-256 与本地 APK 一致：`E95AA3E53817EB967130D4D8B6D58E225A64A03F47DC4D28DD9D306099E91020`。
- 真机启动 `.MainActivity`：Status ok，冷启动 859 ms，并确认应用进程存在。真机检查为安装/启动冒烟；双机蓝牙通话与主观听感未在本轮验证。
