# 群组电台 R1 实施记录

依据：[群组 PRD](../product/2026-11-17-motocom-group-radio-prd.md)。本次执行 R1 工程 MVP；R2 的角色/领队/私聊、R3 网络、R4 大群和 R5 双模按原版本顺序推进。

## 实现边界

主工作区基线为 `main` 的 `7041d3c461fe6e4530d94e3cc7d30c72f48c85c6`。四人参考实现来自 `four-person` 的 `091c7dcc22a438ee66e3ac3b60c6ac35c4a83dbf`，只逐文件复用群组模块及相应测试，不覆盖主线 Service、音频路由、RNNoise、诊断和恢复实现。

本轮提供 `groupPreview` 独立构建变体，包名为 `com.kuma.motointercom.group.preview`。该变体直接进入群组页，不注册双人首页与双人 Service。蓝牙发现权限、群组协议、PAKE 依赖、群组 Service/UI 仅进入预览变体。已有 `1.x` 和 `2.x` 的发布基线不变。

`version.properties` 的 `groupPreviewVersion` 与 `groupPreviewVersionCode` 管理独立预览包的版本。预览包使用调试签名，不能用来覆盖正式包。正式发布时仍按版本约定核验批准的功能提交、签名和全局递增 code。

## 执行顺序与验收

| 步骤 | 交付 | 验收 |
|---|---|---|
| 1 | 逐文件复用四人领域、认证、控制与网络模块 | 容量/认证/错房间/成员代次/旧回调/GO 释放测试 |
| 2 | 主线共享音频平台支持最多三个成员媒体会话 | 默认 SINGLE 保持一个；成员播放独立静音；关闭一人不撤销其余人 |
| 3 | 群组前台 Service 与公共频道 UI | 创建/六位码加入/成员列表/移除/解除限制/结束确认 |
| 4 | 固定 60 秒恢复窗口 | 反复失败不延长；到期退出；迟到认证不复活；期限内恢复保留原群 |
| 5 | 自动验证与架构复审 | `testDebugUnitTest`、`testGroupPreviewUnitTest`、Lint、两个 APK 构建 |
| 6 | 设备验收 | 四台真机、六对互听、无路由器、蓝牙与 Wi-Fi 共存、两小时稳定 |

步骤 1–5 的工程实现和检查已完成：双人单元测试 876 项、群组及共享音频单元测试 162 项、模拟器原生音频测试 4 项通过；两个变体 Lint 无错误，预览、双人和 instrumentation APK 均构建成功。只读架构复审为 `APPROVED`，无剩余 P0/P1。

模拟器已完成权限拒绝、创建、收起回房、结束确认、无线关闭错误、再次创建和取消搜索的检查。小米 13 已通过单机建组、收起回房、结束取消/确认、GO 释放、再次建组并结束，以及四项原生媒体回归；原生 RTP 验证是同设备、同进程环回。步骤 6 仍待四台真机；R1 产品验收尚未完成。

## 状态与职责

- `GroupSessionOrchestrator` 是群组业务状态单写者；`GroupIntercomService` 只装配、执行操作和托管前台生命周期。
- `GroupRuntime` 等待音频平台及网络都完成释放，才释放本进程会话所有权；GO 处于未知状态时阻止新会话。
- R1 中群主与本地 GO 宿主相同；后续网络模式再拆业务群主与网络宿主。
- 同一引擎共享 ADM、factory、本地音轨和 PCM 静音；SDP/ICE、远端音轨、播放和成员证据各自归属于媒体会话。
- 房间码只在群主页面显示和主动复制；广播、通知和日志不包含房间码或认证凭证。
- 被移除成员立即关闭控制和媒体；解除限制只恢复准入资格，不自动加入。
- 未验证的附近候选不泄露房间、成员或移除状态；认证后的控制通道区分满员、被移除和房间结束。

## 检查命令

```powershell
.\gradlew.bat testGroupPreviewUnitTest testDebugUnitTest lintGroupPreview lintDebug assembleGroupPreview assembleDebug --console=plain
```

预览 APK：`app/build/outputs/apk/groupPreview/app-groupPreview.apk`。

## 发布与回滚

本轮不合并、不签发正式发行包、不替换既有 GitHub Release。独立包可以卸载回退，正式包仍使用原入口。共同音频平台的修改必须通过双人回归，不能用预览变体通过替代。

验证结果另记于 `docs/verification/2026-11-17-group-radio-r1.md`。只有四台真机验收完成，R1 才能标为产品验收完成；自动验证通过只代表工程检查通过。
