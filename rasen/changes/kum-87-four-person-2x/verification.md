# KUM-87 验证

2026-10-14已完成构建、签名、固定CI、正常分支合并及GitHub发布迁移。Base0f5e7b7、功能Source aee3b3f；修改范围仅版本、中文说明、workflow及本单合同。旧Release ID392331516（非immutable）的完整元数据与APK/校验文件已备份到忽略logs/2026-10-14-release-lines，旧APK SHA256为2FB6A1794611F4F65C19719CA880A06F56D92597B6467C1B45C6DF20C0137414，与GitHub资产digest一致。原Git标签保持0f5e7b7。

固定候选/实际构建Head为683a38dd603805c857bce55a5ae36e0aae708882。11/11路径只读全文审查APPROVED，P0=0/P1=0，仅批准候选和继续打包门禁，不提前认证签名、CI或发布。app/src tree d6dc5055ea94e821b9de9861cd4476c1c033ddab与功能Source/Base一致。

本地testDebugUnitTest/lintRelease/assembleRelease成功（4m42s）：94 suites / 728 tests，0 failure/error/skip；Release Lint 0 errors / 80 warnings。警告数是本轮实测，不沿用旧发行78条的数字。输出与报告保存在忽略logs/2026-10-14-release-lines/group-build-release.log及group-local-counts.json。

已签名的新APK为output/MotoCom-v2.0.0+aee3b3f-preview.apk（主工作区父目录）：包名com.kuma.motointercom，实际versionName=2.0.0+aee3b3f/code8，min23/target36，非debuggable。v1/v2/v3签名及16KiB ZIP对齐检查通过，证书SHA256为7F20F38DC1D7372CDE34CAC6E0E17D80EC995AC298C298FD0D24605E1A8070F3，与历史分发一致。APK SHA256为4FD04707AAA39C52AD724EF0BD6C02E0952D707B7A465807F8A36934765944E0。group-signature.txt/group-badging.txt记录原始检查，校验文件同目录。

PR #60与固定候选CI38528829906均通过。CI两项completed/success：API36 instrumentation job115735506251、verify job115735506624。最终签名包装门禁APPROVED/P0P1=0，独立核验实际APK、原始XML、本轮CI、旧/新证书、manifest及zipalign；复用11/11候选审查，并全文审查证据追加。本轮功能/版本/构建/workflow保持固定683候选，仅补证据不重复功能测试。

PR #60正常merge到four-person，提交225493e3c67e6aabe05fd56e61affe7f41ef28c4。新v2.0.0+aee3b3f标签指向实际构建683a38d；原v1.3.0标签仍0f5e7b7。既有Release ID392331516已迁到新2.0标签且公开为prerelease，链接https://github.com/g95809080-cmyk/moto-intercom/releases/tag/v2.0.0%2Baee3b3f。实际2.0APK/校验文件上传并核验服务器digest与本地一致，只有这两项资产，已备份的旧1.3资产从该Release移除。发布前后元数据及上传响应留存忽略logs。

全局code8已实际分发，后续新包至少9。四机、蓝牙、隔离环境和道路验收继续未完成；未部署到手机或改动iOS。证据收尾提交不改变实际构建Head或标签，Source保持不变。
