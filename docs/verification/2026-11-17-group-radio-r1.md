# 群组电台 R1 验证记录

日期：2026-11-17。依据：[群组 PRD](../product/2026-11-17-motocom-group-radio-prd.md)、[实施记录](../plans/2026-11-17-group-radio-r1.md)。

## 范围与基线

- 主工作区：`MotoIntercomApp`，`main`，基线 `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`。
- 本次改动尚未提交；没有新建分支、合并或正式发布。
- 群组参考源码：`four-person` 的 `091c7dcc22a438ee66e3ac3b60c6ac35c4a83dbf`。
- 交付形态：独立 `groupPreview` 变体，包名 `com.kuma.motointercom.group.preview`，版本 `2.1.0-r1.preview.1`。
- 本轮实现 R1 公共频道；R2 角色、领队和私聊，R3 网络，R4 SFU，R5 双模均未实现。

## 已接入的能力

创建本地四人房间、六位码认证加入、成员名单、公共语音、VOX、自静音、本机屏蔽成员、群主移除与解除限制、结束确认、固定 60 秒恢复、前台通知与回房入口。

群组源码、BLE 权限、PAKE 依赖及群组入口仅进入预览变体。共享音频平台默认仍为一个媒体会话；群组模式最多三个远端会话，共享 ADM、factory、本地音轨与音频控制。

新安装未设置昵称时，使用设备稳定标识后六位生成可区分的默认昵称，例如“骑士 ABC123”；已设置的昵称保留。

## 关键异常顺序

| 场景 | 处理 |
|---|---|
| 取消后设备身份读取才返回 | 启动 token 已失效，不再创建房间 |
| 网络或控制链路反复失败 | 恢复期限固定为首次失联后 60 秒，重试不续期 |
| 期限后旧认证或网络回调返回 | 不恢复已结束的房间 |
| 第五人加入 | 明确提示满员并回准备页；不挤占现有成员 |
| 音频构造在路由阶段失败 | 等待该引擎真实销毁；旧平台未释放前不创建新平台 |
| 旧音频释放回调重复或迟到 | 仅释放原构造 token，不影响替补实例 |
| 某个 peer 拆除 sink 抛错 | 仍尝试释放该 PeerConnection、其他 peer 和共享平台 |
| 房间先结束、网络或音频仍释放中 | Service 保持忙碌，释放完成后才允许再次建组 |
| native 回调与业务关闭并发 | native 收件只读无锁所有权快照；业务授权在 RTC/Main 队列校验 |
| 关闭或静音单个成员 | 不撤销其他成员的共享音频授权；播放静音按成员生效 |

## 自动检查

| 检查 | 结果 |
|---|---|
| `testDebugUnitTest` | 876 项通过，0 失败，0 错误，0 跳过 |
| 真实 `RiderAudioEngine` 调度与所有权回归 | 4 项通过，包含 GROUP 容量、SINGLE 容量、共享授权和 native 收件锁边界 |
| `testGroupPreviewUnitTest` | 162 项通过，0 失败，0 错误，0 跳过 |
| `lintGroupPreview` | 0 错误、93 警告 |
| `lintDebug` | 0 错误、89 警告 |
| `assembleGroupPreview` / `assembleDebug` | 均成功 |
| `assembleDebugAndroidTest` | 成功 |
| `RiderAudioEngineHotSessionTest` instrumentation | Android 16 / API 36 模拟器，4 项通过 |
| 小米 13 原生媒体回归 | Android 16 / API 36 真机，4 项通过；同进程环回，非四机互听 |
| 小米 13 单机生命周期 | 建组、收起回房、结束取消/确认、GO 释放、再次建组并结束通过 |
| 预览 APK 签名与清单 | `apksigner verify` 通过；v1/v2 调试签名；包名、版本、Launcher 与预览变体一致 |
| 模拟器 UI 烟测 | 下表所列流程通过；最终 crash buffer 无记录 |

