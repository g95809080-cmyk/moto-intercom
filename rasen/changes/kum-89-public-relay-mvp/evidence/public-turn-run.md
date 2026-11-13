# 2026-10-17 公网 TURN 双模拟器音频验证

结论：两台 API 36 x86_64 Android 模拟器通过 `101.33.205.188:3478` 上既有的 coturn，完成强制 relay 的双向原生音频探针。两端同属通话 `1e1271bf-00ee-4ebd-a25d-8956d810bbaa`，instrumentation 均为 `OK (1 test)`；没有使用麦克风或耳机。

## 环境与运行

- 已审查源码：`dcb426c43d227d07e00ab92503bfee1bbf0bad5a`；运行时仓库 Head 为 `7dbc52eeb49367f51cbb5016b0aff9ed09a2a365`，其差异仅为文档与证据。本次未修改源码。
- 云端 Ubuntu 22.04，coturn `4.5.2-3.1~ubuntu22.04.1`；UDP/TCP 3478，`external-ip=101.33.205.188/10.1.0.6`，中继 UDP 49160–49200，`max-bps=16000`。3 Mbps 带宽为交接文档所述套餐信息，本轮未做吞吐压测。
- 信令仍为电脑上的 Node.js 24.14.1 鉴权 HTTPS，经 `https://10.0.2.2:8443` 访问，保留专用 CA 和主机名校验。云端 `sig` 裸 WS 未参与本次通话。
- 本机专用 SSH 密钥登录与免密 sudo 均实测通过。通过 SSH 读取既有 TURN 服务配置，生成仅在 Git 忽略目录中的 `server-public.json`；共享 secret 仅供本机信令服务签发每设备一小时凭证，没有输出到日志、下发给客户端或写入仓库。
- 使用既有 APK 与 `tools/relay/run-probes.ps1 -BuildRoot M:/`。探针各持有一个 runtime owner、AudioSessionController/ADM 和 PC，通过既有捕获处理/APM/Opus/网络/解码链路验证不同频率的测试音。

复现时，在已准备的验证工作区设置 `MOTOCOM_RELAY_CONFIG` 指向被忽略的 `tools/relay/.local/server-public.json`，运行 `node tools/relay/server.mjs`，再执行上述双设备脚本。设备配置、TLS 文件和服务端配置仍须保存在私有目录；不要将环境凭据复制进本证据。

## 实测结果

| 端点 | 实际 selected pair | RTP 发送增量 | RTP 接收增量 | 对端解码测试音帧 | 结果 |
| --- | --- | --- | --- | --- | --- |
| emulator-5554 / probe-a | relay / relay，UDP，succeeded | 46007 bytes | 9207 bytes | 106 @1000 Hz | PASS |
| emulator-5556 / probe-b | relay / relay，UDP，succeeded | 9207 bytes | 46007 bytes | 2179 @500 Hz | PASS |

两端分别运行约 30.95 s 和 30.86 s，满足既定探针门槛：真实 selected pair、双向 RTP 增长 >1000 bytes、当前音频 I/O 开启且新鲜、≥100 帧含对端频率的解码 PCM。解码帧数只表示通过频率检测的帧数，不能用作丢包率或语音质量评分。

云端只读观察窗口为北京时间 12:35:03–12:35:58，每秒采样 coturn UDP socket，看到 `10.1.0.6` 上的 49171、49180、49184、49189，各持续约 30 次采样，均在既定端口范围内。这是同时期分配 socket 的旁证，未将其中某个端口标为最终 selected port。现有文件日志和 journal 未输出本轮逐会话日志，故没有逐用户名日志证据；未为补日志而重启或改配置。

观察前后 coturn PID 761、sig PID 781 及服务启动时间相同，均 active。本轮仅停止了本机临时 Node 验证进程，保留云端服务。原始无凭证结果、APK SHA256 与云端观察摘要见 [public-turn](public-turn/)。

## 验收边界

本轮证明公网 TURN 媒体路径和双向解码链路可用。`networkType=cellular` 是模拟器虚拟网络枚举，两端 `cellularRequested=false`，不能证明真实蜂窝或 Wi-Fi Direct 共存选路。ICE RTT 15/18 ms 不能替代端到端音频 P95。

合成测试音未覆盖真实麦克风、扬声器回授与耳机声学路径，不能用于 AEC、风噪处理或实际听感验收。

真实蜂窝 + Wi-Fi Direct、P95 音频 ≤300 ms、恢复 P95 ≤3 s、10 次重连 ≥9 次、静默 30 min 后首包 ≤500 ms 且无吞字、双网切换、蓝牙/电话中断以及 iOS 原生 SDK/后台音频仍未验证。生产公网入口与自动切换未在本轮新增；本结果不构成部署、发布或实车验收。

## 独立核对补充与后续前置

2026-10-17 用户转述了另一操作端的独立核对：coturn/sig PID 761/781、服务 active，`turnserver.conf` mtime 仍为 11:27:36，当前 allocation 为 0，12:35 时段无逐会话日志。这些是用户提供的补充记录，本轮没有再次登录服务器采集。服务端配置与进程保持原状的结论与此前原始证据一致。

服务端按用户归因会话与流量仍是缺口。按交接建议，真机测试前拟启用 `verbose`，安排维护窗口重启 coturn；本轮仅记录待办，未执行云端配置变更或重启。是否补齐缺口须以受控测试的实际日志为准，不能只检查配置项已写入。下一轮应关联通话 `callId`、端点与 TURN username，再核对 coturn session、relay 地址/端口、协议及收发字节统计；日志未提供的字段继续标为缺失，不用 `ss` 端口采样代替逐用户归因。

真机测试还需要从蜂窝网络可达、TLS 校验有效的鉴权 HTTPS 信令端点；本轮 `10.0.2.2` 是模拟器访问电脑的地址，不能直接给 4G 真机使用。

后续按下列顺序逐步增加变量，不改变已采纳 ADR 的阈值与单媒体会话约束：

1. 先完成日志可观测性验证。一台 4G 真机与本轮已通过的模拟器配对，真机强制 relay，核对实际 selected pair、双向 RTP、对端解码及服务端归因；只有单端 allocation 或 relay candidate 时，仅记录该分段结果。
2. 再换成两台 4G 真机，重复双向媒体与服务端归因检查，随后验证真实声学路径。
3. 在 Wi-Fi Direct 保持连接、媒体强制 relay 的状态下采集端到端音频、恢复及静默首包的 P50/P95，并保存实际蜂窝媒体选路证据。ICE RTT、信令 Network 绑定及 Wi-Fi Direct 开关状态均不能单独替代这项验收。

以上均为后续待执行项，未计入本轮 PASS。

后续执行记录：用户另行明确批准后，已启用 verbose 并用临时公网鉴权 HTTPS 完成小米 13＋模拟器蜂窝开发探针，详见 [phone-public-run.md](phone-public-run.md)。该后续运行独立记录云端重启、失败样本、两轮通过和收尾状态，不改写上面 12:35 双模拟器测试的历史环境或结论。
