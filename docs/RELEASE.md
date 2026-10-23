# GitHub 发布准备

发布版本应核对源码、预编译 APK、签名与验证记录的一致性。主机工具离线测试与设备测试分别记录，离线测试结果不能替代实机安装结论。

发布前必须完成：

- 仓库所有者、仓库名、公开或私有属性。
- 确认源码版权归属并提供 LICENSE。缺少许可证时不能标为已开源；第三方固件、原厂 APK 不随源码许可证授权。
- 明确厂家工程包的可持续合法获取方式。没有固定包就只能复现助手层，不能承诺所有人获得相同工程版。
- 保持升级签名管理。1.7 预编译 APK 的私钥不公开；新维护者自有签名无法直接覆盖它。后续正式版本需明确签名与迁移策略。
- 阅读 `evidence/repository-validation.json` 的实际本地验证记录。GitHub CI 只有提交并运行后才有云端通过结论。
- 核对 `AGENTS.md`、`.agents/skills/rokid-glasses/` 和 `docs/AGENT_USAGE.md` 一同提交，技能入口能找到档案、文档、源码和证据。技能修改的本地验证另见 `evidence/agent-skill-validation.json`。

本地检查命令：

```sh
python3 -m unittest discover -s tests -v
python3 tools/check_public_tree.py
sh -n app/build.sh
git diff --check
```

公开边界：可提交源码、文档、设备档案、脱敏证据、自制 APK 和审查后的截图。不提交私钥、原始联网日志、个人标识、含授权查询参数的下载链接、完整厂家固件、提取系统镜像或厂商 APK。

兼容性反馈应区分代码支持、离线核验通过和目标硬件实机通过。其他地区或固件起点新增档案时，应先取得独立证据，再扩大支持范围。单纯更换档案中的哈希不构成适配。
