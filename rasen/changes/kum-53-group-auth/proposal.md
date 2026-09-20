## Why
KUM-53 为六位码自动入队提供真实口令确认及加密消息基础，避免在公开发现字段中暴露口令或可穷举校验值。

## What Changes
- 固定BouncyCastle轻量J-PAKE依赖，封装一次性三轮认证和上下文绑定。
- 用HKDF派生双向AES-GCM密钥，严格有界帧和序列；失败/取消后关闭。

## Capabilities
### New Capabilities
- `group-auth`: 六位码认证与有界加密消息。
### Modified Capabilities
无。

## Impact
Gradle新增bcprov依赖，group认证代码及真实算法单测。不注册/替换系统provider，不写Android Keystore，不接BLE/Wi-Fi/UI；后续适配器仍须完成当前channel/成员准入。
