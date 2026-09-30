# Android UI 与交互契约

规范来源：现有服务与领域模型，2026-09-30 用户确认的首轮方案。旧 Android 改版 PRD 不适用。

## 能力所有者

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| 双人动作/状态 | IntercomActionPolicy / SessionOrchestrator | UI 复用策略，不写领域状态  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |
| 群组快照/动作 | IntercomService / GroupSessionOrchestrator | 观察者仅订阅，动作走现有服务  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |
| 页面导航 | MainScreen / MainActivity / GroupActivity | 返回主页保持通话，显式离开才结束  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |
| 容器与主题 | MotoComPanel / MotoComTheme / resources | 同一主题，原生 Material 语义  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |
| Form | GroupScreen OutlinedTextField / GroupActivity 权限门禁 | 六位 ASCII 数字，当前页面失败保留，IN_ROOM 清空，不保存码  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |
| 弹窗 | Material 3 AlertDialog | 可取消，明确对象和后果，确认才 dispatch  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |
| 音频偏好 | AudioRoutePreferences / AudioControlPreferences | 设置保存默认偏好；群组页仅当前房间  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |
| 滚动 | MainScreen ScrollView / GroupScreen verticalScroll | 单一页面滚动所有者，安全区与键盘避让  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |
| 反馈 | 服务消息与页面状态文本 | 真实状态为准，关键错误保留在页面，不只 Toast  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |
| 诊断披露 | SettingsScreen | 默认折叠，可展开查看原有事实  UX-CONTRACT.md | 原生 Android | JVM 回归与模拟器 |

## 状态与恢复

首页 busy 群组显示返回入口和实际服务消息，隐藏双人操作。未活动群组仍可进入准备页；双人活动时不新增模式切换。订阅随 Activity 绑定/停止注册/移除，断开清除服务事实，重新绑定回放当前值。

房间创建/加入：点击 → 原有权限请求 → 服务创建/搜索/加入 → 快照反馈。等待期间阻止重复开始。拒绝权限/启动失败/搜索失败保留当前页输入并说明重试；成功以 IN_ROOM 快照为准。房间码不进入 Bundle、偏好、URL 或日志。

房主结束全队、移出指定成员必须确认；取消不发送动作。其他成员只能离开自己。静音他人只作用本机收听，文案不能声称全队静音。解除移除限制仅房主可见。全队语音就绪仅使用 snapshot.voiceReady。

## 设备与可访问性

简体中文文本与系统数字键盘；原生按钮、开关、文本输入、RadioButton 和模态语义。关键操作48dp、长昵称换行；所有区域可滚动。点击和键盘辅助操作语义相同。群组选择状态不能仅依赖绿色，使用原生 selected 语义。系统动画关闭不改变通话状态。

## 验证范围

JVM 回归、Lint 与 Debug APK 检查代码和呈现。模拟器可检查布局与失败恢复，不代表实际离线多机网络、蓝牙路由或听感验收。