162 项群组检查按 Gradle 的预览任务筛选运行，包含群组领域、认证、控制、网络、Service、UI 以及共享音频回归。它与双人 876 项存在共享用例，不作为互不重复的测试总数相加。

JVM 测试控制真实引擎的任务调度。原生 instrumentation 已在模拟器和小米 13 分别执行三个真实 PeerConnection 共享平台、关闭后替补、单成员关闭后另一成员 RTP 收发继续增长，以及音频 suspend/resume。每轮环回连接均位于同一设备、同一进程；实际人耳听音、无线距离和多设备互访另行验收。小米 13 首次运行遇到布局工具占用 UiAutomation，停止工具后四项通过，原始失败和重跑输出均保留，详见[单机检查](2026-11-17-group-radio-r1-xiaomi13.md)。

本次修改范围的 `git diff --check` 通过。整个工作树仍有用户已有的 `docs/product/2026-09-30-ui-interaction-inventory.md` 文件末尾空行提示，本轮未修改该内容。

## 模拟器烟测

设备为本次启动的 `medium_phone`，serial 为 `emulator-5554`，Android 16 / API 36，x86_64。模拟器报告支持 Wi-Fi Direct、BLE 和麦克风；以下验证实际运行的预览 APK。

| 场景 | 观察结果 |
|---|---|
| 首次启动与输入 | 显示准备页；五位码时加入按钮禁用，六位码时启用 |
| 拒绝麦克风 | 返回准备页，显示授予麦克风与附近设备权限的解释，可再次请求 |
| 授权后创建 | 创建 GO 与房间广播成功，显示房主、六位码和公共频道 1/4 人 |
| 切换输出选项 | 扬声器选项可选中；收起再回房仍保持该选项，未做人耳听音 |
| 收起对讲与回房 | Activity 退出后 Service 仍为前台；重新启动入口回到同一房间 |
| 群主结束 | 显示确认框；取消保留房间，确认返回准备页并撤销前台状态 |
| Wi-Fi 关闭 | 明确提示检查 Wi-Fi 和权限，返回准备页并撤销前台状态 |
| 释放后再次创建 | 恢复 Wi-Fi 后能再次创建新房间，并能正常结束 |
| 加入搜索与取消 | 六位码进入附近房间验证流程；取消后返回准备页并撤销前台状态 |
| 崩溃检查 | 本轮 native 与 UI 操作后，crash buffer 无记录 |

UI 点击均从当次 UI tree 的 bounds 获取坐标。XML、截图、Service 状态和原生测试输出保留于 `app/build/verification/r1-20261117/`；关键截图为 `ready.png`、`permission-denied.png`、`reopened.png`、`end-confirmation.png`、`wifi-disabled.png`、`search-cancelled.png`。

单模拟器的创建成功只验证 Android API 和生命周期；本轮没有第二台设备接入，BLE 认证、Wi-Fi 加入与四人实际通话的端到端验收仍为空缺。

## 可安装预览包

- 文件：[摩声群组 R1 预览 APK](../../../output/motocom-group-2.1.0-r1.preview.1-3bdf3933.apk)。
- 包名：`com.kuma.motointercom.group.preview`。
- 版本：`2.1.0-r1.preview.1`，versionCode `1`。
- 大小：`61,095,455` 字节；支持 `arm64-v8a`、`armeabi-v7a`、`x86_64`。
- SHA-256：`3bdf3933ff13f726d04f69c7ea580f30540e200b1449de9d6f9dbe9ee628ab03`。
- 调试签名，仅用于本轮工程预览；独立包名可与双人版同时安装。
- 项目构建产物仍位于 `app/build/outputs/apk/groupPreview/app-groupPreview.apk`，与交付副本的 hash 一致。

待执行的真机步骤：四台手机安装同一预览包，授予必要权限并开启 Wi-Fi 与蓝牙；一台创建，其余输入房主六位码，按系统提示加入。逐一检查六对双向听音、成员静音、移除/解除限制、掉线恢复、后台与来电、结束后重新建组，最后完成两小时稳定性测试。

