# KUM-93 验证记录

## 代码候选

- Base SHA：`761e417be15630ed65384ccb21206d48144c3440`
- 审查候选 tree SHA：`b005e76850e48a3023cd585157d2485a1d228b16`
- 分支：`fix/kum-93-ui-accessibility`

## 自动化门禁

- JVM：102 suites / 853 tests，failures=0、errors=0、skipped=0。
- Lint：0 errors / 71 warnings，warning 数量与基线一致。
- `assembleDebug`：成功，生成 `app-debug.apk`。
- Rasen：`validate kum-93-ui-accessibility --strict --no-interactive --json` 通过。
- 回归覆盖：图标完整语义树中只有一个名称；昵称输入框保留实际 `EditableText`、无 `ContentDescription`、`SetSelection` 更新 `TextSelectionRange`。

## 架构审查

- `motointercom-product-architect` 固定 Base/Tree 只读审查：`APPROVED`。
- P0：0。
- P1：0。
- 审查确认旧 P1 已消除：可编辑节点不再用 `contentDescription` 覆盖文本遍历与选区。

## 交付

- Draft PR：https://github.com/g95809080-cmyk/moto-intercom/pull/67
- Linear：KUM-93 已更新为 `In Review`，验证摘要与待验收限制已记录。
- GitHub Actions CI：在 PR #67 上运行。

## 模拟器验收

- 环境：`emulator-5554`，AVD `MotoIntercom_Graphics_API36`，Android 16 / API 36，`1080x2400`，density 420；App `1.2.3+f587472`。
- 辅助功能：TalkBack `17.1.0.1003738110` 已启用，默认 TTS 为 eSpeak NG `1.52.0`。
- Discover：31/31 个节点可见；`返回首页`、`打开帮助与反馈` 各 1 个可见节点。
- Settings：83 个节点、64 个可见；`返回首页`、`保存昵称` 各 1 个可见节点，`打开帮助与反馈` 在屏外；仅 1 个可见 `EditText`，`content-desc` 为空，`本机昵称` 为独立标签。
- 昵称：输入并保存后重新进入 Settings 仍为 `RoadCaptain`；应用私有偏好为 `rider_name=RoadCaptain`。剪贴板复读测试后已恢复输入框为 `RoadCaptain`，未保存测试值。
- TalkBack 焦点：截图确认 Discover 的标题与帮助入口，以及 Settings 的返回、昵称标签、昵称输入、保存按钮均有绿色焦点框。
- 朗读动作：输入 `Meta+Ctrl+C` 后出现系统剪贴板预览；粘贴到昵称框读回 `摩声`。`audioflinger` 与 `dumpsys audio` 显示 TalkBack 创建并释放约 22.05 kHz 音频轨道，并以 `USAGE_ASSISTANCE_ACCESSIBILITY` 请求音频焦点。
- 证据：`build/emulator-evidence/kum-93-20261107-005941/`。

## 未覆盖

- 模拟器以 `-no-audio -no-window` 启动，无法验证真实声卡输出、完整朗读顺序、发音、语速和听感；音频轨道与剪贴板文本不能替代有声音频验收。
- 需在有声扬声器环境或真机上听读 Discover/Settings 全流程，尤其确认 `本机昵称`、`RoadCaptain` 和焦点顺序。
