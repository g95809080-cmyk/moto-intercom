# MotoCom 多音频无真机测试报告

日期：2026-08-14
范围：`docs/product/2026-08-13-motocom-multi-audio-intercom-prd.md` 的 P0/P1 音频共存、电话抢占和恢复验收。
代码提交：本次测试变更（历史测试扩展提交：`3e2be37`；前置实现提交：`2230c69`、`fb794d6`、`9743fb9`）。

## 已验证

| 层级 | 执行方式 | 结果 |
| --- | --- | --- |
| JVM / Robolectric | `:app:testDebugUnitTest`，从临时 `Z:` 映射盘运行 | 550 tests，0 failures，0 errors，0 skipped |
| 音频焦点平台适配 | `AndroidAudioPlatformRobolectricTest` | 3 tests：`MAY_DUCK` 属性、request/abandon 同一对象、音乐流音量不变；API 35/29 电话回调 |
| 协调器状态机 | `CommunicationAudioCoordinatorTest` | 覆盖来电前启动、RINGING/OFFHOOK/IDLE、焦点丢失/恢复、永久焦点丢失不误判、延迟焦点、路由就绪、重复回调、用户停止后的迟到回调 |
| WebRTC 音频生命周期 | 两台 API 36 AVD 的 `RiderAudioEngineHotSessionTest` | 2/2 通过；暂停/恢复时 PeerConnection 保持存活，音频闸门切换成功 |
| 合成音频 | API 36 AVD 的 `SyntheticAudioMetricsTest` | 3/3 通过；暂停帧被拒绝，恢复后同一流继续接收 |
| 系统 Audio Focus | API 36 AVD 的合成媒体焦点客户端 | 1/1 通过；媒体焦点客户端收到 `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` |
| 其他 instrumentation | recovery、reset、disconnect、Sprint 4、启动/兼容性契约、UI smoke | 通过 |

测试代码：

- `app/src/test/java/com/kuma/motointercom/AndroidAudioPlatformRobolectricTest.kt`
- `app/src/test/java/com/kuma/motointercom/CommunicationAudioCoordinatorTest.kt`
- `app/src/androidTest/java/com/kuma/motointercom/RiderAudioEngineHotSessionTest.kt`
- `app/src/androidTest/java/com/kuma/motointercom/AudioFocusInterruptionInstrumentationTest.kt`

## Windows 测试运行注意

工程位于包含中文字符的路径下时，Gradle 9.5 的 Windows test worker 可能在原路径报告 `ClassNotFoundException`，但编译产物和测试类均存在。使用临时盘符映射可复现完整通过结果：

```powershell
$repo = (Resolve-Path '.').Path
subst Z: $repo
Set-Location Z:\
./gradlew.bat :app:testDebugUnitTest --no-daemon --offline --console=plain
subst Z: /D
```

这属于测试运行器路径问题，不是测试断言失败。

## 当前无法由无真机环境证明的项目

1. 小米 6/13 的实际 Audio Policy、蓝牙 HFP/SCO/BLE Audio、通话路由和物理串音。
2. 任意指定第三方音乐/导航 App 是否真的遵守 `MAY_DUCK`，以及释放焦点后的原状态恢复；模拟器已证明系统会向合成媒体焦点客户端发送 `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`。
3. 真实蜂窝电话或其他 VoIP 来电期间的系统电话音频隔离。当前 AOSP ATD 模拟器中 `adb emu gsm call/accept/cancel` 未改变 `telephony.registry` 的 `mCallState`。
4. AOSP ATD 模拟器的 NSD/Wi-Fi Direct；NSD 注册失败，两个 AVD 还共享同一 NAT 地址，无法完成现有跨实例网络脚本。

上述项目必须在小米 6/13 和可用蓝牙头盔上人工逐步验证；模拟器/JVM 结果不能替代听音结论。
