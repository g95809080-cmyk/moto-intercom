# KUM-61 Android 首轮界面验证

## 结论

Android 首轮改造完成，独立架构复核 APPROVED，无 P0/P1。源码审查与最后 APK 对应 `f2eaf7bffc88a38e60c0a25edee4cf36916d1fae`，后续提交只整理文档和证据。未合并或发布。

本轮依据当前实现梳理首页模式、群组操作与设置层次，忽略 Android 改版 PRD。保留服务事实来源、领域状态机、协议、音频资格和持久化边界。

## 自动检查

| 检查 | 源码 | 结果 |
|---|---|---|
| 全量 JVM 回归 | b4c496f | 734 项，零失败、错误、跳过 |
| 最后系统栏图标改动的相关回归 | f2eaf7b | 7 个类、158 项，零失败、错误、跳过 |
| Android Lint / Debug APK | 两次上述源码 | 均 BUILD SUCCESSFUL |
| DESIGN.md 官方 lint | 本轮设计文档 | 0 errors / 0 warnings |
| Premium strict audit | 本轮工程 | 0 findings；该扫描器不扫描 Kotlin，不能作为原生 UI 验收 |
| Rasen strict validation | kum-61-android-ui-refresh | 通过 |

相关回归覆盖房间码失败保留、权限等待、入房后清除输入、结束和移除确认、操作切换清除旧确认、搜索不展示邀请卡、服务观察者解绑与旧回调隔离、首页模式互斥，以及诊断折叠。

共享测试进程曾出现 Room/Robolectric SQLite 退出清理异常；使用随证据提交的 `isolated-tests.gradle` 按类隔离 JVM，未排除任何测试，未改生产配置。精确计数和构建日志见 [full-suite.json](full-suite.json)、[final-ui-suite.json](final-ui-suite.json) 及对应 build-tail 文件。

最终 APK SHA-256：`3cdb512d2a268d08147298cfbfd6828273d61d00dffe7c2e792a78df3c6d4cde`。

## 原生截图与操作证据

设备为本轮启动的 API 36 Android 模拟器。通过实际 Activity、ADB 与 UI 树操作检查首页进入群组、设置诊断展开、失败后保留六位掩码输入、拒绝麦克风权限后可重试；未用网络成功状态替代错误路径。

| 截图 | 说明 |
|---|---|
| [首页](native/home-idle.png) | 最终 APK，真实空闲首页，双人与四人入口 |
| [群组入口](native/group-ready.png) | 最终 APK，真实群组页，浅色背景系统图标可见 |
| [设置](native/settings-defaults.png)、[诊断展开](native/settings-diagnostics.png) | 系统栏修正前 APK，真实设置页面；最后改动不涉及该页面 |
| [搜索失败](native/group-search-failed-retains-code.png) | 系统栏修正前 APK，真实失败保留输入 |
| [权限拒绝](native/group-denied-retains-code.png) | 系统栏修正前 APK，真实权限错误及可重试输入 |
| [小屏大字](native/group-narrow-large-font.png)、[滚动后操作](native/group-narrow-actions.png) | 最终 APK，320×640 dp、字体 1.5 倍，内容可滚动 |
| [键盘](native/group-narrow-keyboard.png)、[键盘下操作可达](native/group-narrow-ime-actions.png) | 最终 APK，滚动后加入按钮完整位于键盘上方 |
| [房间展示样例](native/group-room-fixture.png)、[首页房间样例](native/home-group-fixture.png) | 最终 APK 的 debug 展示夹具，只说明布局，不代表真实入房或多机通信成功 |

检查后恢复模拟器尺寸、密度、字体与键盘设置。真实多机、蓝牙和听感尚未验收，本轮不宣称这些能力通过。
