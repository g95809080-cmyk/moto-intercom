# 公网对讲开发验证版

这个最小版本用现有 WebRTC 音频链路跑双人 TURN 转发。两台 Android 设备各发送不同测试音，接收端检查真实解码 PCM；没有麦克风或耳机也能验证。正常近场入口继续使用原有配置。

这是 KUM-89 的开发验证入口。生产公网按钮、目标锁/coordinator 接入和近场自动切换要在本阶段证据通过后另行实现。四人/SFU 不在本次范围。

已完成本地 coturn 和既有公网 coturn 的双模拟器原生音频验证。公网一轮两端均选中 relay/relay，并通过 RTP 与对端解码 PCM 检查，详见 [公网验证记录](../../rasen/changes/kum-89-public-relay-mvp/evidence/public-turn-run.md)。该轮信令运行在本机，真机蜂窝与设备验收仍待完成。

## 技术栈与模块

| 模块 | 实现与职责 |
| --- | --- |
| 信令服务 | Node.js 24 标准库 HTTPS、JSON、20 s 长轮询，无 npm 运行依赖 |
| 中继 | coturn，TURN REST HMAC-SHA1 临时凭证，共享 secret 仅存服务端 |
| Android | Kotlin、现有 stream-webrtc-android 1.3.9 / m125.4；Network.openConnection 绑定信令 DNS/socket；单 ADM、单 PC |
| 音频探针 | Android instrumentation，测试专用捕获 PCM 注入，经现有 gate/APM/Opus/TURN/解码；检查对端频率、RTP 和真实 selected pair |
| iOS | Swift typed TURN 配置、显式 relay policy、配置注入、串行 PC 生命周期与旧回调隔离；沿用 AVAudioSession playAndRecord/voiceChat |

## 本地运行

需要 Node.js ≥24、OpenSSL、Android SDK/JDK 17、两台 adb 设备和可达的 coturn。模拟器使用 `10.0.2.2` 访问电脑上的 HTTPS 服务。下列地址仅用于本机开发。

1. 在可运行 coturn 的 Linux/WSL 主机取得 IPv4 地址，例如 `hostname -I` 输出的 `172.20.71.254`。先确认 3478 和 UDP 47000–47100 未被其他服务占用。
2. 在本目录生成本地配置：

   ```powershell
   node dev-config.mjs --turn-host 172.20.71.254
   ```

   输出在被 Git 忽略的 `.local/`。脚本生成随机设备 token、TURN secret、3 天自签 TLS 证书和两个探针配置，不输出凭证。已有 `.local/` 时拒绝覆盖；需继续使用当前配置，或先自行归档旧目录再生成。

3. 在 Linux/WSL 中以前台独立进程启动 coturn，配置文件路径指向生成的 `.local/turnserver.conf`：

   ```sh
   turnserver -c /absolute/path/to/tools/relay/.local/turnserver.conf \
     --pidfile /tmp/motocom-relay-validation.pid
   ```

   本地配置禁用 TLS/DTLS TURN listener，只用于受控开发网络；HTTPS 信令仍校验证书和主机名。真正公网应使用受信证书、TURN/TLS、配额、访问策略及监控，重新完成设备验收。

4. 在电脑另一终端启动 HTTPS 服务：

   ```powershell
   $env:MOTOCOM_RELAY_CONFIG = "$PWD/.local/server.json"
   node server.mjs
   ```

5. 在仓库根目录构建：

   ```powershell
   ./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
   ```

   Windows 如遇中文路径的 Java wrapper 错误，可先用空闲盘符 `subst M: <仓库绝对路径>`，再从 `M:/` 执行。不要覆盖现有映射。

6. 回到本目录执行：

   ```powershell
   ./run-probes.ps1 -DeviceA emulator-5554 -DeviceB emulator-5556 -BuildRoot M:/
   ```

   脚本安装两个 debug APK，把配置复制到应用私有目录，分别运行一个探针，再验证 `.local/*-evidence.json` 和 instrumentation 结果。它不自动终止已有生产 Service；已有音频 owner 时探针拒绝启动。设备上已有同包名不同签名 APK 时，先选择干净测试设备，不自动卸载用户数据。

单端手工执行：先将私有配置放到应用 `files/relay-probe.json`，再运行：

```sh
adb -s SERIAL shell am instrument -w \
  -e class com.kuma.motointercom.RelayMediaProbeTest \
  -e relayConfig relay-probe.json \
  com.kuma.motointercom.instrumentation/androidx.test.runner.AndroidJUnitRunner
```

常规 instrumentation 未传 `relayConfig` 时跳过该双设备探针，不会把跳过当成 relay 验收通过。测试结束后可关闭本次 Node/coturn 进程；不要停止其他容器或服务。

## 接口与数据结构

服务配置 `server.json`：

```json
{
  "host": "127.0.0.1", "port": 8443,
  "tls": { "key": "/private/key.pem", "cert": "/private/cert.pem" },
  "devices": [{ "deviceId": "probe-a", "tokenSha256": "<64 hex>" }, { "deviceId": "probe-b", "tokenSha256": "<64 hex>" }],
  "pairs": [{ "pairId": "pair-1", "members": ["probe-a", "probe-b"] }],
  "turn": { "secret": "<random server-only secret, at least 32 characters>", "urls": ["turn:TURN_HOST:3478?transport=udp"] }
}
```

