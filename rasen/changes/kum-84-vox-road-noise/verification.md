# KUM-84 验证

Base `9819833c81b13474609af104c8a3959f5480109d`；当前为独立WIP，无源码Head批准、PR或本问题CI通过结论。沿用前置KUM-83的固定Source及文档绑定批准，不代表本问题处理方案已验收。先补实际native A/B与确定性红绿，再进行固定Source审核及自动门禁。

## 真实 native NS/AGC 对比

Owned API36 AVD 四组测试已通过。首PCM等双方实际 CONNECTED 和 receiver sink 注册后才放行；固定200帧预热，实际PCM16/48000Hz/mono/960bytes，96000样本，input与生产bypass后CRC32均为1962860226；其中一组故意延迟Offer500ms仍保持同输入历史。之前连接预热未固定的首轮结果作废，未用于配置选择。

| NS | AGC | 初始噪声 RMS | 语音类混合 RMS | 返回噪声 RMS | Java gate P95 µs |
| --- | --- | ---: | ---: | ---: | ---: |
| off | off | 507.02 | 1188.33 | 2406.94 | 208.2 |
| off | on | 1137.28 | 3244.93 | 3698.33 | 184.5 |
| on | off | 62.13 | 1162.60 | 55.22 | 145.5 |
| on | on | 95.48 | 3242.07 | 171.12 | 136.0 |

实际解码每段38400样本。Source采样窗口分别位于200..280、480..560、920..1000附近，receiver没有逐帧对应；不能宣称精确语音对齐、真实SNR或可懂度。P95只计Java回调，不含native APM/codec/端到端延迟。测量支持保留当前NS=true/AGC=true/AEC与高通；没有切换默认增益、SDK或叠加新降噪。

## 复现与候选边界

原门控回归共13条（原6+新7）中4条失败；最初候选全部通过。进一步窗口/软语音/脉冲补强共17条（原6+新11）中3条失败，暴露慢调制误学、采样缺口充当稳态时间、瞬时脉冲抬高accepted peak；修复后17条全绿。实际生产编译类重新运行原30秒复现：三个灵敏度均由3000/3000背景发送帧变为141/3000（含有限尾音），最终LISTENING/floor45，无反复开麦。合成指标不等于真实误开麦率。

实际engine方法的旧producer延迟回归确实失败：controls lock等待期间换owner，旧帧仍把noiseFloor32改为36.5959。该用例是生产方法+模拟owner交接，真实native producer连续采集由另一用例验证，不将模拟交接称为原生线程实际重启。

候选只学习accepted peak回落至少18dB、距离已学floor不超过20dB、完整600ms窗口整体范围不超过1.5dB的平台；大于60ms采样缺口或逆时撤候选。峰值须经历25ms连续attack才接受。保留25ms开麦attack、120msrelease和700ms尾音；不设置最大说话总时长。初始校准上限55保持，后续已有噪声学习可至85，防止较强已知背景被旧55上限反复打开。能量歧义、突升不回落的稳态噪声和真实字头/句尾听音仍待验收。

本地完整 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 成功：96 suites / 804 JVM tests / failures=0 / errors=0 / skipped=0；Lint errors=0，70 warnings与基线一致。只读Source gate与owned API36全量原生结果尚待闭合，不能把专项/JVM通过记为全部验收。

详细输出保留在忽略的 `logs/2026-10-12-field-review/`，包括 `kum-84-native-ab-controlled.json`、`kum-84-vox-red.log`、`kum-84-vox-window-red.log`、`kum-84-vox-peak-red.log`、`kum-84-native-owner-red.txt`。不提交原始用户日志。
