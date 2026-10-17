# 固定源码审查记录

审查角色：只读 `motointercom-product-architect`，主 Agent 是唯一写者。Base 始终为 `a72cea7c1ff1879a2b6175b17881d0686c22d9c4`。

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
