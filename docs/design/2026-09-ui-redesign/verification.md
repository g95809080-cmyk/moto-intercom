# UI 改版验证记录

日期：2026-09-05。验证对象：当前工作区 Debug 构建。未提交、合并或发布；保留工作区原有改动。

## 自动验证

执行命令：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain
```

- 最终结果：`BUILD SUCCESSFUL in 2m 11s`，见 [完整构建日志](verification-build.log)。
- JUnit XML 汇总：586 项测试，0 失败、0 错误、0 跳过。
- Lint：0 错误、71 警告。警告包括资源未使用、依赖版本、API 兼容性等；本次未做全项目警告清理。部分旧图标和尺寸在改版后不再引用，仍保留资源文件。
- Debug APK 和 AndroidTest APK 均构建成功；本次未运行设备端完整 instrumentation 测试套件。
- 涉及的源码、资源和测试文件通过 `git diff --check`。
- 新增回归：关闭系统动画时，Connected / Discovering 和雷达运行状态仍与业务状态一致，仅关闭视觉动画。
- 只读产品架构审查：APPROVED，无 P0 / P1。审查反馈中蓝牙状态展示、VOX 重复点击目标及系统动画回归测试均已落实。

安装包：`app/build/outputs/apk/debug/app-debug.apk`。

SHA-256：

```text
35AFD8F539B7A168BF93BFF0456B75448030F3629C05B1F2C8B755A91C19B199
```

## 模拟器交互与截图

环境：API 36，`MotoIntercom_Graphics_API36`，ADB UI 树定位控件，原始截图。

| 场景 | 检查与结果 | 截图 |
| --- | --- | --- |
| 手机 390 × 844 dp，字体 100% | 实际主页状态、主操作、音频状态和 VOX 信息清晰 | [主页](screenshots/home.png) |
| 已连接状态样本 | 对讲对象、蓝牙、连接事实、结束操作与 OPEN 高亮保持一致 | [已连接布局样本](screenshots/connected-preview.png) |
| 发现列表状态样本 | 已配对、附近与离线配对分组；名称和状态分行 | [发现布局样本](screenshots/discover-preview.png) |
| 实际设置与日志 | VOX、音频路由、设备信息、日志入口可达；终端面板可读 | [设置](screenshots/settings.png)、[日志](screenshots/logs.png) |
| 小屏 320 × 640 dp，字体 130% | 内容可滚动到主操作；VOX 三个英文状态完整显示，无拆词 | [首屏](screenshots/home-320-font130.png)、[滚动后](screenshots/home-320-font130-scrolled.png) |
| 大屏 1280 × 800 dp，字体 100% | 既有导航栏、主页和详情侧栏按大屏宿主呈现 | [大屏](screenshots/home-tablet.png) |

实际操作检查：启动应用、运行时权限授权、发起发现、导航到发现页并重新扫描、打开设置、切换 VOX 后主页反映 DISABLED / IDLE、选择手机听筒、滚动进入日志并返回。模拟器发现过程出现无线链路忙/重试提示；该环境未建立真实双设备音频连接。

最终视觉复核同时对照概念图与最新已连接截图，检查信息层级、配色、字体、图标、留白、状态真实性；最后再次检查小屏大字体和大屏。VOX 状态区改成标题一行、三个状态独占下一行，以保证窄屏可读。保留原抽屉/大屏导航、真实业务文案和完整设备信息等有意差异详见 [设计说明](README.md)。

文件名含 `preview` 的截图来自 Debug 专用确定性状态样本，仅用于检查布局。其联系人、耳机和连接数据不代表真实连接测试。蓝牙耳机、双真机对讲、无线距离、风噪与骑行听感仍需物理设备验证。
