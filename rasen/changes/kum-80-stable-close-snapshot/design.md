## Fixed inputs

稳定Base b7f717bd9f4c635ddd442185fb8335e0a35928cd。KUM76批准源码5b9b6a7的两个生产文件相对该稳定Base恰好各一行快照差异。回归也从同一固定源码读取，保持真实Socket/HELLO、onReleased和executor/server关闭断言。

## Mechanism

Collection.toList会在size==1时调用iterator.next；worker可在size返回后删除最后lease。采用集合自身toArray快照，再对独立array转List，保留原锁边界及锁外I/O关闭。不为测试清空生产registry或宽松异常断言。

## Gate

旧源真实回归失败与修复绿分别保留；运行两个完整类、完整稳定JVM/Lint/双APK，固定SHA只读架构review。媒体/AndroidTest源不变，原生音频证据沿用KUM71，不声称新增射频或听音通过。Draft PR基于KUM71，KUM79包含本项批准增量后继续。
