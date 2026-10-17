## Why

用户已采纳复用 WebRTC、鉴权信令和 coturn 的公网通信路线。先交付可运行的单链路 relay 开发版本，验证凭证、选路与双向媒体，避免在证据不足时改造现有自动恢复。

## What Changes

- 归档公网引擎 ADR，关联 KUM-89、KUM-37、KUM-76；使用标准 UTF-8 阈值符号。
- 提供 Node.js HTTPS 双人信令服务：设备鉴权、预授权配对、有期限会话、隔离消息队列和 coturn REST 凭证。
- 为 Android 现有单媒体生命周期增加可选、不可变 relay 配置，以及当前 selected candidate pair 证据；正常近场默认不变。
- 提供每设备一个音频 owner 的 instrumentation 探针，复用真实 ADM/Opus/PCM gate，通过测试专用 PCM 注入避免依赖麦克风。
- iOS 增加 typed TURN 配置与 relay 模式，保持音频配置和后台 audio 契约，并防止重复 PC 与旧回调污染。
- 提供本地启动、测试、验收和未验证事项的中文说明。

## Capabilities

### New Capabilities

- `public-relay-validation`: 鉴权 HTTPS 信令、短期 TURN 凭证、双端单会话 relay 策略与开发验证。

### Modified Capabilities

无。保留生产近场、V2 信令、音频单 owner、配对数据库和版本身份契约。

## Impact

新增独立服务、工具和测试；修改 Android 媒体配置入口与传输证据、iOS WebRTC 配置及回调防护。复用现有依赖；不加入 GME，不实现四人/SFU、生产公网控制入口、自动切换、签名、部署或发布。主工作区 main 及原有未跟踪文档不变。
