# 2026-10-17 本地原生 relay 验证

范围：Windows Node.js 24.14.1 HTTPS 服务 + WSL Ubuntu 24.04 coturn 4.6.1 + 两台 API 36 x86_64 模拟器。每设备一个 runtime owner、一个 AudioSessionController/ADM、一个 PC。纯软件捕获注入，未使用麦克风/耳机。

运行：`tools/relay/run-probes.ps1 -BuildRoot M:/`。两台 instrumentation 均 `OK (1 test)`，退出正常，所有者在 native disposal 后释放。配置与 TLS 私钥仅保存在忽略的 `.local`，不进入本证据。

| 端点 | 实际 selected pair | RTP 发送增量 | RTP 接收增量 | 对端解码测试音帧 | 结果 |
| --- | --- | --- | --- | --- | --- |
| emulator-5554 / probe-a | relay / relay，UDP，succeeded | 118251 bytes | 25661 bytes | 376 @1000 Hz | PASS |
| emulator-5556 / probe-b | relay / relay，UDP，succeeded | 25661 bytes | 117762 bytes | 2736 @500 Hz | PASS |

首轮在 fa6402a 对应实现上通过（200/2358 帧）；补充清理异常回归、测试 sink 解绑后，使用最终构建再次通过，表格和 JSON 为第二轮结果。

原始无凭证 JSON 和 instrumentation 输出在本目录。`networkType=cellular` 是模拟器虚拟网络枚举，`cellularRequested=false`；它不能证明手机蜂窝或 Wi-Fi Direct 双网选路。RTT 是 ICE 传输往返统计，不能作为音频端到端 P95。

尚未验证：真实公网/蜂窝+Wi-Fi Direct 共存、端到端音频 P95 ≤300 ms、断线恢复 P95 ≤3 s、10次重连≥9次、静默30 min后首包≤500 ms无吞字、自动切换静默、蓝牙/电话中断、iOS原生SDK与后台audio。这些不由本机开发探针签收。

## 工具清理限制

此前 TLS 测试使用临时目录；只读审查生成的 `C:/Users/kuma/AppData/Local/Temp/motocom-relay-tls-Zwlm5P` 删除请求被自动审批以 `blocked by policy` 拒绝，未绕过重试。当前 TLS 测试已改成仅在进程内存中生成 PEM，后续测试不再产生此类目录。
