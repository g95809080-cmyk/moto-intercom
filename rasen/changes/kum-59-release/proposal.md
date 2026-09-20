# KUM-59 发布准备

用户批准发布 main aee3b3f，版本 1.3.0+aee3b3f。只调整版本构建配置、README 和发布证据，不改功能源码。

设计：version.properties 统一提供 SemVer、递增 versionCode=4、获批功能完整 SHA；Gradle 校验并组合版本名。发布标签记录最终构建提交，后缀追溯功能基线。

退出条件：本地单测、Release Lint 与构建通过；只读审核无 P0/P1；PR CI 通过并按用户授权合并；签名版本和校验核对后正式发布；主工作区回到 main。
