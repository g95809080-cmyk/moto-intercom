# 2026-10-17 小米 13＋模拟器公网 relay 验证

结论：一台小米 13 与 `emulator-5554` 经公网鉴权 HTTPS 和 coturn，连续两轮通过完整开发探针。手机信令使用蜂窝 Network 225，实际 selected pair 的 `networkType=cellular`，两端均为 `relay/relay`、UDP、succeeded。手机网络属性为 `NR_SA,NR_SA`，因此本次记录为真实蜂窝/5G 验证，不写成指定 4G 制式验证。

这是单真机＋模拟器的合成音开发验证，不是生产公网入口、双真机、Wi-Fi Direct 共存或音频 P95 验收。此前三轮失败均归档，没有降低门槛或只保留成功样本。

## 实施范围与环境

- 生产源码仍为已审查 `dcb426c43d227d07e00ab92503bfee1bbf0bad5a`。新增源码提交 `5f5727e89f3aa01b7eb72d328b914b1a0bc96043` 只修改 AndroidTest：失败诊断及默认关闭的 `requireCellularMedia`；独立架构复审 APPROVED，P0=0/P1=0，见 [review-history.md](review-history.md)。
- 手机为 Xiaomi 13 / 2211133C / API 36 / arm64。安装独立包 `com.kuma.motointercom.relay.validation` 及其 instrumentation，复用原生 AudioSessionController/ADM/PC。原正式包 `1.2.3+f587472`（code 7）与 RNNoise beta（code 9）版本未变；结束检查三包均无活动 Service。Wi-Fi 保持开启以维持无线 ADB，未全局绑定进程网络或更改手机全局 CA。
- 信令为 `https://101.33.205.188:443`，专用测试 CA 保留 IP 主机名校验。Ubuntu 上使用官方 SHA256 校验的 Node.js 24.22.0；服务端 13 项测试通过。公网无效凭证请求实际返回 401。
- 每端一个 owner、controller、ADM 和 PC。手机在同一 RTC executor、当前租约/session/PC 上设置 `networkPreference=CELLULAR`，native 接受后才执行 offer/answer。偏好本身不算选网证据，开启模式后必须实际选中 cellular；其余 relay/RTP/音频 I/O/100 帧门槛保持。

## 云端变更与恢复状态

用户明确批准清单后，先核对中继端口段没有活跃分配 socket，再只向 `/etc/turnserver.conf` 追加 `verbose` 并重启 coturn。配置备份保留在服务器私有目录；变更前后 SHA256 和收尾结果见 [phone-public](phone-public/) 中的 receipt。

- coturn：13:33:59 CST 重启，PID 从 761 变为 30755，结束时 active；配置只增加 `verbose`，端口范围、限速、鉴权、secret 均保持原值。
- 原 sig：PID 781、11:31:59 CST 启动，整个测试期间保持 active。
- 临时 HTTPS：以 ubuntu 身份通过 systemd transient unit 启动，只赋予绑定低端口能力，最长运行 1 小时，无持久 unit 或开机启用。14:01:25 CST 已主动停止，443 不再监听；`/opt/motocom-relay-validation` 的运行材料仍保留，后续测试需重新启动临时服务。
- 结束检查：49160–49200 段没有 coturn UDP socket 残留。这里只报告所观测 socket 与会话关闭日志，不冒充未提供的全局 allocation 计数接口。

## 全部轮次

时间为北京时间，开始时间取服务端抓取窗口。手机一直发送 500 Hz，模拟器输入频率在第 3 轮改为配置允许的 700 Hz；采集、检测算法和通过阈值未改。

| 轮次 | 开始 | 条件与观测 | 结论 |
| --- | --- | --- | --- |
| 1 | 13:41:21 | 原探针；只绑定蜂窝信令，手机实际媒体为 Wi-Fi，手机 788 帧 @1000 Hz；模拟器未满足完整判据，旧快照无法细分门槛 | FAIL；不能算双端或蜂窝通过 |
| 2 | 13:54:46 | 新诊断＋蜂窝媒体模式；手机已实际 cellular，RTP/当前音频正常，但只有 56 帧 @1000 Hz；模拟器 2107 帧 @500 Hz 并通过 | FAIL；保留 100 帧阈值 |
| 3 | 13:56:29 | 改为 500/700 Hz；手机 events 长轮询 `SocketTimeoutException`，对端随后关闭，未取得选中媒体路径 | FAIL；不能用于比较测试音效果 |
| 4 | 13:58:15 | 同样的 500/700 Hz 配置，双方 instrumentation 均 `OK (1 test)` | PASS |
| 5 | 13:59:44 | 不改源码、配置、设备与阈值的复测，双方 instrumentation 均 `OK (1 test)` | PASS |

频率调整用于检查既有静态测试音的不稳定性。第 2 轮的数据不足以确认 APM、编解码或其他环节是根因；两轮通过不能推算可靠性或语音质量，长轮询超时也没有在本次代码里通过自动重试掩盖。