| 请求 | 鉴权与含义 |
| --- | --- |
| `POST /v1/sessions` | `Authorization: Bearer <deviceToken>`；body `{deviceId,runtimeId,pairId}`。设备 token 哈希和预授权双人成员必须同时匹配 |
| `GET /v1/sessions/{sessionId}/events?after=N` | `Bearer <leaseToken>`；返回 `{events:[…],closed:Bool}`，最多等待 20 s。每租约只允许一个并发 poll |
| `POST /v1/sessions/{sessionId}/signals` | `Bearer <leaseToken>`；body `{callId,sequence,kind,payload}`。kind 是 offer/answer/ice；payload 复用现有 Android/iOS SDP/ICE JSON 字符串 |
| `DELETE /v1/sessions/{sessionId}` | `Bearer <leaseToken>`；终止本次双方通话，唤醒 poll，旧租约不能影响新通话 |

加入响应：`{sessionId,leaseToken,deviceId,runtimeId,pairId,peerId,role,expiresAt,iceServers:[{urls,username,credential,expiresAt}]}`。时间均为 Unix 毫秒。TURN username 使用 `到期秒数:deviceId`，credential 为共享 secret 对 username 的 HMAC-SHA1/Base64。

双方均加入后，服务发出 `ready` 事件：`{sequence,type:"ready",callId,role,self:{deviceId,runtimeId},peer:{deviceId,runtimeId},expiresAt}`。客户端核对双方身份、运行时和租约，再创建媒体。offerer 为成员 deviceId 排序较小的一端。

转发事件：`{sequence,type:"signal",callId,from:{deviceId,runtimeId},kind,payload}`。`from` 由服务端产生，不信任请求体自报身份。`closed` 事件给出通话 ID 和终止原因。客户端按事件 sequence 连续处理，下一次 poll 的 after 仅确认已处理事件。

## 关键约束

- 一个设备至多一个活跃 runtime；同 runtime 重试加入幂等，其他 runtime 返回 409。没有公开注册、任意房间或第三成员加入。
- 内存服务用于开发验证，进程重启使租约失效，不保存生产配对库。最多配置 1000 个设备/配对，每队列 128 条，JSON 请求 ≤64 KiB，信令 payload ≤60 KB，每租约每 30 s 最多 256 条发送。
- 租约空闲 60 s、硬期限 1 h；TURN 凭证不超过租约硬期限。当前版本不做无缝续期，过期后先关闭再建新会话。客户端打开媒体前再次验证有效期，relay 失败不退回 direct。
- Android 复用 `LegacyRuntimeOwnership` 和完整 AudioSessionController/焦点/路由生命周期；native disposal 完成后才释放 owner。没有 activePc/standbyPc。
- 信令可绑定指定 Network；探针 `requireCellular:true` 时要求已存在蜂窝 Network，无可用网络即失败。`networkType` 和 selected pair 用于观察媒体；信令绑定不能证明媒体走蜂窝。Wi-Fi Direct 共存的真实媒体路径还需抓包与设备验证。
- 探针可选 `requireCellularMedia:true`（默认关闭）：在同一 PC 上设置蜂窝偏好，并增加实际 selected `networkType==cellular` 的硬门槛；native 拒绝配置、超时或媒体仍走 Wi-Fi 均失败。它同时要求蜂窝信令网络，独立记录两个选项，不代表生产媒体绑定。失败快照保留当前门槛、最后 RTP/transport 和音频 revision，`everMetMediaGate` 只作诊断。
- iOS `SessionCoordinator` 可注入 `WebRTCSessionConfiguration(iceServers:…,hostOnlyICE:false,networkMode:.relay)`。nearby 仍要求 host-only/no-server。实际 App target 必须接入获批 WebRTC SDK、麦克风权限和 `UIBackgroundModes: audio`（见 `ios/Resources/Info.plist.example`）；本仓库没有完成原生 SDK 音频 sink/后台验收。
- TLS 测试 CA 只在探针专用 client store 中可信，保留主机名验证。不要使用全局 trust-all 或 bindProcessToNetwork。配置/日志不应提交设备 token、私钥或共享 secret。

## 验收标准

开发探针 PASS 需要**双方分别**满足：当前 transport 真正引用的 selected pair 为 relay/relay、状态 succeeded、双向 RTP 比基线增长 >1000 bytes、音频 I/O 新鲜且开启、至少 100 帧包含对端频率的真实解码 PCM。候选收集、ICE connected 或收到 track 本身均不足以通过。

```powershell
node --test                       # 在 tools/relay 中
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

iOS 在 macOS/Xcode 中使用现有 `iOS Package CI` 运行协议、typed relay 和旧回调测试；条件编译的 GoogleWebRTC adapter 还需在获批 SDK App target 中单独编译。包测试通过不能代替 SDK 或后台音频验收。

| 上线设备门槛 | 通过标准 |
| --- | --- |
| 公网 relay 通话 P95 | ≤300 ms，实测端到端音频；ICE RTT 不能替代 |
| 断线至双向可听 P95 | ≤3 s |
| 连续 10 次重连 | ≥9/10 |
| 静默 30 min 后首包 | ≤500 ms，且无破音/吞字 |
| 双网切换静默 | ≤1.5 s，仅监控 |
| 双网/平台 | Wi-Fi Direct 存活时蜂窝媒体路由、真实蓝牙音频、中断恢复、iOS 后台 audio |

以上上线门槛均不由一次本地双模拟器探针自动判定。实际结果见 Rasen change 的 evidence；未运行的项保持未验证。

参考：[Android Network API](https://developer.android.com/reference/android/net/Network)、[coturn TURN REST 说明](https://github.com/coturn/coturn/blob/master/README.turnserver)、[本项目 ADR](../../docs/decisions/2026-10-16-kum-89-online-transport-engine-selection.md)。
