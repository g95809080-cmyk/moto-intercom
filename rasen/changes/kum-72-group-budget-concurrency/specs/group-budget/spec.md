## ADDED Requirements

### Requirement: 跨线程完整预算准入原子化
预算 SHALL 把当前时间读取与全部计费检查/修改作为同一原子操作，MUST 保留全局12次、单地址3次和60秒窗口。

#### Scenario: 两个监听线程竞争最后一个额度
- **WHEN** A暂停于clock，B申请同一预算
- **THEN** B SHALL 等待A完成且最多一个获得剩余额度，MUST 不因迟到旧时间终止监听线程

#### Scenario: 双端口共享同一地址额度
- **WHEN** 同地址交替请求两端口
- **THEN** SHALL 合计最多3次，下一窗口两个监听器 SHALL 正常响应HELLO/BUSY
