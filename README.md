# 🏍️ 摩声 MotoCom

**两台手机，就能边骑边聊。**

摩声是一款 Android 一对一骑行对讲 App。打开 Wi-Fi，找到附近车友，连上后直接说话——不用一直按着按钮，也不需要互联网。

## 📦 下载体验

[下载最新正式版 APK](https://github.com/g95809080-cmyk/moto-intercom/releases/latest) · [查看全部版本](https://github.com/g95809080-cmyk/moto-intercom/releases)

支持 **Android 6.0 及以上**。下载 Release 页面中的 `.apk` 文件，在手机上打开安装；系统询问时，允许当前浏览器或文件管理器安装应用。

## ✨ 摩声能做什么？

- **离线聊天**：通过局域网或 Wi-Fi Direct 连接附近车友，Wi-Fi 要开，互联网可以没有。
- **开口就聊**：支持 VOX 自动开麦，也可以随时静音自己的麦克风。
- **耳机陪你上路**：可选择蓝牙耳机、手机听筒或外放，按实际设备情况切换。
- **断开后找回原车友**：开启自动重连后，连接意外中断会尝试恢复。
- **第一次也不迷路**：先看图文说明，再跟着高亮按钮完成准备、启动和发现车友。

当前是**一对一对讲**。仓库里的 iOS 代码仍在开发，本次发行的是 Android APK。

## 🚀 四步上手

1. **双方做好准备**：打开 Wi-Fi；使用蓝牙耳机时，先在手机系统中连好耳机。打开摩声，按提示授予必要权限。
2. **双方启动摩声**：在首页点击“启动摩声”，再进入“发现车友”。
3. **一方连接，另一方接受**：找到对方点击“连接”，收到请求的手机点击“接受”。
4. **看到“语音通道已连接”，就能聊啦！** 不用按住按钮；首页可以静音、调整音频或结束对讲。

首次打开会自动展示新手引导。完成或跳过后不会反复弹出；想再看一遍，可去 **设置 → 帮助与反馈 → 重新查看引导**。

<details>
<summary>🖼️ 点开查看一图上手说明</summary>

![摩声四步使用说明](app/src/main/res/drawable-nodpi/onboarding_guide.png)

</details>

## 💬 遇到小问题？

| 情况 | 可以这样试试 |
| --- | --- |
| 找不到车友 | 确认双方都已启动摩声、Wi-Fi 已打开，再点“重新扫描”。 |
| 听不到声音 | 检查双方是否静音，以及“音频设置”里选择的输出设备。 |
| 权限没开 | 跟随引导进入系统权限设置，开启所需权限后返回 App。 |
| 身边暂时没有车友 | 可以先结束教学，等对方准备好再连接。 |
| 想反馈问题 | 到“设置 → 帮助与反馈”；需要诊断时可到“日志诊断”复制日志。反馈不会自动附带日志或录音。 |

## 🧑‍💻 想自己构建？

项目使用 Kotlin、Jetpack Compose、WebRTC。用 Android Studio 打开仓库根目录，配置 Android SDK（compileSdk 37），再使用项目自带的 Gradle Wrapper。

Windows 示例：

```powershell
# 换成你自己的 JDK 路径
$env:JAVA_HOME = 'F:/Android/jbr'
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/`。运行设备测试：

```powershell
./gradlew.bat connectedDebugAndroidTest
```

`assembleRelease` 默认生成未签名 Release APK，需要使用分发证书签名后安装。GitHub 个人分发版本沿用历史版本的签名证书，便于覆盖安装；它不是应用商店生产签名。签名文件不保存在仓库中。

源码路径含中文且遇到构建工具路径问题时，可将当前目录映射到空闲盘符，再从该盘符运行 Wrapper。

## 📚 更多资料

- [新用户引导设计](docs/product/2026-09-12-first-run-onboarding.md)
- [引导功能验证记录](docs/verification/2026-09-12-onboarding.md)
- [v1.2.0 更新说明](docs/releases/v1.2.0.md)

自动测试和模拟器验证不代表双真机听音、蓝牙兼容性或实际距离测试已经通过；每次版本的具体验证范围见更新说明。

由 **kuma** 开发。让骑行时的交流更简单一点。🏍️
