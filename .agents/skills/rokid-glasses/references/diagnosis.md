# 非显然的原因与失败经验

定位资料后，使用仓库 `docs/WORKLOG.md` 核对完整来由，使用源码核对当前实现。下面保留会改变下一步选择的诊断经验。

## 开机与 Wi-Fi

| 表现 | 已观察原因或应区分的阶段 | 下一步 |
| --- | --- | --- |
| 装了助手但没有开机记录 | 未首次打开、被强行停止，或厂商对较高 targetSdk 冷启动限制 | 检查启动状态、Manifest target 25、RUN_IN_BACKGROUND，再看实际广播日志；不改系统 JAR |
| 有 BOOT_COMPLETED，ADB 仍未恢复 | 广播成功后还可能被原厂关闭 Wi-Fi，或关联失败 | 分开检查 Wi-Fi 开关、ASSOCIATION_REJECTION、认证、DHCP；不用“ADB 断开”推断广播失败 |
| 前台能读网络、后台说没有保存网络 | 后台定位授权缺失，或 UID AppOps 仅 foreground | 核对前台/后台定位及 UID COARSE/FINE_LOCATION allow；权限错误不能当网络不存在 |
| 缓存网络 ID 失效 | 原厂手机服务可能删除并重建配置，SSID 相同但编号变化 | 通过当前可读网络按已保存 SSID 匹配；不重新索要或写入密码 |
| 恢复任务打断连接 | 正在关联、认证或 DHCP，或者已经连上 | 保留连接过程；按现有 AutoWifi 条件有限重试，不增加无限轮询或常驻服务 |

首次失败版本的记录说明不同阶段分别失败，不能恢复成早期单一原因。当前 30/90 秒补查是已验证的边界，长时间摘镜策略未证明。

## OTA、签名和 root

| 表现或判断 | 应采取的判断 |
| --- | --- |
| 找到包内证书，想给修改 OTA 签名 | 公钥证书不能签发固件。用当前设备信任库核对精确包，不能只信包内证书 |
| CRC/文件哈希正确 | 只能证明相应完整性，不能替代 OEM 整包签名和设备 native applicability |
| URL HEAD 返回 403 | 历史对象授权 GET，HEAD 的错误不说明 GET 的结果。仅对有权访问来源诊断方法/真实文件名，不暴力猜地址或公开授权参数 |
| 文件名版本相同，哈希不同 | 不是本工程已验证文件。停止安装，调查来源；不更新 profile 来消除失败 |
| 包是 full OTA | 无旧分区数据依赖仍不证明所有 bootloader/回滚/地区组合都适配 |
| bootloader 显示 unlocked | 历史已经如此，不能据此承诺 fastboot、EDL、Magisk 或锁定设备安装能力 |
| native verify 失败或 update 状态不明 | 保留当前原生状态和错误，停止安装/重启等依赖动作，不 reset-status 或擦除 |
| 工程版存在，但普通 APK su 失败 | 已验证工程版的系统 su 为 root:shell，普通应用无法直接执行；检查 RootBridge 本机 ADB + shell su 固定命令路径 |
| 重启后 UID 2000 | 历史工程版 adbd 重启回到 shell 属正常现象；授权允许时再次 adb root 后重连并回读 UID |
| RootBridge 收到 AUTH/STLS | 当前固件认证行为不满足旧通道，停止系统控制并报告依赖差异；不自动关认证或替换 adbd |

## 优化、显示和恢复

- `vendor.cnss_diag` 是诊断进程，区别于 Wi-Fi HAL、`cnss-daemon` 与 wpa_supplicant。调整诊断日志不需要停用无线连接关键服务。短时 CPU/写入对照不能换算成续航改善百分比。
- 保留 `logcatd`，调轮转份数，不批量删除旧日志。Light Doze 与 Deep Doze 独立；已验证组合为 light 开、deep 关。豁免原厂连接包，不能把服务仍存活当成手机完整交互回归。
- 原生设置会缓存颜色资源。先确认覆盖启用、资源 lookup，再重启设置应用检查新进程；不因旧截图未变就改物理面板或驱动。
- 亮度 136 是参考设备读数，其他设备应使用各自的恢复基线；预设的回读也不证明已测量光学亮度。
- `files/optimization-baseline.json` 是当前设备首次优化前状态。`system-state-snapshot.json` 仅记录某一时刻状态，不能替代恢复基线。清除数据/卸载前先恢复并回读；不能复制另一台的基线，也不能用配置恢复代替固件降级。
- 新编译 APK 默认不同签名，不能覆盖预编译版本。签名冲突时保全应用数据及基线，选择明确的迁移/独立开发方案，不自动卸载。

## 实现入口

| 模块 | 职责 |
| --- | --- |
| MainActivity | IP、真实 ADB 帧识别、首页入口 |
| AutoWifi / WifiBootReceiver | 有限补查、保存网络恢复、开机与更新处理 |
| ToolsActivity | 网络历史/状态、权限及系统设置入口 |
| PhotoActivity | MediaStore 分页、预览、分享入口和确认删除 |
| RootBridge | 本机 ADB 协议与固定系统 su 命令 |
| SystemProfile | 首次基线、优化脚本、恢复、快照与开机应用 |
| MaintenanceActivity | 优化切换、状态与确认恢复界面 |
| ScreenStyle | 黑底绿字及描边显示 |

包名 `local.syc.rokidadb` 是现有安装/升级身份，不能为了整理风格随意改动。Manifest target 25 有厂商兼容原因；提高版本后必须重新证明开机接收与联网。
