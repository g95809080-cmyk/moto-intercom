# MotoIntercom 全仓审查与结构化重构设计

日期：2026-07-10
目标分支：`ui/light-theme-redesign`
采用方案：B（修复已确认问题，同时拆分超大类）

## 目标

在不改变一对一离线对讲产品定位的前提下，修复审查确认的生命周期、并发、信令、音频路由、权限和 UI 问题，并把当前超大类拆成可独立理解、测试和验证的单元。

完成后必须满足：

- 停止会话后，旧线程、旧 socket、旧 WebRTC 或音频回调不能复活服务状态。
- Wi-Fi Direct 成组但信令未建立时能超时恢复，不永久卡死。
- TCP 信令拒绝非预期来源和超限输入；协议错误会统一断开并通知服务。
- `AudioManager` 的通信模式和路由只有一个所有者，停止后恢复初始状态。
- Activity 重建或回到前台时恢复真实服务状态，后台不持续刷新 UI。
- Android Lint 无 error；关键新逻辑有可重复单测。
- MI 6 与 Xiaomi 13 完成双机实测；Xiaomi 13 的 OPPO Enco X3 完成蓝牙通话路由实测。

## 已确认基线

- GitHub 仓库：`g95809080-cmyk/moto-intercom`（private）。
- 当前分支：`ui/light-theme-redesign`，工作区审查开始时干净。
- `assembleDebug` 成功。
- `testDebugUnitTest` 为 `NO-SOURCE`。
- `lintDebug`：4 errors、28 warnings。
- 在线设备：
  - `9688fa60`：MI 6。
  - `efcb9031`：Xiaomi 13（系统型号 `2211133C`）。
- Xiaomi 13 音频状态可见 `mBluetoothName=OPPO Enco X3`。

## 范围

### 本轮包含

1. 修复审查确认的 P1/P2 行为问题。
2. 修复全部 Lint error；处理与本轮改动直接相关的 warning。
3. 拆分 `MainActivity`、`IntercomService`、`WifiDirectTunnel`、`RiderAudioEngine` 的独立职责。
4. 增加最小单测基础和关键回归测试。
5. 编译、Lint、单测和双机/蓝牙实测。

### 本轮不包含

- 多人对讲、云服务、账号体系或公网通信。
- 为解决附近设备抢连而新增 PIN、二维码或证书确认 UI。
- 替换 WebRTC 库、迁移 Compose、引入 DI/架构框架。
- 无证据的格式化、命名翻新或资源全面国际化。
- 自动 push、PR 或发布 APK。

## 设计原则

- 先修行为，再移动代码；每个阶段保持可编译。
- 旧公共调用入口尽量不变，避免同时重写调用方和实现。
- 平台对象由单一组件拥有；资源释放与创建位置对称。
- 异步结果必须携带会话代次，并在交接资源前后校验。
- 不以公开 DNS-SD/TXT 字段冒充加密认证。
- 只为已确认边界创建新类，不增加单实现接口或工厂。

## 目标结构

### 1. 服务与会话

`IntercomService` 只保留：

- Android started/bound service 生命周期。
- foreground notification。
- 当前会话状态和对 UI 的快照发布。
- Wi-Fi Direct、LAN、信令、音频组件的编排。

新增 `SessionGeneration`：

- 用单调递增 token 标识每次启动。
- `startIntercom()` 创建 token。
- `stopIntercom()` 首先使 token 失效，再关闭资源。
- 所有异步回调和阻塞操作返回值必须验证 token。
- 失效回调只关闭其局部资源，不写回服务字段。

新增 `LanDiscoveryCoordinator`：

- 独占 NSD、UDP 广播/监听、LAN TCP server 和相关 executor/socket。
- 只产出设备快照或已连接 socket。
- `close()` 后不再回调；晚到 socket 立即关闭。
- `IntercomService` 不再直接维护 LAN 线程和 socket。

系统杀死后的策略：本轮不持久化会话恢复，因此 `onStartCommand()` 返回 `START_NOT_STICKY`。不创建无通知、无媒体状态的空壳服务。

### 2. Wi-Fi Direct

`WifiDirectTunnel` 保留 Android `WifiP2pManager` 注册、连接和组校验编排。

新增 `WifiDirectPeerRegistry`：

- 维护 pending、accepted、selected peer。
- 每次完整 peers 回调按当前地址集合 reconcile。
- selected peer 离场后清除并重新选择。
- 纯 Kotlin，无 Android 线程依赖，可单测。

新增 `WifiDirectSignalingSocket`：

- 独占 server/client socket、连接重试和 TCP-ready watchdog。
- server 绑定本地 P2P 地址，不绑定所有接口。
- `accept()` 使用超时；client 每次失败立即关闭局部 socket。
- 连接成功后校验远端 IP 与已验证 P2P group peer 一致。
- 只有 token 有效时才能把 socket 交给上层。

状态明确分为：`DISCOVERING`、`P2P_CONNECTING`、`GROUP_READY`、`SIGNALING_READY`、`CLOSED`。watchdog 延续到 `SIGNALING_READY`，任何失败走同一 reset/removeGroup/rediscover 路径。

### 3. 信令协议

新增纯 Kotlin `SignalingProtocol`：

- 定义消息类型、字段和大小上限。
- 解析并验证 identity、SDP、ICE candidate。
- identity 最多 64 个 Unicode code point，SDP 最多 64 KiB，单个 ICE candidate 最多 4 KiB，整帧最多 128 KiB。
- 单会话最多接收 256 个 ICE candidate。
- 未知类型、非法顺序、缺字段或超限统一返回协议错误。

`IntercomManager` 保留 TCP framing、reader/writer 和 WebRTC 胶水：

