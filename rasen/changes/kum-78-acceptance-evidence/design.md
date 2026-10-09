# 设计

只修改 Android 开发验收工具和 Windows CI。协议工具保留原 envelope/identity 校验，第二帧在输出 received 前必须属于 CONNECT_ACCEPT/CONNECT_REJECT/BUSY；--expect 仍精确限制其终态。

采集工具通过 checked native exit 读取状态、系统属性、实际 DataStore device_id 和 APK hash。按 PreferencesProto map entry 精确解码 device_id，不扫描昵称 UUID。不同非空 serial、不同 canonical device_id、完整 model/release/正整数SDK、qemu标记为空或0才允许双真机标签；未知/模拟器/缺失身份失败。Stop 在收集前和收集后验证设备及 APK；无线端口可以变化，但对应 A/B 身份必须保持。

Start 只允许新/空目录。scenario-result.txt 是原子替换的唯一当前判定，外部命令前先置 NotRun/collecting；失败为 NotRun/capture_failed。Stop 每次写入 captures/<GUID>/，日志、数据库、摘要全部完成才发布 complete 和用户给定 Result。summary.md 根目录仅作中性索引；成功历史不覆盖，失败不冒充产品 Fail。数据库临时目录只用 GUID，删除前验证自有绝对路径；完整 integrity_check 必须为 ok。

回归只经实际 Windows PowerShell 5.1 CLI 与外部 mock ADB/Python；不替换生产函数。夹具生成真实 PreferencesProto 字节和仓库 Room schema 的10字段 SQLite，包含 UUID 昵称、损坏无关表但 paired_peers 可读、有效stdout/非零退出、重复Stop早/中/末失败及原历史hash不变。协议使用实际 subprocess/TCP loopback。旧源反证和最终绿分别保存；不得声称真实蓝牙、射频、Android9/16听音或硬件验收。
