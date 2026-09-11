# 固定底部三栏导航

按用户后续反馈实现：去掉首页右上角菜单和设置入口，在首页、发现车友、设置和日志页共用底部导航。导航位于页面滚动容器外，滚动内容时保持可见。当前页以浅绿背景、深绿图标与文字高亮，同时提供无障碍选中状态。

日志是设置的二级页面，底部高亮设置，页面返回及系统返回均回到设置。再次点击当前页签不重建页面。切换页签保存未提交昵称与各页滚动位置。大屏使用同一底部三栏并保留既有详情区域。

原抽屉和侧栏的隐藏布局暂留，已无顶部入口；不影响页面的实际导航。Service、连接选择、音频引擎和配对协议未修改。上一份逻辑检查中的设置音频就绪回显、手机车友卡片点击无反馈仍是独立待修项。

后续更新：上述待修项及音频入口定位已在下一轮完成，见 [三处逻辑修复](logic-fixes.md)。

## 截图与交互

- [首页](screenshots/bottom-nav-home.png)：右上角入口移除。
- [发现车友](screenshots/bottom-nav-discover.png)：底部持续显示，发现高亮。
- [设置](screenshots/bottom-nav-settings.png)：底部持续显示，设置高亮。
- [日志](screenshots/bottom-nav-logs.png)：仍保留三栏，归属设置。
- [320dp / 130% 字体](screenshots/bottom-nav-small-font130.png)：三栏完整、可点击；页面内容支持滚动。

API 36 模拟器实际执行：首页 → 发现 → 设置 → 滚动进入日志，另在小屏大字体下连续切换三栏。自动回归覆盖所有页面选中态、日志返回、360–1200dp 尺寸、大字体、当前页重复点击不重建，以及音频入口后的昵称草稿与滚动恢复。

另实际输入未保存昵称 NavDraft，滚动设置，再通过底部页签往返发现/设置，确认位置保留：[切换前](screenshots/bottom-nav-scroll-before.png)、[返回后](screenshots/bottom-nav-scroll-after.png)。

## 最终自动验证

`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain`：BUILD SUCCESSFUL，587 项测试全部通过，Lint 0 错误、69 警告。AndroidTest APK 仅构建，本轮未运行完整设备 instrumentation 套件。见 [构建日志](bottom-navigation-build.log)。

最初遇到的旧菜单测试调用和 API 23 padding 兼容问题均已修正。新增滚动测试在每次切页等待 Compose 完成布局后验证，与实际交互一致。

最新 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；SHA-256：`FA12E5DF746A98E378DA61B69111397A23BE8A12CD070807F0C8220DC1C781A3`。此前 verification.md 中的 APK 哈希与 586 项测试是首次视觉改版的历史结果。

本轮只读产品架构审查：APPROVED，无 P0/P1；允许导航变更进入最终汇总。该结论仅适用于本轮导航范围，不表示此前独立逻辑问题已解决。
