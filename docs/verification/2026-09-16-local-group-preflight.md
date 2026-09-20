# 四人离线对讲：实施前核查记录

日期：2026-09-16。结论：本地基线自动化通过；设计已形成可审查成果；真实 Linear 绑定仍受工具授权问题阻塞，未开始源码行为变更。

## Git 与工作区

- 唯一工作区：`C:/Users/kuma/Documents/摩托对讲机/MotoIntercomApp-local-group`。
- 初始/结束 HEAD：`0f808ad44f70976624f69d242bfb0a72aa3f77f2`。
- 分支：`docs/local-group-intercom-prd`，无 upstream；不能报告远端差异为零。
- 初始干净；新增内容仅为本次设计、审查和验证证据，保持未提交。恢复 issue 绑定后随相应设计/实现单元明确暂存提交，不在 PRD 分支上堆源码实现。
- 未编辑其他 worktree，未 stash、回滚、复制其未提交内容；未 push、合并、发布、部署。

## 构建门禁

JDK：`F:/Android/jbr`。SDK：`C:/Users/kuma/AppData/Local/Android/Sdk`。全程使用现有依赖离线构建。

首轮在中文绝对路径运行 `testDebugUnitTest lintDebug assembleDebug --offline --console=plain`，Gradle `BUILD FAILED in 2m 18s`，测试报告出现 `ClassNotFoundException: com.kuma.motointercom.AdaptiveWindowTest` 等测试类加载异常。没有把这轮记成测试通过；原始日志保存在 `evidence/2026-09-16-local-group-baseline/unicode-build.txt`。

确认 M: 无既有映射后，将 M: 临时映射到同一 worktree，从 M: 执行：

```powershell
$env:JAVA_HOME='F:/Android/jbr'
$env:ANDROID_HOME='C:/Users/kuma/AppData/Local/Android/Sdk'
./gradlew.bat clean testDebugUnitTest lintDebug assembleDebug --offline --console=plain
```

结果：`BUILD SUCCESSFUL in 3m 3s`，58 tasks executed。

- JVM/Robolectric：70 suites、566 tests，failures=0、errors=0、skipped=0；汇总来自本次 TEST-*.xml，不复用历史数字。
- lintDebug：0 Error/Fatal，69 Warning；保存本次 XML。
- assembleDebug：通过；只是未改动源码的基线 Debug 构建，不代表群组功能 APK。
- 中文路径问题通过映射规避，未修改 Gradle 配置，也不声称该工具链问题已修复。
- 结束后移除本次 M: 映射及根目录临时日志；长期证据保留在本目录。

## 工具与权限证据

1. Linear `list_issues(query="Moto")` 首次返回 `UNAUTHORIZED`、`oauth_token_invalid_grant`、`Reauthentication required`。
2. 用户在本任务回复“已重新连接”后，立即重新调用相同接口，仍返回相同错误。没有把用户操作视作未执行，也没有循环要求重复登录。
3. `web-access` 前置检查报告 Node v24.14.1、Chrome port 9222、proxy ready；但 `cua.getState()` 和随后 `createBrowserTab("chrome", "https://linear.app")` 都失败于 `nodeRepl.fetch request failed`，没有成功读取 Linear 页面或创建任务。
4. Rasen `--help`、`context --json`、`list --json` 均在 DO_NOT_TRACK=1 / RASEN_TELEMETRY=0 下执行；确认 root 为当前 worktree。已有本地 change 未发现本次四人需求的专用条目，但本地列表不能代替实时 Linear 路线。
5. 未创建 issue、Rasen change、实现分支或 PR；否则将无法遵守真实 issue 一对一绑定约定。

## 设备与未验证项

本次 `adb devices -l` 没有设备。PRD D01–D12 全部 NOT RUN；没有四机、三机听音、P2P 客户端互达、蓝牙来电、两小时稳定性或四小时观察证据。也未运行模拟器 instrumentation；不以基线单测代替群组验收。

## 已落地设计

见 `../design/local-group/2026-09-16-implementation-design-draft.md`：分离名单 revision、逐成员 incarnation、控制连接 generation 和媒体 link generation；明确容量串行裁决、60 秒占位、显式进程重入、移除限制、共享音频资源边界、无关成员不影响健康链路、独立协议与凭码发现待验证项。未将 PAKE/BLE 候选写成既定产品决策。

恢复 Linear 工具后，先查询已有需求/依赖/Exit Criteria，再建立真实活动 issue 对应的 Rasen change 和实现分支。首个候选单元为独立纯域模型；wire codec、预 IP 认证和媒体集成须在各自设计收敛后获准。无需重新开展需求访谈或再询问是否开发。
