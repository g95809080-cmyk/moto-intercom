## Why

KUM-72 / OCR-GROUP-01：主分支两个 legacy BUSY accept 线程共享 GroupBleBudget，无同步的时钟和集合更新可超预算或异常终止监听线程。

## What Changes

把完整 acquire 包括时钟读取纳入同一临界区，保留容量、地址、全局和窗口规则。以受控时钟验证最后一个额度，并通过真实双监听端口验证HELLO/BUSY、共享地址限制、下一窗口恢复和线程存活。

## Impact

独立main分支，不带入v1.2维护链。生产server仅追加默认不变的时钟参数供确定性排序测试；不改变身份、群组、媒体或发行版本。