| 轮次 / 端点 | 实际 selected 网络与 pair | RTP 发送增量 | RTP 接收增量 | 对端解码测试音 |
| --- | --- | --- | --- | --- |
| 4 / 手机 | cellular，relay/relay，UDP | 40563 bytes | 18466 bytes | 167 帧 @700 Hz |
| 4 / 模拟器 | 虚拟 cellular，relay/relay，UDP | 23552 bytes | 40563 bytes | 2059 帧 @500 Hz |
| 5 / 手机 | cellular，relay/relay，UDP | 40462 bytes | 61337 bytes | 764 帧 @700 Hz |
| 5 / 模拟器 | 虚拟 cellular，relay/relay，UDP | 61373 bytes | 40462 bytes | 2102 帧 @500 Hz |

第 4、5 轮通话分别为 `ee104f41-aacb-4410-8931-f4d5aceb2cd2`、`27df9c9e-6d6e-4bf6-a992-ba05bef19fc1`。每轮双方同 call、真实 selected pair succeeded、双向 RTP 增长 >1000 bytes、当前音频 I/O 开启且新鲜、对端频率 ≥100 帧全部满足。各端采样时刻不同，RTP 差值不用于计算丢包率。

## 服务端归因证据

每轮均记录到本窗口新建的 6 个已鉴权 coturn session，关联 `<expiry>:probe-a` / `<expiry>:probe-b`，包含 ALLOCATE、REFRESH、usage/peer usage 和关闭记录。第 4、5 轮还取得 permission/channel 记录；第 3 轮有 ALLOCATE/permission，但没有 CHANNEL_BIND，不能算媒体通过。第 4、5 轮抓包分别为 581946、773025 bytes；PCAP 原件保留在服务器私有目录，提交其 SHA256、流量汇总和脱敏日志。

归档器按 coturn 进程相对时间及本轮 `new` 事件筛选 session，剔除日志延迟刷新带入的旧轮次；早期现场 summary 的 11/8 个计数已在归档汇总中纠正为各 6 个，原始私有记录保留。

早期 summary 曾将“手机选中 cellular”与整体探针 PASS 合并计算。派生归档已改为独立观察：第 2 轮是 `true`（帧数不足仍使探针失败），第 3 轮为 `null`（未取得 selected 路径），其余按双端原始 JSON 记录；原始私有 summary 未改。

本轮已补上用户名、session、协议及收发统计的服务端证据。客户端 JSON 没有 selected candidate 的地址/端口，现有日志也未建立完整的 selected pair → allocation → relay port 映射；`ss` 和抓包中观察到的分配端口不能标为最终 selected port。这部分仍待补。

## 复现入口与验证

源码运行方式见 [tools/relay/README.md](../../../../tools/relay/README.md)。本机验证材料位于 Git 忽略的 `tools/relay/.local/phone-cloud-candidate`、`phone-apks` 和 `run-phone-pair.py`：

1. 在服务器以相同已暂存 Node/server.json 启动有 1 小时上限的 `motocom-relay-validation` transient unit；沿用专用证书与主机名校验，测试前核对服务状态、证书有效期和设备蜂窝网络。
2. 正常 instrumentation APK 只装模拟器；手机 APK/test APK 使用 `.local/relay-phone.init.gradle` 改独立 applicationId，避免覆盖正式 App。APK 与固定源码关系见 [run-manifest.json](phone-public/run-manifest.json)。
3. 将两端私有配置放入各自 app 私有 files 目录，手机开启 `requireCellular:true`、`requireCellularMedia:true`，两端发送/期望频率互相匹配；执行 `python tools/relay/.local/run-phone-pair.py`。该脚本仅适用于此次已配对设备与本机 SSH 环境。
4. 同时保存两端 instrumentation、JSON、coturn 本窗口会话及抓包摘要；结束释放媒体、检查中继 socket，并停止临时 HTTPS。不要把完整配置或 PCAP 加入仓库。

新增测试源码本地 `assembleDebugAndroidTest`、`lintDebug` 和独立包构建成功；服务端 Ubuntu Node 测试 13/13。远程 CI 以 [PR #62 checks](https://github.com/g95809080-cmyk/moto-intercom/pull/62/checks) 为准。原始不含凭证的双端结果、失败轮次、服务状态、APK/PCAP 哈希见 [phone-public](phone-public/)。

## 尚未验收

真实 4G 制式、双真机与真实麦克风/扬声器/耳机声学路径、AEC/风噪、Wi-Fi Direct 保持连接时的实际蜂窝媒体、端到端音频 P95 ≤300 ms、恢复 P95 ≤3 s、10 次重连 ≥9 次、静默 30 min 后首包 ≤500 ms、蓝牙/电话及 iOS native/后台均未验。ICE RTT 不等于音频 P95。尚未进行生产入口整合、自动切换、合并、发布签名或发布。
