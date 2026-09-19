## Context
KUM-54，Base 5d1c427。承接已确认 PRD 和 KUM-53。用户暂无实体设备，先完成代码和自动化。

## Goals / Non-Goals
实现可关闭的真实 Android BLE 消息与 Wi-Fi 网络适配器，以及它们使用的有界策略。本 issue 不写房间状态、不发 GroupJoinProof、不启动录音。后续单写者接入 PAKE、候选选择和房主准入。

## Decisions
- BLE 广告仅固定独立服务 UUID，不放房间代码、哈希或网络凭据。采用 GATT 单请求/响应邮箱；连接与消息不等于认证。首条应用消息可请求公开房间描述，后续传输 KUM-53 轮次；任何网络凭据只能由后续认证桥接在确认后加密提供。
- GATT 特征仅 READ/WRITE，写入使用有响应写。固定最低 ATT MTU 23 即可工作：每片最多 20 byte，4 byte total/offset 和至多 16 byte 数据，单消息最大 8192 byte。严格顺序，不接受重叠/跳片/并行消息；回调只能接收完整消息。读取响应为空表示尚未准备，客户端 100ms 后再读；服务端每次 read 推进一片，不使用 notification/CCCD，避免共享通知特征状态。每连接只有一个完整请求在处理/一个响应，重入关闭。
- 所有蓝牙回调转入一个 Handler，连接操作串行且对象身份检查。服务端最多 4 个连接、每连接 20 秒硬截止（独立 Handler runnable），连接结束移除 assembler/outgoing/认证回调引用。房间级每分钟 12 次连接预算、每地址每分钟 3 次，地址记录最多 64，满表拒绝新地址不逐出。预算只授予新连接，不把 Bluetooth MAC 当身份。收包验证前限制长度；权限/蓝牙关闭/异步失败均显式失败。
- 客户端 scan 独立可取消，候选去重最多 16，固定 12 秒窗口后停止，保留全部候选不自动选第一个；候选只含 opaque BluetoothDevice 和 RSSI，后续 PAKE 确认后才成为匹配房间。一次 client 连接只进行一个 exchange；绝对 deadline 不因轮次或轮询延长。
- Wi-Fi 房主先确认不存在其他 P2P group，再 createGroup，等 group info + connection info 都确认本机 GO 后输出私有网络描述。Wi-Fi P2P 官方 getNetworkName/getPassphrase 明确用于 legacy client 连接，所以不用可能匿名的 GO MAC。
- API29+ 成员通过 WifiNetworkSpecifier SSID/WPA2 passphrase + requestNetwork 等系统确认；结果返回 Network，后续 socket 使用 network.bindSocket，禁止绑定整个进程。API23–28 使用 legacy WifiConfiguration，追踪本 adapter 新建 networkId，成功确认当前 SSID、获取对应 Wi-Fi Network 后才返回；退出仅移除自己的新增配置并恢复前网络，决不删除第三方网络。
- 每个网络适配器 single-use，关闭先失效回调再释放自身 request/group；晚到 createGroup 成功须清理本次 group，不能影响后续 group owner。上层必须等 close completion 再创建下一网络 owner。建网截止 45 秒，close 最终回调有截止。双人适配器不修改。

## Risks / Trade-offs
ATT MTU23 分片低吞吐，最坏握手性能须设备测量，超过20秒显式失败；不声称完成 RF 或四设备共存验证。Legacy Wi-Fi 系统切网和 Wi-Fi Direct 客户端隔离仍需设备验收。单元测试证明边界和生命周期策略，Android编译证明API可用，不代替硬件结果。

## Sources
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pGroup
- https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap
- https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback
- https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser
