# 整合与所有权

先应用 main KUM-62 的诊断增量，再按 stable `e958fff38c98177a9995343601eb82d75e3cbdd6..d7eaa6c0c14b5edc00ef70bebd45af5cfd014266` 三方合并经批准的增量，逐处处理冲突，保持 main 多会话和 UI 行为。KUM-72/75 使用其相对同一 main 基线的独立增量。兼容 LegacyRuntimeOwnership 仅委托主线 group canonical 对象，不引入第二个锁或 owner。

共享音频平台保留一套 ADM、factory、local track；每个 MediaSession 拥有 PC、decoded renderer、播放状态及播放静音。Native capture 与播放回调不获取 registry 锁，使用原子 gate 和不可变 sessions 快照。只有单人替换或最后群组成员离开时撤销共享采集；中间成员只释放自己的输出与 PC。旧资源 dispose 在 registry admission 内排入 RTC FIFO，不能晚于继任者初始化。

音频就绪同时验证当前 gate、实际共享采集新鲜 PCM、当前成员真实 Android 输出写入、该 PC 双向 RTP。采集和成员播放修订分别校验，成员不得借用另一成员的就绪事实。系统中断释放全部旧输出，恢复时创建新输出并重启实际采集，保留 PC。

共享 ADM 致命故障由当前 GroupSession operation 的 Failed 事件统一结束和释放；单个当前 renderer 故障只发送对应 MediaSession 的错误，旧 renderer 无权结束继任者或其他成员。
