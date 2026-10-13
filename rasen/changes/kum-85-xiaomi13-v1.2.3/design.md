## 版本与来源

唯一版本权威为version.properties。上一分发code6/1.2.2+bb0fa92已在目标手机确认；本次为code7/1.2.3+f587472。当前用户安装请求选择上一轮已审查修复Source，后续构建头只进行版本与证据打包；不把feature Source称为main功能合并，不走公开Release流程。

## 安装与证据

ADB mDNS自动发现当前已配对小米13，设备型号2211133C/API36。旧包拉取仅用于证书/元数据比对；不清数据或卸载。旧包及当前debug签名均为7F20F38D...1A8070F3，Release unsigned构建再沿用该本地历史个人证书签名。私钥不进库、不输出密码。

安装前后记录version/code、firstInstallTime、ceDataInode和已授权权限；UI树检查既有设置与日志。Android实际蓝牙/双机听音、射频与长时道路不因单机安装自动标记通过。Rasen仅记录流程，签名及部署由Root依据本次明确用户请求执行。
