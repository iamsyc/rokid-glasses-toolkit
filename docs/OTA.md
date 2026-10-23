# 厂家签名工程 OTA：证据、复现和限制

## 已经验证的组合

| 字段 | 值 |
| --- | --- |
| 实机数 | 1 |
| 产品 | RG-glasses / glasses / neo，Android 12 API 32 |
| 源版本 | 1.27.009-20261014-150203，user/release-keys |
| 目标版本 | 1.28.001-20261020-150101，userdebug/release-keys |
| 文件 | RVE01-ota-1.28.001-20261020-150101.zip |
| 字节数 | 1117929620 |
| SHA-256 | `964648a3edb1d9355a087bcb130d72ff0036a399533537a3005750c555313d34` |
| 设备 OTA 证书 DER SHA-256 | `9fc9844e46a7c2794ba561ee418e29f37add9604e0825e6b1c877007ee87a993` |
| boot 状态 | 原本 unlocked/orange，未执行解锁 |

实测完整包为 full A/B OTA，无源分区哈希依赖；这不代表任意更老/更新固件、bootloader 或地区版本都兼容。AVB 链、回滚索引、分区布局和原生适用性均曾针对上述组合核对，尚无通用跨版本判定器。

## 固件获取

历史线索来自 [社区固件索引](https://rokid.andersmadsen.dk/firmware) 与 [通道信息接口](https://rokid-ota.andersmadsen.dk/info)，原始对象属于 Rokid 的 `rokid-factory-ota` OSS 域名。固定对象路径记录在设备 JSON 档案中。

原索引返回的签名 GET URL 曾缺少文件名的 `-ota-`，恢复正确文件名后成功获取。签名查询参数会过期，仓库不保存这些参数，不承诺不带授权的对象路径可以直接下载。2026-10-23 整理工程时，索引页可访问，但返回页面中没有找到该精确版本；信息接口读取超时。精确包长期可取得性尚未解决。

应自行从有权访问的厂家或社区来源取得**同一个哈希**的完整 ZIP。项目不重新分发厂家固件，没有获得其再分发授权。无法取得精确文件时停留在助手层，不用文件名相似的包替代。

## 主机离线核验

设置自己的目标和文件路径，先读取设备证书库。这只传输文件，不写设备固件：

```sh
export GLASSES_ADB='GLASSES_IP:5555'
export OTA_ZIP='/path/to/RVE01-ota-1.28.001-20261020-150101.zip'
mkdir -p work/ota
adb -s "$GLASSES_ADB" pull /system/etc/security/otacerts.zip work/ota/otacerts.zip
python3 -c 'import zipfile; print("\n".join(zipfile.ZipFile("work/ota/otacerts.zip").namelist()))'
```

参考设备的证书条目为 `releasekey.x509.pem`。核对目标设备的条目列表后提取证书，再执行验证；不能用待安装 OTA 内自带的证书替代设备信任库。

```sh
python3 -c 'import zipfile,pathlib; z=zipfile.ZipFile("work/ota/otacerts.zip"); pathlib.Path("work/ota/device-ota-cert.pem").write_bytes(z.read("releasekey.x509.pem"))'
python3 tools/rokid.py inspect-ota "$OTA_ZIP" \
  --device-certificate work/ota/device-ota-cert.pem --output-dir work/ota
```

工具先比对完整文件大小和固定 SHA-256，再检查设备/目标元数据、payload 大小/哈希、metadata 大小/哈希，动态计算 ZIP 中 payload 的偏移，提取含签名的 `metadata.bin`。指定设备证书后校验固定 DER 指纹及 Android 整包 CMS。OpenSSL `-nointern` 禁止包内证书成为受信签名者；`-noverify` 跳过 CA 链验证，内容签名仍被验证。

输出中 `wholefile_signature_verified_with_pinned_device_certificate` 必须为 true。未指定证书时仅执行固定文件及内部哈希检查，不构成 OEM 签名验证。该工具不解析 AVB 回滚兼容性，不替代后面的设备原生校验。

## 设备端适用性和传输

再次核对 `doctor` 的全部设备字段、精确源指纹和 boot 状态。仅上述已验证起点有实机证据。确认电量至少 50%、USB 供电、数据分区至少约 6 GiB 可用，且原生更新状态为 IDLE，无其他更新或快照合并正在进行。这里的空间/电量是项目操作门槛，不构成所有设备上的系统需求证明。

```sh
python3 tools/rokid.py doctor --serial "$GLASSES_ADB"
adb -s "$GLASSES_ADB" shell dumpsys battery
adb -s "$GLASSES_ADB" shell df -k /data
adb -s "$GLASSES_ADB" shell /system/bin/update_engine_client --status
adb -s "$GLASSES_ADB" push work/ota/metadata.bin /data/local/tmp/rokid-ota-metadata.bin
adb -s "$GLASSES_ADB" shell /system/bin/update_engine_client --verify --metadata=/data/local/tmp/rokid-ota-metadata.bin
```

要求原生验证退出 0，输出明确确认签名正确和 `Payload is applicable`。失败时不安装，不使用 reset-status 或手动改系统状态跳过失败。

```sh
adb -s "$GLASSES_ADB" push "$OTA_ZIP" /sdcard/Download/rokid-engineering-verified.zip
adb -s "$GLASSES_ADB" shell sha256sum /sdcard/Download/rokid-engineering-verified.zip
```

眼镜端 SHA-256 必须与上表完全一致。传输成功提示不能替代设备端哈希。下列安装步骤会更新固件，单独执行，执行前重新确认目标、版本、供电和原生状态。

## 提交原生 OTA

以下片段从离线核验生成的 JSON 读取偏移和大小，使用原始换行 properties 作为单一参数。没有自动重启或降级逻辑。

```sh
python3 - <<'PY'
import json, os, pathlib, shlex, subprocess
target = os.environ['GLASSES_ADB']
report = json.loads(pathlib.Path('work/ota/ota-inspection.json').read_text())
assert report['wholefile_signature_verified_with_pinned_device_certificate'] is True
assert report['ota_sha256'] == '964648a3edb1d9355a087bcb130d72ff0036a399533537a3005750c555313d34'
properties = pathlib.Path('work/ota/payload_properties.txt').read_text().strip()
command = ['/system/bin/update_engine_client', '--update',
    '--payload=file:///sdcard/Download/rokid-engineering-verified.zip',
    '--offset=' + str(report['payload_offset']), '--size=' + str(report['payload_size']),
    '--headers=' + properties, '--follow']
with pathlib.Path('work/ota/apply.log').open('w') as log:
    result = subprocess.run(['adb', '-s', target, 'shell', shlex.join(command)],
                            stdout=log, stderr=subprocess.STDOUT)
print('exit:', result.returncode, 'Inspect work/ota/apply.log before any reboot.')
PY
```

阅读保存的日志。要求退出成功，原生安装结束明确 `kSuccess (0)`，状态为 `UPDATED_NEED_REBOOT`，再由使用者执行 `adb -s "$GLASSES_ADB" reboot`。遇到错误或连接断开先查询原生更新状态，不重复提交，不擦除，不重置状态。文档片段本身没有自动保证所有前置步骤已完成。

## 首次启动、快照、root 与助手验收

等待同一设备无线恢复，核对版本、槽位、设备身份，确认没有连到另一台设备。不要只凭相同 IP。序列号可在本机私下比对，不提交到 issue。

```sh
adb connect "$GLASSES_ADB"
adb -s "$GLASSES_ADB" shell getprop ro.build.fingerprint
adb -s "$GLASSES_ADB" shell getprop ro.boot.slot_suffix
adb -s "$GLASSES_ADB" shell id
adb -s "$GLASSES_ADB" root
adb connect "$GLASSES_ADB"
adb -s "$GLASSES_ADB" shell id
adb -s "$GLASSES_ADB" shell 'bootctl is-slot-marked-successful $(bootctl get-current-slot)'
adb -s "$GLASSES_ADB" shell bootctl get-snapshot-merge-status
adb -s "$GLASSES_ADB" shell snapshotctl dump
```

参考设备升级后进入 `_b`，槽位成功，快照合并最终为 `none`；root 后 UID 0。目标设备的槽位应按实际升级前后状态核对。重启后 adbd 可恢复 shell 身份，需要再次 `adb root`。首次 `connect` 过早失败可等待后重试，固定超时不足以判定设备无法启动。DHCP 地址变化时需从路由器或眼镜页面重新确认。

按 [复现与验收](REPRODUCE.md) 检查助手、联网历史、权限、原厂连接服务与实际交互。已完成的历史记录没有完整验证手机语音/蓝牙工作流，其他使用者仍需实际验收。

已验证工程版的 TCP ADB 无密钥认证，SELinux 为 Permissive。部署流程不包含 VPN、网络拓扑、ADB 认证或防火墙修改。优化恢复入口仅恢复优化配置，不负责固件降级；消费版回退路线尚未做实机验证。
