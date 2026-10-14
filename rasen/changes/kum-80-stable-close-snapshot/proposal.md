## Why

KUM79部署前确认稳定线仍遗漏KUM76已修复的两个pending集合关闭竞态，不能将已知关闭缺陷带入新测试包。

## What Changes

- 从KUM76回补LAN keys和Wi-Fi set的toTypedArray快照，再关闭真实资源。
- 回补两个实际HELLO/ready worker删除最后lease的确定性回归和自有fixture清理。

## Impact

无版本、协议、数据库、群组或iOS变化；不改唯一actor/资源归属、关闭顺序或terminal flush。KUM79只在本项固定源码批准后继续打包。
