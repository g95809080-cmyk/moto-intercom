# 固定源码审查记录

审查角色：只读 `motointercom-product-architect`，主 Agent 是唯一写者。初始 MVP Base 为 `a72cea7c1ff1879a2b6175b17881d0686c22d9c4`；增量审查另列 Base。

## 396ecfdcd1a994bd83aa6aedf911597fa54278d1

REQUEST CHANGES。P0=1，P1=2。

- P0：原 Service 的 owner 仅等待 Wi-Fi 清理，新探针可能早于旧 native ADM disposal 启动。
- P1：探针获取 owner 后、finally 之前的配置解析异常可能保留 owner。
- P1：信令 publisher 提交与 shutdown 交错，RejectedExecutionException 可能逃出主 Looper。

以上已在 `fa6402a` 修复，包含 native disposal completion、工厂资源 reservation、Service 双清理屏障、withOwner 作用域和带 lease 检查的任务提交，以及对应确定性回归。

## fa6402a4069b2ee3321518c233c93690725f990a

REQUEST CHANGES。P0=0，P1=1。此前三项均核查已修。814 项 Android XML、APK SHA256 和双端同 call 的真实 relay/RTP/PCM 证据吻合；Node 13 项独立复跑通过。

- P1：AudioSessionController.close() 重新抛出 route/session/coordinator 清理异常时，未注册 native disposal completion，导致 owner 和 keepAlive 永久保留。

修复：在 finally 中注册 completion，并覆盖 route.close 抛错且 Wi-Fi/native 两种完成顺序。下一固定 Head 需再审；此前报告不作为最终批准。

## dcb426c43d227d07e00ab92503bfee1bbf0bad5a

APPROVED。P0=0，P1=0。finally 注册 native completion 的异常路径、controller 与 Service 两种顺序回归已核查；测试 sink 解绑与全步骤清理没有新增阻塞。

确认 98 suites / 817 tests，0 failure/error/skip，Lint 无 Error/Fatal，两个 APK 哈希与归档吻合；最终双端同 call 的 relay/relay、RTP增长、376/2736帧真实解码证据吻合。

Base：`a72cea7c1ff1879a2b6175b17881d0686c22d9c4`。固定源码 Head：`dcb426c43d227d07e00ab92503bfee1bbf0bad5a`。其后的提交仅更新文档与验收记录。

Next gate allowed：创建 Draft PR并运行 Android、Node、iOS 包 CI；本批准只覆盖开发验证 MVP。真实蜂窝/Wi-Fi Direct、端到端 P95、静默30 min、蓝牙/电话中断、iOS native SDK/后台仍未验，未授权部署/发布。

Draft PR：[KUM-89 relay MVP #62](https://github.com/g95809080-cmyk/moto-intercom/pull/62)。CI结果以 [GitHub checks](https://github.com/g95809080-cmyk/moto-intercom/pull/62/checks) 为准；本地记录不替代远程运行结果。

## 5f5727e89f3aa01b7eb72d328b914b1a0bc96043

APPROVED。P0=0，P1=0。Base：`5684f997ab62689c302f8c44fc0ef746a87c7154`。

此次源码仅修改 AndroidTest 探针：增加失败采样诊断，以及默认关闭的 `requireCellularMedia`。同一 RTC executor 内核对当前租约、session、PC 后设置原 relay 配置的蜂窝偏好，等待 native 接受配置才允许 offer/answer；拒绝或超时均失败。实际 selected `networkType==cellular` 是额外硬门槛，原 relay/RTP/当前音频 I/O/100 帧门槛保持，`everMetMediaGate` 仅作诊断。生产源码没有变化。

固定提交前 `assembleDebugAndroidTest`、`lintDebug` 成功。只读复审核实配置顺序、同 session/PC、最后采样和异常清理。Non-blocking：偏好不是生产网络绑定，失败 JSON 是最近取得的采样；公网 P95、静默 30 min、Wi-Fi Direct 共存及 iOS native/后台仍未验。

Next gate allowed：安装新测试 APK，在用户已授权范围内执行手机＋模拟器探针并保存双端原始证据。本批准不代表设备验收、生产集成、合并或发布通过。

后续证据只读复核：APPROVED，P0=0/P1=0。核对全部五轮 JSON/instrumentation、两轮同 call 完整 PASS、每轮 6 个本窗口新 coturn session（共 30 个、无跨轮重复）、清理回执和敏感值扫描。派生摘要已将第 2 轮实际 cellular 与整体 FAIL 分开，第 3 轮未取得路径记录为 null；原始结果不改。复核仅检查本地归档，没有再操作设备或云端。Next gate allowed：提交证据并同步 PR/Linear，不签收剩余物理/生产门槛。
