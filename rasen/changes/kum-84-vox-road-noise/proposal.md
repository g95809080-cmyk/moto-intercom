## Why

当前VOX用native NS前的PCM总能量，仅LISTENING学习底噪；OPEN/HANGOVER持续高于close threshold就无限续期。已用生产VoxGate复现：30校准→70语音段→45背景30秒，灵敏度20/50/80均3000/3000帧持续OPEN。该结果不等于真实误开麦率。

固定SDK软件NS已请求，不能从字符串推断效果；Android AGC为固定数字模式，不能先认定自适应增益造成路噪。需要实际native发送、远端解码的同输入NS/AGC组合对比。

## What Changes

- 修复活跃态底噪冻结与恒定背景无限拖尾续期，让灵敏度影响实际开/关；评估轻量语音特征/检测能力与实时开销，保护持续语音和字头/句尾，不采用固定最长开麦时限。
- 原始采集供持续检测，实际PCM mute与settings/current producer约束保留；日志对应实际门控及处理指标。
- 只纳入native A/B证据支持的处理或配置修复，保留AEC、全双工、混播，不新增SDK或用户功能。

## Validation

确定性安静/持续语音/稳态低频背景/噪声上升/脉冲/喇叭类合成fixture，灵敏度拖动、mute、旧producer；实际native发送→对端解码NS/AGC对比。固定Source审查、官方OCR逐路径覆盖、JVM/Lint/双APK/API36/Draft PR/固定Source CI。真实道路语音与双机听音另待条件。
