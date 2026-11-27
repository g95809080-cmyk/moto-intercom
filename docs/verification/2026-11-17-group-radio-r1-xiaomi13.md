# 群组电台 R1：小米 13 单机检查

日期：2026-11-17。关联：[R1 工程验证](2026-11-17-group-radio-r1.md)。

## 结果

独立 R1 预览包已在小米 13 上完成安装、权限授权、创建房间、收起后回到原房间、取消与确认结束、Wi-Fi Direct GO 释放、再次创建和结束。四项原生媒体回归在该手机上全部通过。

首次无线 ADB 中断后，用户切换 USB 调试；恢复连接时原房间及前台 Service 仍在，随后完成上述检查。当前只有一台真机，不能据此认定四机互听、蓝牙实际听音或 R1 产品验收通过。本轮未修改产品源码。

## 设备与安装

| 项 | 结果 |
|---|---|
| 设备 | Xiaomi 13，型号 `2211133C`，代号 `fuxi` |
| 系统 | Android 16 / API 36 |
| 真实设备数 | 1；IP 和 mDNS 两条 ADB 记录经 `ro.serialno` 比对，属于同一台手机 |
| USB 调试 | serial `efcb9031`，`adb devices -l` 为 `device` |
| 预览包 | `com.kuma.motointercom.group.preview` |
| 版本 | `2.1.0-r1.preview.1`，versionCode `1` |
| 安装结果 | `adb install` 返回 `Success`，Launcher 解析及启动成功 |
| APK SHA-256 | `3bdf3933ff13f726d04f69c7ea580f30540e200b1449de9d6f9dbe9ee628ab03` |
| 现有主包 | 安装前已存在；本轮未安装、更新或清除该主包 |
| 现有测试包 | 安装前已存在；本轮未安装、更新或清除该测试包 |

## 已取得的真机证据

| 检查 | 结果与证据 |
|---|---|
| 准备页 | 创建、六位码输入、加入、权限设置均可见 |
| 设备能力 | 系统报告支持 Wi-Fi Direct、BLE 和麦克风 |
| 权限 | 麦克风与附近设备授权后，包管理器确认 RECORD_AUDIO、NEARBY_WIFI_DEVICES、BLUETOOTH_SCAN/CONNECT/ADVERTISE、POST_NOTIFICATIONS 均已授予 |
| 创建房间 | 显示“房间已创建，等待成员加入”、公共频道 1/4 人、稳定后缀昵称与房主标识 |
| 前台托管 | Service 快照中 `isForeground=true`、`startRequested=true`，前台类型包含 microphone 与 connectedDevice |
| USB 恢复连接 | 原单人房间仍在，Service 仍为前台；没有重新安装预览或清除数据 |
| 收起与回房 | 点击“收起对讲”后 GroupActivity 不再为 resumed，Service 仍为前台；重新启动入口后房间码与收起前一致 |
| 取消结束 | 出现“所有成员都会离开，当前房间码将失效”确认框；点击“继续对讲”后原房间保留 |
| 确认结束 | 确认后房间码消失，异步释放完成后恢复“创建房间”；Service 撤销前台状态，`startRequested=false` |
| GO 释放 | `dumpsys wifip2p` 显示 `InactiveState`、`groupFormed: false`、`isGroupOwner: false` 和 `mGroup null` |
| 再次建组 | 释放后再次创建成功，重新显示公共频道 1/4 人与前台状态；第二次结束后恢复准备页并再次释放 GO |
| 最终状态 | 预览停在创建/加入准备页，没有活动群组；本次独立测试包和布局工具包已卸载 |

首次无线阶段证据位于 `app/build/verification/r1-phone-20261117/`，包括 `install.txt`、`ui-ready.xml`、`ui-permissions*.xml`、`created.png`、`service-created.txt`。

USB 阶段证据位于 `app/build/verification/r1-phone-usb-20261117/`，关键文件为 `ui-before-background.xml`、`ui-current-cli.json`、`ui-end-confirmation.json`、`ui-end-cancelled.json`、`ui-ready-after-end.json`、`ui-recreated-1.json`、`ui-recreated-ended-2.json`、`ui-final-ready.json`，以及对应的 Service、Wi-Fi Direct 状态和 `*-result.txt`。

UI 点击均依据当次布局树的 bounds。普通 `uiautomator dump` 多次报告无法取得 idle；没有据此判定应用卡死。窗口快照显示 GroupActivity 为焦点，系统记录没有 ANR，且实际点击与状态切换成功。后续使用 `android layout --device efcb9031 --no-idle` 读取布局，JSON 仅保留预览应用窗口。

单人房间没有远端 peer，页面的“音频待机”“语音连接确认中”不作为实际通话已就绪或音频故障的证据。

