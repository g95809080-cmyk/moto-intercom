# KUM-50 启动权限与后台运行验证

源码提交：`f5e0731964319553ff6f6450b36be069226e1eb2`。
基线：`v1.2.0` / `d479ec1dec5c4c197785d69b88d0872a366946cb`。

## 完成内容

跳过引导和引导授权均进入统一检查：核心权限、蓝牙/通知可选权限、Wi-Fi、系统位置开关、后台运行建议。授权结果后在 Activity 恢复前台时续接启动；核心拒绝停止，再次启动可进入系统权限设置；蓝牙/通知拒绝仍可用手机音频。位置缺失停止无线发现及 BUSY 重试，保留连接失败清理。

首次后台设置提示支持跳过；系统电池优化豁免申请支持拒绝后继续；设置页保留入口并查询真实豁免状态。小米/荣耀额外省电和启动管理仅提供指引，不声称已自动配置。

## 自动化

- 71 suites / 579 tests；0 failures、0 errors、0 skipped。
- `lintDebug`：0 Error、77 Warning。
- `assembleDebug`：通过。
- 控制器 11 项专项覆盖 API 23/32/33、跳过引导、核心拒绝/重试、蓝牙通知拒绝、设置返回、后台跳过/拒绝、等待状态恢复。
- MainActivity 集成测试确认恢复前台前不发启动服务请求；无线回归确认缺少权限时仍执行失败清理。
- 未改动音频参数和编解码。

本机中文目录触发历史测试类加载问题，使用 `subst V:` 映射本工作树运行。全量首次默认测试堆内存不足导致两项 OOM；使用临时 Gradle init 配置 `tasks.withType(Test).configureEach { maxHeapSize='2g'; maxParallelForks=1 }` 重跑全量通过，项目构建配置未修改。

执行：`gradlew.bat --init-script <temporary-memory-init.gradle> :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain`。
JDK：本机缓存 Eclipse Adoptium 21.0.7；Android SDK：本机 SDK；Gradle：项目 Wrapper 9.5.0。

## 安装包

`MotoCom-KUM50-startup-background-debug.apk` 是调试测试包，版本仍为 1.2.0 / versionCode 3，不是正式发行。

SHA-256：`c633554c54175b6e86c84cfa43ec0f4c408f2dc2e1ef2465f06ac0f18be38ea7`。
签名验证通过；证书 SHA-256 `7F20F38DC1D7372CDE34CAC6E0E17D80EC995AC298C298FD0D24605E1A8070F3` 与 v1.2.0 相同。

## 待实机复测

当前 `adb devices -l` 无设备，未验证实际安装、系统授权界面、双机无线或厂商后台策略。

1. 覆盖安装测试包；已有数据不需清除。在系统应用权限中撤销必要权限后返回，可验证补齐路径；重看引导后跳过，点击授权/启动。
2. 允许核心权限后，检查能进入发现并建立双向通话；拒绝核心权限时应明确提示，再次授权后恢复。
3. 关闭系统位置开关后启动，检查提示开启；返回后继续，不应持续显示无线占用。
4. 选择后台运行设置，允许或拒绝均应能继续；设置页状态应与系统电池优化豁免一致。
5. 小米13/荣耀双机配合 V9X/BH1 锁屏骑行复测，音质调整仍搁置。

## 最终只读架构审查

APPROVED；P0=0，P1=0。
Base SHA：d479ec1dec5c4c197785d69b88d0872a366946cb。
Head SHA：f5e0731964319553ff6f6450b36be069226e1eb2。
独立审查已核对测试 XML、Lint 和 APK。Next gate allowed：进入小米13/荣耀双真机验收；实机验收未完成，任务保留 In Review。


## 用户复测与后台状态说明修正

用户确认上一测试包的启动修复可用，但手机后台活动及“不限制”已开启时仍显示未豁免。用户授权修正文案和入口。

源码提交：`9aef96fdec0bdb6bc222ad232ab066ddaae93316`。

- 系统电池优化白名单与厂商后台设置拆为两个面板；前者显示 API 真实结果，后者说明不能自动确认。
- 不将未加入系统白名单解释为厂商后台设置未开启。
- 系统白名单设置和手机应用详情有独立入口。
- 固定 4a65424 -> 9aef96f 只读审查 APPROVED，P0=0、P1=0。
- 本提交 lintDebug / assembleDebug 通过；Lint 0 Error / 77 Warning。此次低影响展示/导航修改未新增或重跑单元测试，上文579项属于前一源码版本。
- 新测试包：`MotoCom-KUM50-background-ui-debug.apk`；SHA-256 `32b83f67926320b9e15091e9e76709d3c32cda71a50425a81fe51281767b9feb`。签名验证通过，与前版证书一致。
- 新的设置展示及两个入口待手机端复测。


## 用户最终确认

用户确认新版后台运行展示“没问题了”，并明确要求推送。结合此前反馈，启动修复与后台设置展示均获用户确认；此确认不扩展为新的半小时骑行或全部厂商后台策略验收。
