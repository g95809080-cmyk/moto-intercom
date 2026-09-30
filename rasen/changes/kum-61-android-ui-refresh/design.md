## Context

Linear KUM-61，基线 0f5e7b778ee0b945725c9506055d6137a7faf546。方案来自用户确认的 UI 现状梳理。

## Goals / Non-Goals

让当前模式、可用操作和设置范围可理解。保留现有颜色、主题与导航。不改协议、媒体授权、房间身份、资源释放、后台服务或重连策略。

## Decisions

1. MainActivity 绑定服务时注册独立群组观察者，停止时移除；MainScreen 保留最新快照。busy 为真时首页呈现群组状态和返回房间入口，隐藏双人通话操作，避免声称双人离线代表整个服务离线。观察者仅影响 UI；所有群组动作仍通过现有服务执行。
2. 非群组模式原有主页继续呈现双人状态，主按钮复用 IntercomActionPolicy 文案，不改变 dispatch。活动双人通话仍可查看群组页；服务保持原有互斥判断和可重试消息，不新增切换模式命令。
3. 房间页使用共享原生卡片，成员与房主权限按快照呈现。输入仅 remember 于页面内，不保存、不记录日志。只有 IN_ROOM 后才清空输入，失败保留。权限请求 pending 状态来自 Activity 并禁用重复开始；后台完成仍遵守 resumed/pending 原有门禁。
4. 返回主页只导航；房主结束全队保留确认；移出成员新增明确确认，再向现有服务发送 Remove。非房主不展示解除移除限制。
5. 房间音频只改当前运行房间；设置页仍写既有默认偏好并作用于双人运行时。诊断折叠只影响呈现。
6. colors.xml、dimens.xml 与 MotoComTheme 为现有规范源，DESIGN.md 镜像并记录，UX-CONTRACT.md 记录生效范围与原生语义。

## Risks / Trade-offs

不以本地 UI 推断媒体就绪；只有 snapshot.voiceReady 为真显示全队语音就绪。导航和权限回调不创建新的 writer。准备、搜索、加入、重连、释放中一律显示实际服务消息。

## Validation

项目要求 motointercom-product-architect 独立只读审查；回归覆盖动作文案、群组主页与回放、失败输入保留、房主确认与导航、诊断展开。执行 JVM、Lint、Debug APK、Premium 静态审计；原生模拟器检查可用时执行，真实多机与听感不得以模拟器代替。
