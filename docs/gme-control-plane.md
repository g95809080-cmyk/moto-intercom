# GME 云对讲控制面（实验）

控制面负责“谁能和谁对讲、是否接受邀请、当前是哪次骑行”；云端语音由腾讯 GME 承载，本地语音由 WebRTC 承载。Phase 0–5 的测试栈已部署并完成线上控制面联调，**尚未完成腾讯媒体链路的双真机验收**。

## 本地上手

在项目根目录运行：

```powershell
cd backend/gme-control-plane
npm ci --ignore-scripts
npm test
cd ../..
.\scripts\gme\build-control-plane.ps1
```

打包产物为 `backend/gme-control-plane/.build/control-plane.zip`，只包含源码、锁定版本的依赖及包清单，不打包测试、数据库、SDK 下载缓存或配置文件。`node_modules` 与 `.build` 不入库。

`ws` 仅用于 Android JVM 双客户端合同测试。打包脚本在独立目录安装 `--omit=dev` 依赖，Lambda ZIP 不包含该测试服务器或 `ws`。合同测试复用同一控制面实现，真实运行 Java/Node 签名、HTTP 与事件通知，GME 票据为无真实授权的测试值。

测试默认使用内存事务存储。同一组控制面测试也能跑在真实的 DynamoDB Local 上，验证实际 AWS SDK 请求、条件事务和并发冲突：

```powershell
.\scripts\gme\test-dynamodb-local.ps1 `
  -DynamoDbLocalDirectory '<已解压的 DynamoDB Local 目录>'
