## Source

Base为0f5e7b778ee0b945725c9506055d6137a7faf546，来自原main及历史四人标签。功能Source仍aee3b3f8be28b787665e4237addd0bbe4622c701。只变更版本权威、文档和workflow分支选择，app、Gradle、iOS与Base一致。

## Release migration

重新打包2.0.0+aee3b3f/code8，旧证书保持。旧Release ID392331516、元数据及两项资产先备份并核验旧APK hash2fb6a179...；迁移既有Release到新v2.0.0标签和实际新APK，旧Git标签不移动。发布为四人预览版，不成为双人下载入口。

## Version contract

main双人1.x、four-person四人2.x，code全局递增，之后新包至少9。不把仅重编号当作修复或完成四机/道路验收。
