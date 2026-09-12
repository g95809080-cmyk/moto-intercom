# 首次使用引导验证

## 结论与源码身份

本地自动门禁与本次引导模拟器交互验证通过。基线为 `0a67901e0513e5797facf70265bbb64cca6a80cc`，分支 `feat/first-run-onboarding`，验证对象是该 HEAD 加本次未提交源码及新增文件；没有新建提交、推送、发布或合并。原 release-v1 工作区未提交改动未被修改。

包含基线自身缺失的 homePresentation(audioReady) 参数及音频等待显示修复，详见产品文档。

## 自动检查

在本次独立工作树的 ASCII 映射 `O:`，使用 JDK `F:/Android/jbr` 和已有缓存：

```powershell
$env:JAVA_HOME = 'F:/Android/jbr'
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain
```

结果：`BUILD SUCCESSFUL in 3m 30s`，88 tasks，58 executed / 30 up-to-date。

| 项目 | 结果 |
| --- | --- |
| 单元/Compose/Robolectric 测试 | 70 suites，566 tests，0 failure / error / skipped |
| 新增引导测试 | 8 项通过 |
| Lint | 0 Error/Fatal，70 Warning |
| 调试 APK | 构建通过并安装到模拟器 |
| Android instrumentation APK | 构建通过，未执行完整 instrumentation suite |
| git diff --check | 通过 |

新增测试覆盖：未完成与权限请求记录续接、真实条件选择、首次介绍不启动服务、跳过后重建、等待真实启动状态、过期 START 回调、无车友结束教学、拒绝后设置入口、来电让位、音频未就绪不提前完成，以及原生滚动恢复后的目标可见性。横屏布局结论以实际模拟器截图为依据；smallLandscape 测试的 helper 包含固定尺寸测量，不能单独作为横屏视觉证明。

Onboarding 相关 Lint 非阻断提示包括 DrawAllocation、UseKtx（三处）、RtlHardcoded。后者为挖孔定位使用窗口物理坐标 Gravity.LEFT；不能直接改为相对 START 而不同时转换坐标。

## 模拟器交互

设备：Android API 36，`MotoIntercom_Graphics_API36`，`emulator-5554`。仅使用本任务的模拟器测试安装；没有操作真机。

- 首次出现完整内置说明图，底部带我开始/跳过可点击；原图片 SHA-256 与用户生成图完全一致。
- 已授权路径：点击真实启动按钮上的镂空代理进入 Discovering；点击发现导航镂空代理进入扫描教学。没有伪造 Service 状态。
- 横屏：启动和扫描教学均自动使目标进入原生 ScrollView 可视范围，气泡移到侧面，目标及教学完成按钮可见（05、06）。
- 点击“我知道了，自己试试”后，本地 XML 为 `phase=COMPLETE`、`hasShownGuide=true`；强停 App 后重新启动，布局树中无 onboarding 节点，直接显示首页（07、relaunch.json）。
- 清除模拟器该测试 App 数据后，重新显示说明图。点带我开始展示权限说明（08）；点授权打开系统麦克风、附近设备、电话状态弹窗，教学未遮挡系统操作。
- 逐项拒绝后，正确高亮真实“打开系统权限设置”按钮。切到 1.5 倍系统字体触发重建，仍续接设置教学而非重复说明图，文字和按钮可读（09）。测试结束恢复字体 1.0。

01、02 是实现过程中的竖屏展示截图（对应视觉在最终版保留）；05–09 对应最终 APK。03、04 的旧扫描文案截图已删除，避免混用。

没有第二台手机，未验证双向真实声音、蓝牙耳机、距离或射频质量；没有把 Wi-Fi Direct 在模拟器上的 Busy 反馈算作真实设备连接通过。没有执行 CI 或核对/关闭 Linear issue。

## 架构审查

由独立只读 reviewer 按 `motointercom-product-architect` Skill 完整审查本次工作区差异及实际证据：

```text
APPROVED
P0: 无
P1: 无
Non-blocking: Lint 提示；横屏测试 helper 的固定测量尺寸，视觉结论以模拟器截图为准。
Base SHA: 0a67901e0513e5797facf70265bbb64cca6a80cc
Head SHA: 0a67901e0513e5797facf70265bbb64cca6a80cc + 未提交 diff 和新增文件
Next gate allowed: 补齐手工记录后交付功能及 APK，进入后续代码提交/PR 审查。
```

确认教学不修改产品状态、身份、媒体生命周期；授权/启动/发现/扫描复用已有意图入口，来电与连接期间让位，完成教学与实际音频就绪相互区分。

## 产物

- APK：`../output/MotoCom-onboarding-debug.apk`（相对于工作树根目录）。
- APK SHA-256：`BF40D2BF5A38997B0A5755857E12B257E7FE826DB9391B4070723C6C864EEF3A`。
- 原说明图 SHA-256：`DE8146366EB54FFF632B1C30AC33F1764C03EB3E29914750FBBD1C73DF9BF0DD`。
- 本目录 `evidence/2026-09-12-onboarding/`：构建日志、引导测试 XML、Lint XML、汇总 JSON 和截图。
