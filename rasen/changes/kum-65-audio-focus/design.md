## Boundaries

SessionOrchestrator/目标/信令不变。焦点授权与路由确认分别等待；GAIN 使用现有请求授权而不再抢一次焦点。关闭、无媒体和电话优先阻止恢复。永久丢失须等下一次显式媒体启动，不无限回抢。

## Verification

确定性测试覆盖 transient 不 abandon、DELAYED 十秒无重请求、permanent 不恢复、迟到 route/gain、结束恢复 MODE_NORMAL；手机来电原有边界不变。
