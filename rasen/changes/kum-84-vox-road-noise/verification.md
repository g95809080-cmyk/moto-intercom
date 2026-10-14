# KUM-84 验证

Base `9819833c81b13474609af104c8a3959f5480109d`；固定Source Head `f587472e251b59f3c25e8cad5e483c8e0afc887a` 的正式只读审查为APPROVED，P0/P1=0，12/12路径覆盖（11全文+1差异上下文，0 skipped）。[Draft PR #57](https://github.com/g95809080-cmyk/moto-intercom/pull/57) 已建立并附到当前任务，独立基于KUM-83。固定Source [Android CI 38239675696](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/38239675696) 已核对headSha为f587472并确认completed/success，verify job `114778910524` 与API36 job `114778910314` 均成功。KUM-84已进入In Review；实机/道路验收仍未通过，iOS跳过。

## 真实 native NS/AGC 对比

Owned API36 AVD 四组测试已通过。首PCM等双方实际 CONNECTED 和 receiver sink 注册后才放行；固定200帧预热，实际PCM16/48000Hz/mono/960bytes，96000样本，input与生产bypass后CRC32均为1962860226；其中一组故意延迟Offer500ms仍保持同输入历史。之前连接预热未固定的首轮结果作废，未用于配置选择。

| NS | AGC | 初始噪声 RMS | 语音类混合 RMS | 返回噪声 RMS | Java gate P95 µs |
| --- | --- | ---: | ---: | ---: | ---: |
| off | off | 501.98 | 1189.57 | 2472.09 | 202.7 |
| off | on | 1137.28 | 3250.91 | 3698.33 | 165.2 |
| on | off | 64.91 | 1161.53 | 55.36 | 143.9 |
| on | on | 82.42 | 3221.24 | 171.38 | 141.9 |

实际解码每段38400样本。Source采样窗口分别位于200..280、480..560、920..1000附近，receiver没有逐帧对应；不能宣称精确语音对齐、真实SNR或可懂度。P95只计Java回调，不含native APM/codec/端到端延迟。测量支持保留当前NS=true/AGC=true/AEC与高通；没有切换默认增益、SDK或叠加新降噪。

## 复现与候选边界

原门控回归共13条（原6+新7）中4条失败；最初候选全部通过。进一步窗口/软语音/脉冲补强共17条（原6+新11）中3条失败，暴露慢调制误学、采样缺口充当稳态时间、瞬时脉冲抬高accepted peak；修复后17条全绿。实际生产编译类重新运行原30秒复现：三个灵敏度均由3000/3000背景发送帧变为141/3000（含有限尾音），最终LISTENING/floor45，无反复开麦。合成指标不等于真实误开麦率。

实际engine方法的旧producer延迟回归确实失败：controls lock等待期间换owner，旧帧仍把noiseFloor32改为36.5959。该用例是生产方法+模拟owner交接，真实native producer连续采集由另一用例验证，不将模拟交接称为原生线程实际重启。

候选只学习accepted peak回落至少18dB、距离已学floor不超过20dB、完整600ms窗口整体范围不超过1.5dB的平台；大于60ms采样缺口或逆时撤候选。峰值须经历25ms连续attack才接受。保留25ms开麦attack、120msrelease和700ms尾音；不设置最大说话总时长。初始校准上限55保持，后续已有噪声学习可至85，防止较强已知背景被旧55上限反复打开。能量歧义、突升不回落的稳态噪声和真实字头/句尾听音仍待验收。

本地完整 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 成功：96 suites / 804 JVM tests / failures=0 / errors=0 / skipped=0；Lint errors=0，70 warnings与基线一致。最后的测试采样调整发生在Source提交之前，之后Lint/测试APK也通过；Source提交及批准后，JVM、原生测试与生产源码均未再变。严格Rasen validate有效。

Owned API36全量instrumentation为29 pass / 2 双机条件 assumption skip（status -4）/ 0 failure；Runner OK31包含skip。实际 native VOX强背景约1440ms后关麦，之后继续采集且30个新帧真实JNI输入清零；新前景加6帧观察97ms。活跃门控Java P95约231.8µs，不含native APM/codec或端到端延迟。旧producer竞态红转绿，现有mute/VOX bypass与撤权回归均通过。

候选Debug APK只安装到本次owned AVD，未部署小米13。SHA256 `EB5F258808938957FC9226A6004EA695C1C92D93CE7534B6E0472836A022AE46`；版本、versionCode与approvedCommit均保持原权威，不作为新功能发布。

## 官方OCR与固定Source审查

官方 Alibaba Open Code Review 1.12.13 delegate preview/rule为12 changed paths，5 selected + 7 excluded；只读架构按官方规则加手动覆盖全部excluded的JUnit/合同/证据。实际depth为11全文+1 RiderAudioEngine差异上下文，P0/P1=0，不宣称大型未改段全文。完整path/blob/depth见 `coverage.json`；未调用远程OCR LLM，不把规则获取称为模型批准。

唯一非阻断建议：现有70→58±3慢调制在18dB条件下不进入候选，可另补floor45→peak80→58±3/1秒周期直接保护整体范围规则；生产min/max实现正确，未确认行为缺陷。

Next gate allowed：纯证据绑定复审、确认app/测试/构建/workflow/版本blob与Source一致；独立Draft PR及固定Source Android CI。真实两手机/蓝牙/道路听音deferred，不标记Done或发布。

证据绑定复核 Base `9819833c` / Docs Head `e0aff559c95e20e9da4f9c76d9a1487346c7dae6` 为APPROVED，P0/P1=0；仅4项Rasen文档变化，12项Source路径及4额外上下文blob全部匹配。允许补固定Source CI/Linear真实结果；后续纯证据文档复用原Source门禁与相同代码CI，无需重复行为CI。

CI与Linear证据已闭合，本问题自动化任务完成；task7双手机/普通蓝牙/道路听音保持未勾选。GitHub实际run/job结果保留在忽略 `kum-84-ci-result.json`。未自动合并main、发布或部署；source之后仅补文档且已检查app/测试/构建/workflow/version blob一致。

详细输出保留在忽略的 `logs/2026-10-12-field-review/`，包括 `kum-84-native-ab-controlled.json`、`kum-84-vox-red.log`、`kum-84-vox-window-red.log`、`kum-84-vox-peak-red.log`、`kum-84-native-owner-red.txt`。不提交原始用户日志。
