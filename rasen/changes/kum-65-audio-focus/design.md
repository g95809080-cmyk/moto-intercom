## Boundaries

SessionOrchestrator/目标/信令不变。焦点授权与路由确认分别等待；GAIN 使用现有请求授权而不再抢一次焦点。关闭、无媒体和电话优先阻止恢复。永久丢失须等下一次显式媒体启动，不无限回抢。

独立审查指出 Android 按 listener identity 路由排队的焦点事件。每次新 request 使用新 listener/AudioFocusRequest，保留该轮对象用于 transient 等待和 abandon；abandon 先失效该代，上一轮排队 LOSS/GAIN 不得控制新媒体。

## Verification

确定性测试覆盖 transient 不 abandon、DELAYED 十秒无重请求、permanent 不恢复、迟到 route/gain、结束恢复 MODE_NORMAL；真实 Android focus producer 在 API 28/35 覆盖排队旧 LOSS/GAIN 跨 abandon→request 不影响新请求，当前 transient/GAIN 仍可交付。手机来电原有边界不变。