```

测试脚本使用本机端口、内存数据库和固定的假凭据；拒绝远端数据库地址，运行后删除测试表并停止自己启动的进程。下载地址、校验和与 Java 要求以 [AWS 官方 DynamoDB Local 文档](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.DownloadingAndRunning.html) 为准。本轮使用官方 ZIP 并验证 SHA256。

Android 门禁仍从 `G:\` 映射运行，避免中文路径导致测试执行器找不到类：

```powershell
$env:JAVA_HOME='F:\Android\jbr'
G:\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
python scripts/gme/verify-artifacts.py
```

产物扫描会读取本机旧 GME 凭据并检查源码、APK 和 Lambda ZIP，只输出命中的文件与设置名，不输出凭据值。本轮曾在增量 APK 中发现源码已移除的旧令牌残留，最终交付必须以清理构建目录后的重建与复扫结果为准。

## 接口

所有 HTTP 请求都签名，包括 GET。WebSocket 仅用于事件和连接生命周期，不接受业务命令。HTTP API 使用 `$default` stage，签名路径直接从 `/v1` 开始，没有 `/prod` 前缀。

| 请求 | 行为与限制 |
|---|---|
| `POST /v1/devices/register` | 自证持有 EC P-256 私钥；登记 SPKI DER Base64 公钥；现有设备公钥不可直接替换 |
| `POST /v1/pairs/register` | 验证同一 transcript 的双方签名；保留配对证明；同一证明重试幂等 |
| `POST /v1/pairs/{pairId}/revoke` | 结束该配对的骑行、删除证明；设备无其他配对时删除公钥和连接引用 |
| `POST /v1/rides/start` | 提交 `pairId` 和随机 `requestId`；创建 `LOCAL_PENDING`，同一请求幂等 |
| `POST /v1/rides/{rideId}/local-established` | 双方分别确认本地已经建立；两方确认后才标记 `LOCAL_ACTIVE` |
| `POST /v1/rides/{rideId}/invite` | 只向在线、未忙线的车友发出 30 秒邀请；重复提交复用邀请；不签票 |
| `POST /v1/rides/{rideId}/invite/respond` | 提交 `inviteId` 和 `ACCEPT` / `DECLINE` / `BUSY`；接受时原子占用对端设备 |
| `POST /v1/rides/{rideId}/fallback` | 仅接受 `reason=TRANSPORT_FAILURE`；要求本地曾由双方确认成功；双向请求只迁移一次 |
| `POST /v1/rides/{rideId}/ticket` | 只在已批准云端状态取票；返回当前 `rideId`、`revision`、随机 `roomId`、双方 `openId` 和短期票据 |
| `POST /v1/rides/{rideId}/cloud-status` | 上报 `CONNECTED` / `FAILED`；两方连接后为 `CLOUD_ACTIVE`，失败结束骑行 |
| `POST /v1/rides/{rideId}/local-probe` | 提交 64 字符小写十六进制 `probeId`、布尔值 `ready`；双方相同探测连续稳定 10 秒后进入 `SWITCHING_LOCAL`，每次骑行最多 3 次 |
| `POST /v1/rides/{rideId}/local-switch` | 提交 `switchId`、`probeId`、`stage=PREPARED/COMPLETE/FAIL`；双方准备后 ARMED，双方完成后 LOCAL_ACTIVE；失败保持同房云端 |
| `POST /v1/rides/{rideId}/extend` | 云端达到上限进入 `CLOUD_PAUSED` 后，显式增加 4 小时并继续同次骑行 |
| `POST /v1/rides/{rideId}/end` | 任一方主动结束，释放双方设备占用；重复提交不重复产生结束事件 |
| `GET /v1/rides/{rideId}` | 配对成员读取骑行快照；过期状态先结算 |
| `GET /v1/state` | 读取自己的当前骑行、待处理邀请和配对车友在线状态；断线后补偿漏收事件 |
| `POST /v1/heartbeat` | 建议每 30 秒签名提交，可带 `rideId`；`connectionAlive=false` 时客户端应重新建立 WebSocket |
| `POST /v1/ws-token` | 签发 60 秒、单次使用的连接票据；数据库只保存哈希，不保存明文 |

同一设备最多 20 个配对、3 个有效控制连接，产品语音会话严格限制两个成员。`members` 使用列表以便将来扩展，当前没有多人行为。

## 签名与身份

请求头是 `X-Device-Id`、`X-Timestamp`、`X-Nonce`、`X-Signature`。时间戳为 13 位毫秒，时间窗 ±60 秒；nonce 为 16–128 个字母、数字、下划线或连字符，存储 120 秒。签名算法为 ECDSA SHA256，签名使用 ASN.1 DER Base64。

签名正文严格为下面五行的 UTF-8 字节，不在末尾补换行：

```text
METHOD
PATH
SHA256(raw body) 的小写十六进制
X-Timestamp
X-Nonce
```

GET 使用空正文；请求不接受 query。HTTP body 的原始字节参与哈希，不重新格式化 JSON。设备登记请求使用正文中的公钥验证持钥证明，之后使用已登记公钥。

每次新登记生成随机 `registrationId`。HTTP 验签结果、业务事务、WebSocket 票据和连接均绑定同一登记代次，防止旧签名或旧票据跨越解除配对与重新登记。DynamoDB 对每个事务读取的记录执行版本条件检查，身份改变时不会提交旧请求。

配对 `transcript` 必须恰好包含下面九个字段，按下列顺序使用紧凑 JSON 编码后由双方签名：

```text
protocolVersion, pairId, deviceIdA, deviceIdB, publicKeyA, publicKeyB,
createdAt, nonce, expiresAt
```

当前 `protocolVersion=1`，`deviceIdA < deviceIdB` 按字典序排列，公钥同样按设备对应排列。`createdAt` / `expiresAt` 为毫秒，证明最长 5 分钟，两份签名分别为 `signatureA` / `signatureB`。`pairId` 和 nonce 每次重新配对都重新生成。解除配对保留 10 分钟撤销标记，旧双签不能重新恢复关系。对于还未登记的配对，撤销键绑定请求方公钥的 SHA256 摘要，防止取消后的迟到证明恢复关系，也避免无关设备占用别人的 pairId；不存原始公钥、设备 ID 或签名。

## 状态、事件与时间

邀请未接受、邀请拒绝或超时、首次本地连接未成功、骑行主动结束，都不能取票或自动降级。本地降级要求双方的 `/local-established` 已经提交成功，单方声称“曾经连过”不足以批准。

状态与事件在同一 DynamoDB 事务提交。事件作为 outbox 条目写入，再由 DynamoDB Streams 触发 Lambda 推送；失败项重试，耗尽重试进入加密队列并触发告警。WebSocket 推送允许重复和乱序，客户端按 `eventId` 去重、按 `rideId + revision` 丢弃更旧事件。**同一 revision 可以有不同事件**，例如 `invite.accepted` 与 `ride.cloud.ready`，不能只因 revision 相等就丢弃。

事件信封为 `eventId/type/rideId/revision/occurredAt/payload`。已实现：

```text
presence.changed
invite.created / invite.accepted / invite.declined / invite.cancelled / invite.expired
ride.cloud.ready / ride.cloud.active / ride.cloud.failed / ride.cloud.warning
ride.local.switch
ride.ended
```

`presence.changed` 的 `rideId` 为 null，revision 属于设备，不参与骑行 revision 的排序。推送前再次核验配对和登记代次；恢复连接后用签名快照校正状态。

连接 90 秒无签名心跳后视为离线；骑行双方最后一次心跳均超过 10 分钟后结束。云端批准后累计计时，3 小时 30 分产生提醒，4 小时进入 `CLOUD_PAUSED` 并拒绝新票。Android 按服务端截止时间退出，提供提醒、手动延长和重建后的手动继续入口。

`local-probe` 要求双方当前观测相同且每次间隔不超过 3 秒。单方、不同探测、迟到报告不能保留稳定起点；服务端以自己的时钟计算 10 秒。达到条件后增加骑行 revision 和 `localSwitchAttempts`，创建 8 秒切换截止时间。`localSwitch` 快照包含 `switchId/probeId/phase/prepared/completed/expiresAt`；只有成员可提交，命令必须匹配当前标识。阶段为 PREPARING → ARMED → COMMITTED，失败或超时为 FAILED 并清空稳定观测。失败不会结束房间或重置云端额度；失败重试要求新稳定窗口，骑行累计最多 3 次，提交后再次降级也不重置次数。

只有双方 COMPLETE 才原子提交 `LOCAL_ACTIVE`、记录双方本地成功、清除云端连接确认并停止服务端云端时长累计。Android 此后才退 GME；腾讯账单不以此元数据计时替代。COMMITTED 后确认本地失败可经同房 fallback 再进云；旧 FAIL 不能将后续 CLOUD_PENDING 或其他切换强制改成云端已连接。切换截止时间纳入既有定时清理索引；命令和快照也即时结算超时。300ms 音频切换由 Android 本地预算控制，HTTP 事务不承诺该时延。

定时清理每分钟运行一次，**状态命令也会即时检查业务截止时间**，不依赖定时任务或 DynamoDB TTL 的删除速度。过期推送可能比截止时间稍晚，客户端应以 `expiresAt` 自行倒计时，不延长邀请有效期。

## GME 与数据保留

每次骑行随机分配房号；`openId` 从设备公钥哈希稳定派生为大于 10000 的十进制整数。当前 AppID 为 `1400802321`，房间类型由 Android 固定为 `FLUENCY`。签票算法从旧实验 Lambda 的无密钥辅助代码恢复，并参照 [腾讯服务端 AuthBuffer 示例](https://cloud.tencent.com/document/product/607/32156) 核对；过期时间真正写入加密内容，默认 300 秒，单测通过独立解密校验房号、身份和过期时间。

单表使用不同 PK 前缀，保留同一事务边界：

| 数据 | 保留 |
|---|---|
| `DEVICE`、`PAIR` | 配对解除时移除证明；无其他配对的设备同时移除公钥 |
| `RIDE`、骑行幂等结果、骑行事件 | 7 天 |
| 邀请与邀请事件、在线事件 | 24 小时 |
| nonce | 120 秒 |
| WebSocket 票据哈希 | 60 秒；使用后删除 |
| 连接记录 | 最后心跳后最多 10 分钟 TTL；在线有效期单独按 90 秒判断 |
| 撤销标记 | 10 分钟；无原始设备 ID、公钥或签名；未登记关系只绑定撤销方公钥摘要 |

DynamoDB TTL 是异步清理机制，上表是配置的逻辑保留期。表和日志资源设置 `Retain`，删除实验栈本身不会清空数据。未启用额外的数据库备份保留。

不保存音频、语音内容、GME 票据正文或 WebSocket 明文票据。AppKey 只在 Secrets Manager 和 Lambda 短时内存缓存中存在。日志只允许请求 ID、组件名、状态；API 访问日志不记录正文、签名或 WebSocket token 所在的 query。

## 模板与部署

`infrastructure/gme/template.yaml` 定义新的 `motocom-gme` 栈：HTTP API、WebSocket API、Lambda、单张事务表、Streams outbox、清理规则、失败队列、日志和告警。复用现有 `motocom/gme/app-key` Secret，不读取旧 client-token 或 room-salt Secret；不修改旧 `motocom-gme-ticket` 栈。

脚本默认只打印本地计划，不上传、不创建变更集、不部署：

```powershell
python scripts/gme/deploy-control-plane.py --bucket <同区域私有构建桶>
```

`--validate-only` 只调用 AWS 身份与模板校验；只有显式 `--apply` 才上传构建产物并创建、执行变更集。默认 profile 为 `agent-toolkit`、区域为 `ap-southeast-2`，脚本核对预期账号并拒绝向旧实验栈部署。Python 需要 `boto3`；使用 AWS login 凭据时还需要 `botocore[crt]`。

### Phase 5 已部署状态

2026-11-11 已将测试栈部署到账号 `401921623615` 的 `ap-southeast-2`，栈 `motocom-gme` 状态为 `UPDATE_COMPLETE`。端点如下：

```text
HTTP:      https://1nzpbifxo6.execute-api.ap-southeast-2.amazonaws.com
WebSocket: wss://0v8zmyfkuk.execute-api.ap-southeast-2.amazonaws.com/live
```

`scripts/gme/smoke-control-plane.mjs` 已对真实栈通过：两台临时 EC P-256 设备登记并双签配对，两个 WebSocket 连接收到邀请和云端就绪事件；接受后两端拿到同 Ride、同房间、同 AppID 的票据并进入 `CLOUD_ACTIVE`；双方本地成功确认后的 fallback 复用同房，结束骑行和撤销配对也成功。输出没有打印私钥、WebSocket token 或 `authBuffer`。

4 个 CloudWatch 告警当前均为 `OK`，并已连接 SNS；`explore-aws-budget` 已配置实际费用 80% 和预测费用 100% 告警。SNS 尚无邮箱订阅，腾讯 GME 用量告警仍需在腾讯控制台配置，AppKey 轮换也留到生产发布前处理。本轮未运行 `cfn-guard`，没有宣称独立安全合规门禁通过。

## 下一阶段

Android Keystore、双签配对、隐私同意持久化、签名请求与会话协调器均已接入。邀请接受或本地降级获批后调用服务的 `prepareApprovedCloudIntercom`，等待本地资源释放再进房；不要恢复 raw deviceId 直进房流程。

Android 已接入通知动作、WebSocket 重连与事件去重、本地断链降级、GME VOX、焦点和标准蓝牙路由，以及 10 秒稳定、每次骑行最多 3 次尝试的本地回切，细节与验证限制见 `gme-android.md`。Phase 5 的 API 36 模拟器 instrumentation 共 53 项全部通过，另有 2 项按环境条件跳过。后续只剩真机 Beta 和长时验收；模拟器或线上控制面联调不代表跨网络 GME 语音、头盔听感和 300ms 回切已验收。
