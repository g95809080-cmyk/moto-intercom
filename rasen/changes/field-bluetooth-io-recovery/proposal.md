## Why

用户实测导航/音乐混播已正常，但双方新连接后必须手动再次选择蓝牙才能恢复录放。日志存在系统请求蓝牙而实际录音仍为内置麦克风、权限迟到及重连后的重复录放启动。现有 readiness 检查缺少实际 native 输入/输出绑定，Activity 也没有在补授蓝牙权限后重试偏好的选路。

## What Changes

- 当前 media lease 定时查询实际 AudioRecord/AudioTrack routedDevice 与新鲜录音、播放数据。蓝牙通信路由与 native 录放均匹配后才提供蓝牙 readiness。
- 连通后持续 2 秒仍不匹配时，通过已有通信协调器自动重复手动选路使用的 suspend/select/resume，最多两次；未接通、来电及失去焦点期间不消耗恢复次数。
- 新授予 BLUETOOTH_CONNECT 时由实际 Activity/Service 重试已保存的蓝牙偏好，不覆盖显式手机偏好；权限缺失不再解释为耳机不存在。
- 记录 route revision、实际输入/输出类型、数据新鲜度、恢复次数和耗尽原因。

## Impact

只改 Android 稳定线音频就绪与恢复，不改变多应用混播、电话优先、PCM 静音/VOX、传输授权、身份、当前单/双通道计划及总尝试预算。代码、测试和独立只读审查在本地准备；真实 Linear 绑定尚受 connector 传输错误阻塞，不推进正式 PR/CI gate。
