# AI agent 使用指南

根目录 `AGENTS.md` 定义项目约束，`.agents/skills/rokid-glasses/SKILL.md` 定义任务流程，文档、源码和脱敏证据保存操作细节与验证结果。技能按任务加载所需资料。

## 调用示例

获取完整仓库，在仓库目录中启动 Codex。只读检查示例：

```text
读取仓库 AGENTS.md，使用 $rokid-glasses。
核验目标设备可复现的能力及缺少的前提。
仅执行只读检查，不安装、不改设置、不刷机。
```

允许安装助手、暂不安装固件的示例：

```text
使用 $rokid-glasses，目标是复现仓库已验证的开机联网、工程版 root 和可恢复优化。
识别目标设备和固件，完成兼容性及固件检查，保留原厂助手功能。
允许安装和授权助手；暂不安装固件，先报告目标设备的核验结果。
```

OTA 和系统优化按当前会话的授权范围执行；已有授权在同一会话内继续有效。读取仓库不构成设备写入授权。缺少可用 ADB、精确厂家包或匹配配置时，应先调查缺口，不将参考设备的测试结果视作目标设备的当前状态。

## Codex 如何发现技能

仓库将技能放在 `.agents/skills/rokid-glasses/`。Codex 会扫描工作目录到仓库根目录之间的 `.agents/skills`，也支持显式 `$rokid-glasses` 调用。若未出现在技能列表，重新打开会话，并确认当前工作目录属于这个 Git 仓库。发现规则依据 [Codex 官方技能文档](https://developers.openai.com/codex/skills/)；根目录指令入口依据 [AGENTS.md 文档](https://developers.openai.com/codex/guides/agents-md/)。

技能支持仓库内发现，无需全局安装。使用技能安装器时，安装路径为本仓库的 `.agents/skills/rokid-glasses`，并需保留完整仓库。单独安装后，通过技能目录中的 `scripts/context.py --repo /path/to/checkout --task overview` 定位资料。脚本不依赖开发者机器路径，不下载或刷写固件。

不支持 Codex 技能发现机制的 agent 可直接读取 `AGENTS.md` 和该 `SKILL.md`，再运行上下文定位脚本。

## 技能覆盖范围

| 任务 | 关键流程与判断依据 |
| --- | --- |
| 理解历史 | 已完成能力、证据日期、单台实机边界、未验证事项 |
| 自动联网排障 | 开机广播限制、后台定位、网络编号重建、关联与 DHCP 不应打断 |
| 安装/开发助手 | 同签名升级、权限/AppOps、首次启动、targetSdk 25 原因、源码模块 |
| 工程 OTA | 精确版本/证书/哈希、签名 GET 来源问题、设备 native verify、原生 A/B 安装、快照验收 |
| root | 工程版原生 adb root、本机 ADB + 系统 su、重启身份变化、认证依赖 |
| 优化/恢复 | 日志服务区分、Light/Deep Doze、原厂服务豁免、六个配色覆盖、首次基线与快照区别 |
| 验收/发布 | 真实协议与 UID/状态回读、恢复测试、隐私边界、固件获取和兼容性缺口 |

能力报告应区分参考设备已验证、目标设备实测和待验证事项。未匹配的硬件配置与尚未验证的厂商功能，需分别补充兼容性和功能测试。
