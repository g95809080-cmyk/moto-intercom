# 实际通道及关闭终点

GroupSocketChannel 的 owner 查询只读取本对象 closed 和其捕获的实际 Host/Client owner。Runtime 在安装前、安装后和 Main 最终交付处校验 channel、queue、network attempt；撤销只 remove(key, exact value)，关闭自己的 queue/socket/key，不能借助第二个状态 writer 或清空其他通道。

已公布 channel 的正常关闭仍交付 Closed。Client 意外 readLoop 返回经现有真实 onFailed 回调交付当前 ControlFailed，未公布认证也能重连；主动 adapter.close、旧 adapter 替换和 stop 保持静默。SocketClient.run 保留局部 connection，在 finally 直接清理，不能依赖已经成功过的 parent CAS close。

closeControl 先复制 CHM values 到数组，再逐一关闭；保持原资源顺序与 1 秒 terminal flush。实际网络/身份/期限不在测试中放宽，fixture 仅接管 Android 无线引导与确定性调度。