## 无线连接中断

首次在线地址为 `192.168.1.160:37433`。开始下一项后台检查时 ADB 已报告 `device offline`，点击和 Service 读取未能执行。测试脚本因没有取得 Service 快照而终止，该结果不用于判定后台 Service 失效。

尝试刷新 offline transport，并重连原地址和 mDNS 中的 `192.168.1.160:42981`；两个端口均返回连接拒绝。没有重启整机、关闭 Wi-Fi、清除应用数据或修改系统调试配置。

用户随后切换为 USB 调试。原房间状态、后台回房、结束释放和单机 native 已补验；无线 ADB 中断原因仍未确定，多机验收继续保留。

## 四项真机原生媒体回归

为保留手机已有主包及测试包，本次使用临时 Gradle init script，仅在测试构建时覆盖 `testApplicationId`：

```text
测试包：com.kuma.motointercom.group.preview.instrumentation
目标包：com.kuma.motointercom.group.preview
Runner：androidx.test.runner.AndroidJUnitRunner
```

`assembleGroupPreviewAndroidTest` 构建成功；APK 内 Manifest 的 package/targetPackage 和签名验证通过。测试 APK 位于 `app/build/outputs/apk/androidTest/groupPreview/app-groupPreview-androidTest.apk`，在 USB 设备上以 `adb install -r -t` 安装成功。

只执行 `RiderAudioEngineHotSessionTest`。最终 raw 输出含四个 start、四个 ok、零个错误/失败/忽略/假设失败状态，完整结尾为 `OK (4 tests)` 与单个 `INSTRUMENTATION_CODE: -1`。通过判断依据逐项状态及完整结果，不依赖 shell exit code。

| 测试 | 实际通过的断言 |
|---|---|
| 热会话暂停与恢复 | 逻辑音频闸门关闭/打开，保留同一个原生 PeerConnection，无错误回调 |
| SINGLE 资源复用 | 拒绝并行第二会话；顺序两条 PC 复用 ADM、factory、source、track，最终释放第二条 PC |
| GROUP 容量与替换 | 创建三条 PC、拒绝第四条；替换一条后其余两条及共享平台对象不变，最终平台只完成一次销毁回调 |
| 同进程双向 RTP | 同一手机、同一进程内两对连接交换 SDP/ICE；关闭一对后另一对收发字节继续增长；暂停/恢复后闸门 revision 变化并重新取得有效音频 IO 证据 |

前三项不包含远端实际音频传输。第四项是同进程环回，没有经过 BLE、PAKE、控制 TCP 或 Wi-Fi Direct；RTP 字节增长不能证明真实人声可懂或指定耳机实际出声。恢复断言验证新的闸门 revision 和音频 IO，未额外要求恢复后的 RTP 字节相对暂停前增长。

首次执行时，布局工具仍持有系统 UiAutomation 连接，四项均在权限准备阶段报错，未进入媒体断言。保留输出 `native-instrumentation.txt`；停止本次布局工具包后重跑，输出 `native-instrumentation-after-cli-stop.txt` 与 `native-result.txt`。该冲突通过释放测试工具连接解决，没有修改产品或测试源码。

复现构建：

```powershell
.\gradlew.bat --init-script `
  app/build/verification/r1-phone-20261117/group-preview-instrumentation.init.gradle `
  -PtestBuildType=groupPreview :app:assembleGroupPreviewAndroidTest --console=plain
```

复现真机测试时先结束活动房间，并确保布局工具未占用 UiAutomation：

```powershell
$taskAdb = 'C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $taskAdb -s efcb9031 install -r -t `
  app/build/outputs/apk/androidTest/groupPreview/app-groupPreview-androidTest.apk
& $taskAdb -s efcb9031 shell am force-stop com.android.cli.interact.instrumentation
& $taskAdb -s efcb9031 shell am instrument -w -r `
  -e class com.kuma.motointercom.RiderAudioEngineHotSessionTest `
  com.kuma.motointercom.group.preview.instrumentation/androidx.test.runner.AndroidJUnitRunner
```

本轮结束后卸载独立测试包 `com.kuma.motointercom.group.preview.instrumentation` 和本次安装的布局工具 `com.android.cli.interact.instrumentation`，两项均返回 `Success`。已有主包、原有 instrumentation 包均保留；预览应用保留在准备页。

## 尚未通过的验收

单台手机的生命周期检查与原生环回仍不足以覆盖 BLE 认证加入、四机六对互听、OEM 客户端互访、真实蓝牙听音、通话中的后台/锁屏/来电恢复、60 秒失联恢复及两小时稳定性。R1 产品验收保持未完成。
