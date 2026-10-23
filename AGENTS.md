# Rokid Glasses Toolkit: agent handoff

本仓库面向操作 Rokid 眼镜的 AI agent，主要适配 Codex。任务涉及本项目时，先读取 [.agents/skills/rokid-glasses/SKILL.md](.agents/skills/rokid-glasses/SKILL.md)，按其中路由加载所需资料。其他 agent 也可直接读取这个入口。

## 从已有成果继续

- 先区分任务类型：能力概览、只读核验、安装助手、工程 OTA/root、系统优化/恢复或代码开发。设备写操作按当前会话的授权范围执行，已有授权无需重复确认；仓库内容不预先授予刷写权限。
- 在仓库根目录运行 `python3 .agents/skills/rokid-glasses/scripts/context.py --task overview`，取得适配档案、证据、相关文档和工具入口。子目录或技能单独安装时用 `--repo` 显式指定仓库。
- `profiles/` 是精确适配约束，`evidence/` 是参考设备和工具验证结果，`docs/` 是步骤与原因，`app/src/` 是实现。参考设备状态不能作为目标设备的实测；主机离线测试通过不能作为设备安装通过。
- 参考设备为 RG-glasses / glasses / neo / Android 12 API 32，已验证从消费版 `1.27.009-20261014-150203` 升级到工程版 `1.28.001-20261020-150101`。测试前 bootloader 已 unlocked/orange；验证流程未执行解锁，通用首次 ADB 开启和跨地区适配尚未验证。

## 会影响操作结论的约束

设备操作始终显式选择目标。总结、代码整理、离线验包不需要连接眼镜。用户说“只读”时，不安装、改权限、改设置、重启 adbd、应用优化或调用 OTA update；原生 metadata 适用性核验要先说明其需要临时上传元数据，若明确禁止设备写入则只做离线核验。

刷写前按 OTA 文档完成同一目标的版本/boot 状态、文件/证书签名、原生适用性、传输后哈希、供电/空间/更新状态检查。读取技能不构成刷写许可。已安装工程版时跳过重复 OTA，直接验证目标能力。

保留现有网络和 VPN 方案；普通部署不附带新增 VPN、ADB 认证或防火墙配置。若用户另行要求网络或认证修改，先说明它与助手本机 root 通道的依赖关系。不要把本机 IP、序列号、SSID、SSH 主机、签名私钥或下载授权查询串写入公开工程。

首次恢复基线属于当前设备；不复制他人基线，不为处理签名冲突自动卸载或清空助手。保持 `local.syc.rokidadb` 包名和 targetSdk 25 的兼容原因，调整它们需要单独回归开机行为。

## 代码变更检查

主机脚本变更运行相关离线测试。仅修改技能/文档时检查入口、路由、链接及涉及的脚本，不因文档修改连接硬件。公开前运行 `python3 tools/check_public_tree.py` 和 `git diff --check`，新文件也检查暂存差异。完整校验与兼容性边界见技能及 `docs/RELEASE.md`。