## 架构与代码复审

`motointercom-product-architect` 对当前源码的只读复审结果为 `APPROVED`，P0 无、P1 无。独立代码复核也确认默认昵称可区分、普通成员不显示房间码、群主结束需要确认、Activity 收起不退群，以及异步释放完成前仍禁止重建。

最终证据复审已直接核对测试 XML、Lint、native 输出、UI/Service 快照、签名和交付副本 hash，结论保持 `APPROVED`。Base SHA 与 Head SHA 均为 `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`，Head 另含本次已审查的未提交工作树。

允许的下一检查点为四台真机 R1 验收。角色、领队/私聊、网络后端、SFU 和双模仍按 PRD 的后续阶段推进；当前结果不用于宣布 R1 产品退出条件已满足。

## 复现环境

- JDK：Microsoft OpenJDK `17.0.21`。
- Gradle：项目指定的 `9.5.0`；未修改 wrapper 版本或分发地址。
- Android SDK：`C:/Users/Administrator/AppData/Local/Android/Sdk`。
- 使用 `subst M:` 将当前工作区映射为英文盘符，避开项目已知的中文路径 host test 类加载问题。
- Gradle 分发包与缺失的 Robolectric SDK33 运行库经官方 checksum 校验后填充本机缓存；未降低依赖版本或调整测试 SDK。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-17.0.21.21-hotspot'
$env:ANDROID_HOME = 'C:/Users/Administrator/AppData/Local/Android/Sdk'
subst M: 'C:/Users/Administrator/Documents/摩托对讲机/MotoIntercomApp'
Set-Location M:/
.\gradlew.bat testGroupPreviewUnitTest testDebugUnitTest lintGroupPreview lintDebug assembleGroupPreview assembleDebug assembleDebugAndroidTest --console=plain --max-workers=2
```

已有 `M:` 时先核实映射，不覆盖其他盘符。单元测试、Lint 报告位于 `app/build/reports`；XML 测试结果位于 `app/build/test-results`。

本次 Gradle daemon 已停止。系统临时目录中的两个下载 ZIP 和临时工具链目录在清理时被执行策略拒绝，暂时保留；经校验的正式 Gradle 与 Robolectric 缓存仍可供后续构建使用。

原生音频测试可在安装 debug APK 和 debug androidTest APK 后执行：

```powershell
& "$env:ANDROID_HOME/platform-tools/adb.exe" -s emulator-5554 shell am instrument -w -r `
  -e class com.kuma.motointercom.RiderAudioEngineHotSessionTest `
  com.kuma.motointercom.instrumentation/androidx.test.runner.AndroidJUnitRunner
```

## 设备验收边界

初次工程验证使用 `medium_phone` 模拟器。用户随后在一台小米 13 上完成安装与建组，无线 ADB 中断后切换 USB，已补验收起回房、结束确认、GO 释放、再次建组和四项原生媒体回归。手机最终保留在预览准备页，临时测试与布局工具包已卸载，详见[小米 13 单机检查](2026-11-17-group-radio-r1-xiaomi13.md)。以下仍需真实设备完成：

| 检查 | 状态 |
|---|---|
| 四台手机、六对双向实际听音 | 未执行 |
| 无路由器、无互联网的创建与加入 | 未执行真机验收 |
| OEM Wi-Fi Direct 客户端互访限制 | 未执行 |
| 蓝牙耳机与 Wi-Fi Direct 同时工作 | 未执行 |
| 锁屏、后台、来电后实际听音恢复 | 单机收起回房通过；通话中的实际听音恢复未执行 |
| 60 秒内恢复及房主失联后的真实网络清理 | 未执行真机验收 |
| 两小时连续对讲 | 未执行 |

上述项目通过前，R1 的产品退出条件未满足。自动检查通过及模拟器运行仅用于工程验证。
