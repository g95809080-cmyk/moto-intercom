# KUM-56：Android 四人离线入口

基于已合并权限修复的 main `880e3ed8`，沿 KUM49/51/52/53/54/55 独立 Draft PR 链接入 Android 生产流程。Linear KUM-56；Rasen `kum-56-group-app`。本记录区分代码自动化与真实设备验收，不代表已发布。

## 生产调用链

主页 → GroupActivity → 显式可见页面操作/权限 → IntercomService → GroupRuntime → GroupSessionOrchestrator。房主通过 Wi-Fi Direct GO + BLE 广播发布房间；成员依次认证候选、选房、系统 Wi-Fi 确认，再做独立 TCP PAKE。准入后才建立每成员 PC，四人形成六对链路。无外部 STUN/TURN、账号或云端控制服务。

GroupRuntime 执行网络和媒体效果，writer独占产品状态。跨 Service 的 runtime/audio 所有权保留到异步清理完成；GO UNKNOWN 持续由 application-context 清理对象持有。旧双人协议在群组期间经被动监听器返回 BUSY，不开放媒体或写配对。

实际语音证据来自同 RTP 流前后计数、PC/远端轨、ADM录音与播放状态及近期PCM、当前I/O授权与已验证路由；查询期间或两次样本间的代次变化会拒绝旧证据。无语音/VOX静默不会被当作网络断开。

## 自动化映射

| PRD | 自动化证据范围 |
| --- | --- |
| A01 创建/结束/重建 | GroupRoomTest、GroupSessionOrchestratorTest、GroupRuntimeTest |
| A02 自动准入/拒错 | GroupAuthenticationTest、GroupBootstrapTest、GroupSocketTransportTest、writer准入与runtime延迟媒体测试 |
| A03 同码多队 | writer候选选择、GroupRuntimeTest同room网络描述防错目标 |
| A04 四席容量 | 域/编排四成员、FULL及WAITING测试 |
| A05 保留60秒 | 编排失联边界、重复失联与网络代次测试 |
| A06 取消/迟到 | auth/network/control/link/current gate、权限销毁与pending identity取消 |
| A07 移除/解除 | bootstrap移除凭据拒绝、writer移除重入/解除测试 |
| A08 静音范围 | Participation、GroupMediaController及runtime Mute；真人听音未执行 |
| A09 本机中断 | CommunicationAudioCoordinator、AudioIoGate、GroupSessionOrchestrator健康pair保留 |
| A10 房主独处 | runtime正式准入前不创建音频；最后peer释放 aggregate demand |
| A11 页面/进程 | GroupServiceLifecycleTest、GroupScreenComposeTest返回不Leave、新Service不自动启动 |
| A12 部分就绪 | 六pair双方证据、缺I/O/route不ready；界面展示未就绪双方名字 |
| A13 双人兼容 | 原有完整回归 + 真实socket V2 HELLO/CONNECT_REQUEST/BUSY + 模式清理门禁 |
| A14 权限/主机权力 | 远端End/其他pair拒绝，创建入口必需权限，房主结束确认 |
| A15 清理 | GroupRuntime等待audio与network双方释放、GroupWifiHost UNKNOWN/迟到回调、native热平台仪器测试 |

测试替身用于可控时序；真实加密socket、Android适配器shadow、编排/效果和UI各自有对应验证，不把它们写成四台实际手机闭环已通过。

## 固定源码审查

`motointercom-product-architect` 对 base `8bb8cb7e91ec34cfcdc272af9eac3c2c0358f95b` → head `df632144cf44ec4012a76a46c21c23ce8615359b` 结论 **APPROVED，P0/P1 均为零**。已修复新Service在旧实例清理时的前台启动拒绝路径，以及实际路由变化/连续失效/快速重验证的通知顺序。实际device与revision绑定统计查询；失效通知独立锁存，重新ready之前必先交付撤销。

## 本地模拟器

Android API 36，`MotoIntercom_Graphics_API36`，本轮临时只读实例。已检查主页入口、空房页、六位码启用加入、系统权限流程、创建房间显示六位码、房主独处音频待机、结束确认及结束后回到创建/加入页；crash buffer为空。窄屏360dp与1.5倍字体无截断。此次单机流程不证明外部BLE可发现、四机入会或实际互听。

- [常规页面](images/kum-56/group-idle.png)
- [窄屏与大字体](images/kum-56/group-large-font.png)
- [输入六位码](images/kum-56/group-code-entered.png)
- [房主页面](images/kum-56/group-host.png)

## 最终自动化

固定源码 `df632144`：`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline` 全部通过（2m14s）；91个测试套件、714项测试、零失败/错误/跳过。Lint 0 errors / 78 warnings。debug APK及instrumentation APK均生成。云端API36仪器检查运行后回填。

再次核实远端main为 `880e3ed8b4423100114758232c3f75d93e25b270`，包含用户刚合并的修复；本实现链保留该祖先。

## 真实设备：D01–D12

全部 **NOT RUN**，原因：用户暂时没有实体设备，要求先完成代码与自动化。

包括三人三对听音、四人六对互听、无互联网无路由器、成员进退、60秒/满员恢复、房主失联、进程终止、静音与移除、蓝牙/后台/电话、两小时与四小时稳定性、旧版APK互通及分阶段建联耗时。模拟器与单元测试不能替代这些结果。
