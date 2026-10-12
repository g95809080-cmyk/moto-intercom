## Why

实测主要为 Wi-Fi Direct。两次 DISCONNECTED 在182ms/1643ms后恢复，但现流程立即采用新的 recovery attempt，不能保留这类原链路自愈；清理与继任 SDK connect 也缺少原生操作完成屏障。初次无线失联原因未证实。

## What Changes

- 当前已接通、实际 native source 和已验证媒体控制 owner 短暂掉线观察2秒，原生在窗口内恢复则保持原连接。FAILED/CLOSED、真正 owner EOF/写失败或确认无线丢失立即恢复。
- 首次 loss 的 monotonic时间、序号和授权在 native producer 入口冻结；排队/观察共用首轮原有10秒预算及3秒fallback，不续期。计时器在最终采用前查询同 source 已登记的恢复证据。
- 同原车友、同runtime、真实HELLO的RECOVERY请求可推动正在观察的连接走既有恢复/glare；健康Connected、第三方及新runtime保留原门禁。
- 精确关闭旧媒体owner，验证仍可复用的 group 后在原deadline内重试Socket；同一 native channel 的 removeGroup完成前，继任connect/group/Socket不能越过清理屏障。

## Impact

保持产品状态唯一writer、TargetLock/HELLO、现有传输计划、三轮恢复/Reset/fresh discovery、媒体混播和蓝牙恢复。只处理已确认应用流程，不依据未证实的RF或设备品牌假设改行为。