- 所有 JSON/协议异常包装为 `IOException`。
- 统一调用一次 `onIntercomDisconnected`，随后关闭。
- writer 不接收关闭后的新任务。
- 不创建无界的跨线程候选积压。

来源 IP 校验可阻止普通旁路抢连，但不是密码学身份认证。真正抵抗同网段主动攻击需要用户可确认的 PIN/二维码/证书流程，留作单独产品设计。

### 4. WebRTC、VOX 与音频路由

`AudioRouteController` 成为 `AudioManager.mode`、communication device 和 legacy SCO 的唯一所有者：

- 初始化时记录真实初始 mode/route。
- 所有路由操作走同一 executor。
- `close()` 排队完成恢复，再关闭 executor。
- API 31+ 使用强类型 communication-device listener，不使用返回 `null` 的动态代理。
- API 级别访问增加正确 guard。

`RiderAudioEngine` 不再写 `AudioManager.mode`，只拥有 WebRTC 资源。

新增 `VoxGate`：

- 只负责 `BYPASS/LISTENING/OPEN/HANGOVER` 状态转换。
- 输入为音量、时间和阈值；输出为轨道目标音量和显示状态。
- 保持当前已验证的 `setVolume(0.0/1.0)` 门控策略。

所有 WebRTC observer 回调先切回 `rtc` 单线程 executor，再访问 remote-description 和 candidate 状态。所有投递到主线程的回调在执行时复查 `closed` 与 session token。

初始化采用明确 `INITIALIZING/READY/FAILED/CLOSED` 状态；失败按创建逆序释放资源，不能留下半初始化音频模式。

### 5. UI 与权限

`MainActivity` 只保留 Activity 生命周期、权限请求、服务绑定和用户动作。

新增 `MainScreen`：

- 构建和持有当前程序化 View 树。
- 接收不可变 UI 状态并渲染。
- 日志使用固定上限（最多 300 条）。

将 `RippleView`、`VisualizerView` 移到独立文件，停止时取消动画。

绑定规则：

- `onStart()` 绑定服务并注册 listener。
- `onStop()` 清 listener、解绑并停止 UI 动画。
- `bindService()` 返回成功时立即记录绑定请求，避免回调前销毁导致漏解绑。
- 服务连接后一次性发布状态、音频、设备和远端昵称快照。

权限规则：

- RECORD_AUDIO、Wi-Fi Direct/附近设备和必要蓝牙权限仍是核心权限。
- `POST_NOTIFICATIONS` 单独请求；拒绝不阻止对讲启动。
- Manifest 补充 `ACCESS_COARSE_LOCATION` 的兼容声明。

资源与主题：

- API 31+ `AppTheme` 与基础主主题一致。
- Splash 专属属性只留在 `SplashTheme`。
- 修复 bitmap density 位置；本轮不重新设计图像。
- LAN 回退保留时，`android.hardware.wifi.direct` 改为非必需，并运行时判断能力。

## 错误处理

- 协议错误：记录短错误原因，关闭当前信令，通知服务断开。
- P2P/TCP 建链错误：统一释放 group/socket 状态并重新发现；重试有上限。
- WebRTC 初始化错误：逆序释放并结束当前会话，不保持假运行状态。
- 蓝牙断开：回退扬声器并继续会话；设备重新出现时按现有策略尝试路由。
- 服务停止：先失效 token，再关闭生产者，最后清 UI 状态和 foreground notification。

## 测试策略

新增 JUnit 4 单测依赖，只测试纯逻辑和无需 Android runtime 的边界：

- `SessionGenerationTest`：旧 token 在 stop/restart 后失效。
- `WifiDirectPeerRegistryTest`：peer 离场清理、重选、pending/accepted reconcile。
- `SignalingProtocolTest`：合法消息、缺字段、未知类型、超限字段、candidate 数量、非法顺序。
- `VoxGateTest`：LISTENING、OPEN、HANGOVER、BYPASS 转换。
- `PermissionPolicyTest`：通知权限拒绝不阻止核心启动；各 API 级别核心权限集合。

Android 行为用 Lint、构建和真机验证，不引入 Robolectric 或 UI 测试框架。

## 实施顺序

1. 建立测试基础，先写纯逻辑失败测试。
2. 引入 session generation，封住停止后复活。
3. 提取并验证信令协议。
4. 提取 peer registry 和 socket transport，修复 P2P/TCP 生命周期。
5. 提取 LAN discovery coordinator，瘦身 service。
6. 提取 VOX，串行化 WebRTC callback，统一音频路由所有权。
7. 拆分 UI，修绑定/权限/主题/日志/可访问性。
8. 清理本轮产生的无用代码和 Lint error。
9. 全量自动验证。
10. 安装两台设备并执行双机与蓝牙实测。

## 验收

自动验证：

```powershell
$env:JAVA_HOME='F:\Android\jbr'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

真机矩阵：

1. MI 6 与 Xiaomi 13 冷启动，完成自动发现和一对一连接。
2. 双向说话均可听见，无持续静音、爆音或错误扬声器路由。
3. Xiaomi 13 的 OPPO Enco X3 成为 communication device；耳机听、麦克风说均正常。
4. 停止后服务、P2P group、socket、WebRTC 和音频路由全部释放。
5. 连接中强制断开/关闭一端，另一端进入丢失状态并可重新发现。
6. Activity 旋转、退后台、重新进入，UI 与前台服务真实状态一致。
7. 拒绝通知权限仍可启动对讲；拒绝核心权限会明确阻止启动。

只有自动验证和双机验收均通过，才允许提交代码。不会自动 push 或创建 PR。
