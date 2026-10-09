## Processing and evidence

Pinned m125.4 [WebRtcVoiceEngine](https://github.com/GetStream/webrtc/blob/m125.4/media/engine/webrtc_voice_engine.cc) 在built-in NS启用成功后取消软件NS；软件NS=true对应kHigh。1.3.9 Java effects创建/启用错误不会自动恢复该native软件选项。指定硬件NS=false、正确googNoiseSuppression=true，使链路明确走软件处理。保留硬件AEC、AGC与Opus，不替换SDK/nativeABI或引入新用户功能。

RiderAudioIoEvidence只接受原生start/stop/error与PCM，不把“请求开始”当作实际采集。native错误使该实例后续PCM清零且只报告一次engine错误，产品更新仍经当前runtime主线程门禁。请求挂起/关闭期间的停止错误不当作新故障。真正的platform失败与可恢复的路由错误分开：前者停止当前online runtime并显示错误，避免失败engine永远Connected而无声；仍用已有RuntimeStopped/orchestrator入口，无第二产品writer。KUM-68将进一步使用这些证据判断UI音频就绪。

NativeCaptureDiagnostics在主线程读取本模块自己的pinned AudioRecord字段，日志仅包含数值输入/格式/状态。通过R8 keepclassmembers保留两个反射字段；查询异常只记录metadata-unavailable，不阻断通话，不扫描其他app。PCM线程只计算门控/记录freshness与排队日志。

正确约束是处理配置证据，不能由此宣称实机风噪减少多少；手机和普通蓝牙耳机的声音必须另行听音验证。
